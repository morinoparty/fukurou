import { useState } from "react";
import { css } from "styled-system/css";
import type { ManifestRun, ResetInfo, StepResult, TestResult } from "../../contract";
import { formatDuration } from "../../lib/format";
import { buildStepTree, wallDuration } from "../../lib/stepTree";
import { codeBlockStyle, panelStyle } from "../../styles";
import { StatusBadge } from "../StatusBadge";
import { StepsTable } from "./StepsTable";

// phase ごとのまとまり。見出しのボタンで折りたたむ
const groupBox = css(panelStyle, { overflow: "hidden" });
const groupHeadStyle = css.raw({
  width: "full",
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: "2",
  px: "3",
  py: "2",
  textAlign: "start",
  bg: "bg.muted",
  cursor: "pointer",
  _hover: { bg: "bg.emphasized" },
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "colorPalette.focus.ring", outlineOffset: "-2px" },
});
const groupHead = css(groupHeadStyle);
const groupHeadFailed = css(groupHeadStyle, { bg: "bg.error", color: "fg.error", _hover: { bg: "bg.error" } });
const phaseLabel = css({ fontWeight: "semibold" });
const groupMeta = css({ fontSize: "xs", color: "fg.muted" });
const chevron = css({
  display: "inline-block",
  width: "3",
  color: "fg.muted",
  transitionProperty: "transform",
  transitionDuration: "fast",
});
const chevronOpen = css({ transform: "rotate(90deg)" });
const resetRow = css(panelStyle, { px: "3", py: "2", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" });
const resetError = css(codeBlockStyle, { color: "fg.error", width: "full" });

/** 連続する同じ phase / fixture のステップのまとまり */
interface StepGroup {
  key: string;
  phase: StepResult["phase"];
  fixture: string | null;
  steps: StepResult[];
}

/** 実行順のまま、phase と fixture が変わるところで区切る */
export function groupSteps(steps: StepResult[]): StepGroup[] {
  const groups: StepGroup[] = [];
  for (const step of steps) {
    const last = groups[groups.length - 1];
    if (last && last.phase === step.phase && last.fixture === step.fixture) {
      last.steps.push(step);
    } else {
      groups.push({
        key: `${groups.length}:${step.phase}:${step.fixture ?? ""}`,
        phase: step.phase,
        fixture: step.fixture,
        steps: [step],
      });
    }
  }
  return groups;
}

/** まとまりの見出し。fixture は名前付き、test はテスト自身のステップ */
function groupTitle(group: StepGroup): string {
  if (group.phase === "fixture") return `fixture ${group.fixture ?? ""}`.trim();
  if (group.phase === "beforeEach") return "beforeEach";
  return "test steps";
}

/** そのまとまりに失敗（または skipped 以外の異常）が含まれるか。含まれるなら折りたたまない */
function hasProblem(group: StepGroup, failureStep: number | null): boolean {
  return group.steps.some((step) => step.status === "failed" || step.index === failureStep);
}

interface StepTimelineProps {
  run: ManifestRun;
  test: TestResult;
}

/**
 * テストのステップを phase ごとにまとめて実行順に並べる。
 * beforeEach と fixture のまとまりは既定で折りたたみ、失敗を含むものだけ開いた状態にする。
 * 先頭にはハーネスのリセット（ステップではない）の所要時間と結果を出す
 */
export function StepTimeline({ run, test }: StepTimelineProps) {
  const groups = groupSteps(test.steps);
  const failureStep = test.failure?.stepIndex ?? null;
  return (
    <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
      <ResetRow reset={test.reset} />
      {groups.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>No steps were recorded.</p>
      ) : (
        groups.map((group) => (
          <Group
            key={group.key}
            run={run}
            testId={test.id}
            group={group}
            defaultOpen={group.phase === "test" || hasProblem(group, failureStep)}
            failureStep={failureStep}
          />
        ))
      )}
    </div>
  );
}

/** ハーネスのリセットの行。実行しなかったテストでは null なので出さない */
function ResetRow({ reset }: { reset: ResetInfo | null }) {
  if (!reset) return null;
  return (
    <div className={resetRow}>
      <span className={phaseLabel}>Harness reset</span>
      <StatusBadge status={reset.error ? "error" : "passed"} />
      <span className={groupMeta}>{formatDuration(reset.durationMs)}</span>
      {reset.error && <pre className={resetError}>{reset.error}</pre>}
    </div>
  );
}

interface GroupProps {
  run: ManifestRun;
  testId: string;
  group: StepGroup;
  defaultOpen: boolean;
  failureStep: number | null;
}

/** 1 まとまり分。見出しのボタンで開閉し、中身は Chlorophyll の Table */
function Group({ run, testId, group, defaultOpen, failureStep }: GroupProps) {
  const [open, setOpen] = useState(defaultOpen);
  const failed = group.steps.filter((step) => step.status === "failed").length;
  const skipped = group.steps.filter((step) => step.status === "skipped").length;
  // 同時に動いたステップを二重に数えないよう、parallel ブロックは壁時計の時間で数える
  const nodes = buildStepTree(group.steps);
  const total = wallDuration(nodes);
  const first = group.steps[0];
  const last = group.steps[group.steps.length - 1];
  const bodyId = `steps-${group.key.replace(/[^A-Za-z0-9_-]/g, "-")}`;
  return (
    <section className={groupBox} data-phase={group.phase}>
      <button
        type="button"
        className={failed > 0 ? groupHeadFailed : groupHead}
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={`${chevron} ${open ? chevronOpen : ""}`} aria-hidden="true">
          ›
        </span>
        <span className={phaseLabel}>{groupTitle(group)}</span>
        <span className={groupMeta}>
          {group.steps.length} {group.steps.length === 1 ? "step" : "steps"}
          {first && last && ` (#${first.index}–#${last.index})`} · {formatDuration(total)}
          {failed > 0 && ` · ${failed} failed`}
          {skipped > 0 && ` · ${skipped} skipped`}
        </span>
      </button>
      <div id={bodyId} hidden={!open}>
        <StepsTable run={run} testId={testId} nodes={nodes} failureStep={failureStep} />
      </div>
    </section>
  );
}
