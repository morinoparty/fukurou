import { css, cx } from "styled-system/css";
import type { ManifestV2 } from "../../contract";
import { commitUrl } from "../../lib/ci";
import { formatDateTime, shortHash } from "../../lib/format";
import { eyebrow, pageTitle, panelStyle } from "../../styles";

const meta = css({ mt: "1", display: "flex", flexWrap: "wrap", columnGap: "4", rowGap: "1", color: "fg.muted" });
const headline = css({ mt: "3", textStyle: "lg", color: "fg" });
const subline = css({ mt: "0.5", fontSize: "sm", color: "fg.muted" });
const counts = css({
  mt: "4",
  display: "grid",
  gridTemplateColumns: { base: "repeat(2, minmax(0, 1fr))", sm: "repeat(3, minmax(0, 1fr))", md: "repeat(5, minmax(0, 1fr))" },
  gap: "2",
});
const tile = css(panelStyle, { px: "3", py: "2" });
const valueClass = css({ textStyle: "2xl", fontWeight: "semibold", fontVariantNumeric: "tabular-nums" });

// 件数の色。0 件のときは強調しない（全部 passed のときに赤や黄色が目に入らないように）
const TONES = {
  total: css({ color: "fg" }),
  passed: css({ color: "fg.success" }),
  failed: css({ color: "fg.error" }),
  error: css({ color: "fg.warning" }),
  skipped: css({ color: "fg.muted" }),
  zero: css({ color: "fg.muted" }),
};

/** "2 tests × 6 versions: 14 passed, 2 failed, 2 skipped" の本文 */
function headlineText(manifest: ManifestV2): string {
  const tests = manifest.tests.length;
  const versions = manifest.runs.length;
  const counts = manifest.summary?.tests;
  const parts = (["passed", "failed", "error", "skipped"] as const)
    .filter((key) => (counts?.[key] ?? 0) > 0)
    .map((key) => `${counts?.[key] ?? 0} ${key}`);
  const shape = `${tests} ${tests === 1 ? "test" : "tests"} × ${versions} ${versions === 1 ? "version" : "versions"}`;
  return parts.length > 0 ? `${shape}: ${parts.join(", ")}` : shape;
}

/** 副行: run（バージョン）単位の件数 */
function runsText(manifest: ManifestV2): string {
  const runs = manifest.summary?.runs;
  if (!runs) return "";
  const parts = (["passed", "failed", "error"] as const)
    .filter((key) => (runs[key] ?? 0) > 0)
    .map((key) => `${runs[key]} ${key}`);
  return `${runs.total ?? manifest.runs.length} ${runs.total === 1 ? "version run" : "version runs"}${parts.length > 0 ? ` (${parts.join(", ")})` : ""}`;
}

interface SummaryHeaderProps {
  manifest: ManifestV2;
}

/** 一覧ページの見出し。タイトル、テスト × バージョンの集計、CI へのリンクを並べる */
export function SummaryHeader({ manifest }: SummaryHeaderProps) {
  const { summary, ci } = manifest;
  const tests = summary?.tests;
  const commit = commitUrl(ci);
  return (
    <header>
      <h1 className={pageTitle}>{manifest.title}</h1>
      <div className={meta}>
        {ci?.repository && <span>{ci.repository}</span>}
        {ci?.sha && (commit ? <a href={commit}>{shortHash(ci.sha)}</a> : <code>{shortHash(ci.sha)}</code>)}
        {ci?.runUrl && <a href={ci.runUrl}>CI run{ci.runId ? ` #${ci.runId}` : ""}</a>}
        <span>Generated {formatDateTime(manifest.generatedAt)}</span>
      </div>
      <p className={headline}>{headlineText(manifest)}</p>
      <p className={subline}>{runsText(manifest)}</p>
      <dl className={counts}>
        <Count label="Test runs" value={tests?.total} tone="total" />
        <Count label="Passed" value={tests?.passed} tone="passed" />
        <Count label="Failed" value={tests?.failed} tone="failed" />
        <Count label="Error" value={tests?.error} tone="error" />
        <Count label="Skipped" value={tests?.skipped} tone="skipped" />
      </dl>
    </header>
  );
}

interface CountProps {
  label: string;
  value: number | undefined;
  tone: keyof typeof TONES;
}

/** 集計値1つ分のタイル */
function Count({ label, value, tone }: CountProps) {
  const count = value ?? 0;
  return (
    <div className={tile}>
      <dt className={eyebrow}>{label}</dt>
      <dd className={cx(valueClass, count === 0 && tone !== "total" ? TONES.zero : TONES[tone])}>{count}</dd>
    </div>
  );
}
