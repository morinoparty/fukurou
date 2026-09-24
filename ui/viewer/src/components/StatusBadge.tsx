import type { RunStatus, StepStatus } from "../contract";

/** バッジで表せる状態。unsupported は表示できない run 用 */
export type BadgeStatus = RunStatus | StepStatus | "unsupported";

/** 状態ごとの配色。色だけに頼らないよう、文字でも状態を書く */
const STYLES: Record<BadgeStatus, string> = {
  passed:
    "bg-emerald-100 text-emerald-800 ring-emerald-600/30 dark:bg-emerald-500/15 dark:text-emerald-300 dark:ring-emerald-400/30",
  failed: "bg-red-100 text-red-800 ring-red-600/30 dark:bg-red-500/15 dark:text-red-300 dark:ring-red-400/30",
  error: "bg-amber-100 text-amber-900 ring-amber-600/30 dark:bg-amber-500/15 dark:text-amber-300 dark:ring-amber-400/30",
  skipped: "bg-zinc-100 text-zinc-600 ring-zinc-500/20 dark:bg-zinc-500/15 dark:text-zinc-400 dark:ring-zinc-400/20",
  unsupported: "bg-zinc-100 text-zinc-700 ring-zinc-500/30 dark:bg-zinc-500/15 dark:text-zinc-300 dark:ring-zinc-400/30",
};

interface StatusBadgeProps {
  status: BadgeStatus;
}

/** passed / failed / error などの状態を示す小さなラベル */
export function StatusBadge({ status }: StatusBadgeProps) {
  // 未知の状態が来ても落とさず、unsupported と同じ見た目で文字だけ出す
  const style = STYLES[status] ?? STYLES.unsupported;
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${style}`}>
      {status}
    </span>
  );
}
