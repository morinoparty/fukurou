import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { ManifestRun, StepResult } from "../../contract";
import { assetUrl } from "../../lib/assets";
import { formatDuration } from "../../lib/format";
import { codeBlockStyle } from "../../styles";
import { useLightbox } from "../lightbox/LightboxContext";
import { StatusBadge } from "../StatusBadge";

// 失敗したステップは行ごと赤く、実行されなかったステップは文字を薄くする
const failedRow = css({ bg: "bg.error", _hover: { bg: "bg.error" } });
const skippedRow = css({ color: "fg.disabled" });

const numberCell = css({ fontVariantNumeric: "tabular-nums", color: "fg.muted", width: "1%" });
const durationCell = css({ fontVariantNumeric: "tabular-nums", textAlign: "end", whiteSpace: "nowrap" });
const linkButton = css({
  fontSize: "xs",
  color: "colorPalette.fg",
  textDecoration: "underline",
  textUnderlineOffset: "2px",
  cursor: "pointer",
});
const stepError = css(codeBlockStyle, { mt: "1", color: "fg.error" });

interface StepTimelineProps {
  run: ManifestRun;
  steps: StepResult[];
}

/** シナリオのステップを実行順に並べた表（Chlorophyll の Table）。失敗したステップは行ごと強調する */
export function StepTimeline({ run, steps }: StepTimelineProps) {
  const openLightbox = useLightbox();
  // screenshot アクションの行から、そのステップで撮った画像を開く
  const openStepScreenshot = (step: StepResult, path: string) =>
    openLightbox({ src: assetUrl(run, path), caption: `${run.id} / step ${step.index}: ${step.label}` });
  if (steps.length === 0) return <p className={css({ color: "fg.muted" })}>No steps were recorded.</p>;

  return (
    <Table.Root size="sm" scrollAreaLabel="Scenario steps">
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
      <Table.Body>
        {steps.map((step) => {
          const screenshot = step.screenshot;
          const rowClass = step.status === "failed" ? failedRow : step.status === "skipped" ? skippedRow : undefined;
          return (
            <Table.Row key={step.index} id={`step-${step.index}`} className={rowClass}>
              <Table.Cell className={numberCell}>{step.index}</Table.Cell>
              <Table.Cell>{step.on ?? "–"}</Table.Cell>
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
        })}
      </Table.Body>
    </Table.Root>
  );
}
