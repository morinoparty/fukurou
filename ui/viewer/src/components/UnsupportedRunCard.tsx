import type { ManifestRun } from "../contract";
import { runBadgeStatus, unsupportedReason } from "../lib/runs";
import { StatusBadge } from "./StatusBadge";

interface UnsupportedRunCardProps {
  run: ManifestRun;
}

/** result が無い、または未対応の schemaVersion の run を、ページを壊さずに知らせるカード */
export function UnsupportedRunCard({ run }: UnsupportedRunCardProps) {
  return (
    <div className="rounded-lg border border-dashed border-zinc-300 bg-white p-4 dark:border-zinc-700 dark:bg-zinc-900">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-semibold">{run.id}</span>
        <StatusBadge status={runBadgeStatus(run)} />
        <span className="text-xs text-zinc-500">{run.artifact}</span>
      </div>
      <p className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">{unsupportedReason(run)}</p>
    </div>
  );
}
