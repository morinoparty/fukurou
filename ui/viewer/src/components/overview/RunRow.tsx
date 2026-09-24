import { Link } from "@tanstack/react-router";
import type { ManifestRun } from "../../contract";
import { formatDuration } from "../../lib/format";
import { supportedResult } from "../../lib/runs";
import { StatusBadge } from "../StatusBadge";
import { UnsupportedRunCard } from "../UnsupportedRunCard";
import { PlayerCell } from "./PlayerCell";

interface RunRowProps {
  run: ManifestRun;
  players: string[];
}

/** 一覧の1行（1バージョン）。左に run の情報、右にプレイヤーごとのスクリーンショット */
export function RunRow({ run, players }: RunRowProps) {
  const result = supportedResult(run);
  if (!result) return <UnsupportedRunCard run={run} />;

  return (
    <div className="grid gap-4 rounded-lg border border-zinc-200 bg-white p-3 md:grid-cols-[11rem_repeat(var(--players),minmax(0,1fr))] dark:border-zinc-800 dark:bg-zinc-900">
      <div className="min-w-0">
        <Link to="/runs/$id" params={{ id: run.id }} className="text-lg font-semibold">
          {result.minecraft.version}
        </Link>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <StatusBadge status={result.status} />
          <span className="text-xs text-zinc-500">{formatDuration(result.durationMs)}</span>
        </div>
        <p className="mt-1 text-xs text-zinc-500">
          {result.minecraft.server}
          {result.minecraft.build !== null && ` #${result.minecraft.build}`}
          {result.java.server !== null && ` · Java ${result.java.server}`}
        </p>
        {result.failure && (
          <p className="mt-2 line-clamp-3 text-xs text-red-700 dark:text-red-400" title={result.failure.message}>
            {result.failure.phase}: {result.failure.message}
          </p>
        )}
      </div>
      {players.map((player) => (
        <PlayerCell key={player} run={run} result={result} player={player} />
      ))}
    </div>
  );
}
