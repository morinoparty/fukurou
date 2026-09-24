import type { ManifestV1 } from "../../contract";
import { commitUrl } from "../../lib/ci";
import { formatDateTime, shortHash } from "../../lib/format";

interface SummaryHeaderProps {
  manifest: ManifestV1;
}

/** 一覧ページの見出し。タイトル、件数の集計、CI へのリンクを並べる */
export function SummaryHeader({ manifest }: SummaryHeaderProps) {
  const { summary, ci } = manifest;
  const commit = commitUrl(ci);
  return (
    <header>
      <h1 className="text-2xl font-bold tracking-tight">{manifest.title}</h1>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-sm text-zinc-600 dark:text-zinc-400">
        {ci?.repository && <span>{ci.repository}</span>}
        {ci?.sha && (commit ? <a href={commit}>{shortHash(ci.sha)}</a> : <code>{shortHash(ci.sha)}</code>)}
        {ci?.runUrl && <a href={ci.runUrl}>CI run{ci.runId ? ` #${ci.runId}` : ""}</a>}
        <span>Generated {formatDateTime(manifest.generatedAt)}</span>
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Count label="Total" value={summary?.total} tone="text-zinc-900 dark:text-zinc-100" />
        <Count label="Passed" value={summary?.passed} tone="text-emerald-700 dark:text-emerald-400" />
        <Count label="Failed" value={summary?.failed} tone="text-red-700 dark:text-red-400" />
        <Count label="Error" value={summary?.error} tone="text-amber-700 dark:text-amber-400" />
      </dl>
    </header>
  );
}

interface CountProps {
  label: string;
  value: number | undefined;
  tone: string;
}

/** 集計値1つ分のタイル */
function Count({ label, value, tone }: CountProps) {
  return (
    <div className="rounded-lg border border-zinc-200 bg-white px-3 py-2 dark:border-zinc-800 dark:bg-zinc-900">
      <dt className="text-xs uppercase tracking-wide text-zinc-500">{label}</dt>
      <dd className={`text-2xl font-semibold tabular-nums ${tone}`}>{value ?? 0}</dd>
    </div>
  );
}
