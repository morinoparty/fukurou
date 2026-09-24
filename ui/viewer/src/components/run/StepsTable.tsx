import { type ReactElement, useState } from "react";
import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { ManifestRun, StepResult } from "../../contract";
import { assetUrl } from "../../lib/assets";
import { formatDuration } from "../../lib/format";
import {
  laneBars,
  leafSteps,
  wallDuration,
  type ParallelBlock,
  type RepeatIteration,
  type StepNode,
  type TimeBar,
} from "../../lib/stepTree";
import { codeBlockStyle } from "../../styles";
import { useLightbox } from "../lightbox/LightboxContext";
import { StatusBadge } from "../StatusBadge";

// 失敗したステップは行ごと赤く、実行されなかったステップは文字を薄くする
const failedRow = css({ bg: "bg.error", _hover: { bg: "bg.error" } });
const skippedRow = css({ color: "fg.disabled" });

const numberCell = css({
  position: "relative",
  fontVariantNumeric: "tabular-nums",
  color: "fg.muted",
  width: "1%",
  whiteSpace: "nowrap",
});
const durationCell = css({ fontVariantNumeric: "tabular-nums", textAlign: "end", whiteSpace: "nowrap" });
const linkButton = css({
  fontSize: "xs",
  color: "colorPalette.fg",
  textDecoration: "underline",
  textUnderlineOffset: "2px",
  cursor: "pointer",
});
const stepError = css(codeBlockStyle, { mt: "1", color: "fg.error" });
const laneNote = css({ display: "block", fontSize: "xs", color: "fg.muted", whiteSpace: "nowrap" });

// parallel / repeat の入れ子は、行の左端に縦線（レール）を段数分だけ引いて括る。
// レールの位置は段数で決まる実行時の値なので style で渡し、色だけ Panda のクラスにする
const RAIL_STEP_PX = 10;
const railStyle = css.raw({ position: "absolute", top: "0", bottom: "0", width: "2px", pointerEvents: "none" });
const parallelRail = css(railStyle, { bg: "colorPalette.solid" });
const repeatRail = css(railStyle, { bg: "border.emphasized" });

// ブロックの見出し行（repeat の回・parallel）
const blockCell = css({ position: "relative", bg: "bg.subtle" });
const blockCellFailed = css({ position: "relative", bg: "bg.error" });
const blockHeadButton = css({
  display: "inline-flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: "2",
  textAlign: "start",
  cursor: "pointer",
  borderRadius: "xs",
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "colorPalette.focus.ring", outlineOffset: "2px" },
});
const blockLine = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" });
const blockLabel = css({ fontWeight: "semibold", fontSize: "sm" });
const blockKind = css({
  px: "1.5",
  borderRadius: "xs",
  fontSize: "xs",
  fontWeight: "semibold",
  textTransform: "uppercase",
  letterSpacing: "wide",
  bg: "colorPalette.bg.subtle",
  color: "colorPalette.fg",
});
const blockMeta = css({ fontSize: "xs", color: "fg.muted" });
const blockMetaFailed = css({ fontSize: "xs", color: "fg.error", fontWeight: "semibold" });
const chevron = css({
  display: "inline-block",
  width: "3",
  color: "fg.muted",
  transitionProperty: "transform",
  transitionDuration: "fast",
});
const chevronOpen = css({ transform: "rotate(90deg)" });

// parallel ブロックの時間バー（レーンごとに 1 行）。同時に動いた時間が重なって見える
const gantt = css({
  mt: "2",
  display: "grid",
  gridTemplateColumns: "auto minmax(0, 1fr)",
  columnGap: "3",
  rowGap: "1",
  alignItems: "center",
  maxWidth: "2xl",
});
const ganttLabel = css({ fontSize: "xs", color: "fg.muted", whiteSpace: "nowrap" });
const ganttTrack = css({ position: "relative", height: "2.5", borderRadius: "xs", bg: "bg.emphasized", overflow: "hidden" });
const barStyle = css.raw({ position: "absolute", top: "0", bottom: "0", minWidth: "2px", borderRadius: "xs" });
const passedBar = css(barStyle, { bg: "colorPalette.solid" });
const failedBar = css(barStyle, { bg: "fg.error" });
const otherBar = css(barStyle, { bg: "fg.muted" });
const ganttScale = css({
  gridColumn: "2",
  display: "flex",
  justifyContent: "space-between",
  fontSize: "xs",
  color: "fg.muted",
  fontVariantNumeric: "tabular-nums",
});

/** 入れ子の中の位置。rails は外側から順のブロックの種類、lane は囲む parallel のレーン番号 */
interface Nesting {
  rails: ("parallel" | "repeat")[];
  lane: number | null;
}

const TOP: Nesting = { rails: [], lane: null };

/** 1 回の繰り返しを最初から開いておくか。回数が少ないか、失敗を含む回だけ開く */
const MAX_OPEN_ITERATIONS = 10;

interface StepsTableProps {
  run: ManifestRun;
  testId: string;
  nodes: StepNode[];
  failureStep: number | null;
}

/**
 * ステップの表。parallel ブロックは見出し行（レーンごとの時間バー付き）とレールで括り、
 * repeat の回ごとに見出し行を出して開閉できるようにする。失敗したステップは行ごと強調する
 */
export function StepsTable({ run, testId, nodes, failureStep }: StepsTableProps) {
  const openLightbox = useLightbox();
  // repeat の回の開閉状態。触っていない回は既定値（hasProblem / 回数）に従う
  const [openIterations, setOpenIterations] = useState<Record<string, boolean>>({});

  // screenshot アクションの行から、そのステップで撮った画像を開く
  const openStepScreenshot = (step: StepResult, path: string) =>
    openLightbox({ src: assetUrl(run, path), caption: `${run.id} / ${testId} / step ${step.index}: ${step.label}` });

  const isProblem = (step: StepResult) => step.status === "failed" || step.index === failureStep;

  const defaultOpen = (node: RepeatIteration) =>
    node.repeat.of <= MAX_OPEN_ITERATIONS || leafSteps(node.children).some(isProblem);

  const isOpen = (node: RepeatIteration) => openIterations[node.key] ?? defaultOpen(node);

  // 描画時の閉包ではなく最新の state から反転する（連続クリックでも取りこぼさない）
  const toggle = (node: RepeatIteration) =>
    setOpenIterations((state) => ({ ...state, [node.key]: !(state[node.key] ?? defaultOpen(node)) }));

  /** 入れ子を行の列にする。見出し行とステップ行が交互に並ぶ */
  const renderNodes = (list: StepNode[], nesting: Nesting): ReactElement[] =>
    list.flatMap((node) => {
      if (node.kind === "step") return [renderStep(node.step, nesting)];
      if (node.kind === "iteration") return renderIteration(node, nesting);
      return renderParallel(node, nesting);
    });

  const renderIteration = (node: RepeatIteration, nesting: Nesting): ReactElement[] => {
    const open = isOpen(node);
    const steps = leafSteps(node.children);
    const failed = steps.filter((step) => step.status === "failed").length;
    const skipped = steps.filter((step) => step.status === "skipped").length;
    const inner: Nesting = { rails: [...nesting.rails, "repeat"], lane: nesting.lane };
    const head = (
      <Table.Row key={`${node.key}:head`} data-repeat-block={node.repeat.block} data-iteration={node.repeat.iteration}>
        <Table.Cell colSpan={6} className={failed > 0 ? blockCellFailed : blockCell}>
          <Rails rails={nesting.rails} />
          <div style={{ paddingInlineStart: railsWidth(nesting.rails) }}>
            <button type="button" className={blockHeadButton} aria-expanded={open} onClick={() => toggle(node)}>
              <span className={`${chevron} ${open ? chevronOpen : ""}`} aria-hidden="true">
                ›
              </span>
              <span className={blockKind}>repeat</span>
              <span className={blockLabel}>
                iteration {node.repeat.iteration}/{node.repeat.of}
              </span>
              <span className={failed > 0 ? blockMetaFailed : blockMeta}>
                {steps.length} {steps.length === 1 ? "step" : "steps"} · {formatDuration(wallDuration(node.children))}
                {failed > 0 && ` · ${failed} failed`}
                {skipped > 0 && ` · ${skipped} skipped`}
              </span>
            </button>
          </div>
        </Table.Cell>
      </Table.Row>
    );
    return open ? [head, ...renderNodes(node.children, inner)] : [head];
  };

  const renderParallel = (node: ParallelBlock, nesting: Nesting): ReactElement[] => {
    const steps = leafSteps([node]);
    const failed = steps.filter((step) => step.status === "failed").length;
    const bars = laneBars(node);
    const sum = steps.reduce((total, step) => total + (step.durationMs ?? 0), 0);
    const head = (
      <Table.Row key={`${node.key}:head`} data-parallel-block={node.block}>
        <Table.Cell colSpan={6} className={failed > 0 ? blockCellFailed : blockCell}>
          <Rails rails={nesting.rails} />
          <div style={{ paddingInlineStart: railsWidth(nesting.rails) }}>
            <div className={blockLine}>
              <span className={blockKind}>parallel</span>
              <span className={blockLabel}>
                {node.lanes.length} {node.lanes.length === 1 ? "lane" : "lanes"} at the same time
              </span>
              <span className={failed > 0 ? blockMetaFailed : blockMeta}>
                {formatDuration(wallDuration([node]))} wall · {formatDuration(sum)} total
                {failed > 0 && ` · ${failed} failed`}
              </span>
            </div>
            {bars ? (
              // 時間バーは見た目の補助。各ステップの所要時間は下の行にも文字で出ている
              <div className={gantt} aria-hidden="true">
                {bars.lanes.map(({ lane, bars: laneSteps }) => (
                  <GanttLane key={lane.lane} label={laneLabel(lane.lane, leafSteps(lane.children))} bars={laneSteps} />
                ))}
                <div className={ganttScale}>
                  <span>0</span>
                  <span>{formatDuration(bars.span.end - bars.span.start)}</span>
                </div>
              </div>
            ) : (
              <p className={blockMeta}>Start and finish times were not recorded, so the overlap cannot be shown.</p>
            )}
          </div>
        </Table.Cell>
      </Table.Row>
    );
    const rows = node.lanes.flatMap((lane) =>
      renderNodes(lane.children, { rails: [...nesting.rails, "parallel"], lane: lane.lane }),
    );
    return [head, ...rows];
  };

  const renderStep = (step: StepResult, nesting: Nesting) => {
    const screenshot = step.screenshot;
    const rowClass = step.status === "failed" ? failedRow : step.status === "skipped" ? skippedRow : undefined;
    return (
      <Table.Row key={step.index} id={`step-${step.index}`} className={rowClass}>
        <Table.Cell className={numberCell}>
          <Rails rails={nesting.rails} />
          <span style={{ paddingInlineStart: railsWidth(nesting.rails) }}>{step.index}</span>
        </Table.Cell>
        <Table.Cell>
          {step.on ?? "–"}
          {nesting.lane !== null && <span className={laneNote}>lane {nesting.lane}</span>}
        </Table.Cell>
        <Table.Cell>
          <code>{step.action}</code>
        </Table.Cell>
        <Table.Cell className={css({ minWidth: "48", wordBreak: "break-word" })}>
          <div>{step.label}</div>
          {screenshot && (
            <button type="button" className={linkButton} onClick={() => openStepScreenshot(step, screenshot)}>
              View screenshot
            </button>
          )}
          {step.error && <pre className={stepError}>{step.error}</pre>}
        </Table.Cell>
        <Table.Cell>
          <StatusBadge status={step.status} />
        </Table.Cell>
        <Table.Cell className={durationCell}>{formatDuration(step.durationMs)}</Table.Cell>
      </Table.Row>
    );
  };

  return (
    <Table.Root size="sm" scrollAreaLabel="Steps">
      <Table.Header>
        <Table.Row>
          <Table.Head>#</Table.Head>
          <Table.Head>On</Table.Head>
          <Table.Head>Action</Table.Head>
          <Table.Head>Label</Table.Head>
          <Table.Head>Status</Table.Head>
          <Table.Head className={css({ textAlign: "end" })}>Duration</Table.Head>
        </Table.Row>
      </Table.Header>
      <Table.Body>{renderNodes(nodes, TOP)}</Table.Body>
    </Table.Root>
  );
}

/** レールの分だけ中身を右へずらす幅 */
function railsWidth(rails: Nesting["rails"]): number {
  return rails.length * RAIL_STEP_PX;
}

/** 行の左端の縦線。セル（position: relative）の上から下まで引く */
function Rails({ rails }: { rails: Nesting["rails"] }) {
  return rails.map((kind, depth) => (
    <span
      key={depth}
      aria-hidden="true"
      className={kind === "parallel" ? parallelRail : repeatRail}
      style={{ left: 2 + depth * RAIL_STEP_PX }}
    />
  ));
}

/** 時間バーの行見出し。レーンのステップの対象（プレイヤー名など）を並べる */
function laneLabel(lane: number, steps: StepResult[]): string {
  const targets = [...new Set(steps.map((step) => step.on ?? "wait"))];
  return `lane ${lane} · ${targets.join(", ")}`;
}

/** 1 レーン分の時間バー。repeat のレーンでは回ごとにバーが並ぶ */
function GanttLane({ label, bars }: { label: string; bars: TimeBar[] }) {
  return (
    <>
      <span className={ganttLabel}>{label}</span>
      <span className={ganttTrack}>
        {bars.map((bar) => (
          <span
            key={bar.step.index}
            title={`#${bar.step.index} ${bar.step.action} ${bar.step.label} · ${formatDuration(bar.step.durationMs)}`}
            className={bar.step.status === "passed" ? passedBar : bar.step.status === "failed" ? failedBar : otherBar}
            style={{ left: `${bar.left}%`, width: `${bar.width}%` }}
          />
        ))}
      </span>
    </>
  );
}
