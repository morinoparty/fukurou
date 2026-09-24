import { css, cx } from "styled-system/css";
import type { ManifestV1 } from "../../contract";
import { commitUrl } from "../../lib/ci";
import { formatDateTime, shortHash } from "../../lib/format";
import { eyebrow, pageTitle, panelStyle } from "../../styles";

const meta = css({ mt: "1", display: "flex", flexWrap: "wrap", columnGap: "4", rowGap: "1", color: "fg.muted" });
const counts = css({
  mt: "4",
  display: "grid",
  gridTemplateColumns: { base: "repeat(2, minmax(0, 1fr))", sm: "repeat(4, minmax(0, 1fr))" },
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
  zero: css({ color: "fg.muted" }),
};

interface SummaryHeaderProps {
  manifest: ManifestV1;
}

/** 一覧ページの見出し。タイトル、件数の集計、CI へのリンクを並べる */
export function SummaryHeader({ manifest }: SummaryHeaderProps) {
  const { summary, ci } = manifest;
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
      <dl className={counts}>
        <Count label="Total" value={summary?.total} tone="total" />
        <Count label="Passed" value={summary?.passed} tone="passed" />
        <Count label="Failed" value={summary?.failed} tone="failed" />
        <Count label="Error" value={summary?.error} tone="error" />
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
