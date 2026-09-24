import { Link, useParams } from "@tanstack/react-router";
import { ShotLinks } from "../components/overview/ShotLinks";
import { ScreenshotThumb } from "../components/ScreenshotThumb";
import { StatusBadge } from "../components/StatusBadge";
import type { ManifestRun } from "../contract";
import { findScreenshot, runBadgeStatus, supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";

/** #/compare/<shot> : 同じ名前のスクリーンショットを、プレイヤーごとに全バージョン横並びで比べる */
export function ComparePage() {
  const { shot } = useParams({ from: "/compare/$shot" });
  const manifest = useManifest();
  const players = manifest.players;

  return (
    <div className="space-y-6">
      <nav className="text-sm">
        <Link to="/">← All runs</Link>
      </nav>
      <h1 className="text-2xl font-bold tracking-tight">
        Compare <code className="text-[0.9em]">{shot}</code>
      </h1>
      <ShotLinks shots={manifest.shots} current={shot} />
      {players.length === 0 && <p className="text-sm text-zinc-500">This report has no players.</p>}
      {players.map((player) => (
        <section key={player}>
          <h2 className="mb-3 text-lg font-semibold">{player}</h2>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {manifest.runs.map((run) => (
              <CompareCell key={run.id} run={run} player={player} shot={shot} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

interface CompareCellProps {
  run: ManifestRun;
  player: string;
  shot: string;
}

/** 比較の1枚分。スクリーンショットが無い run も、欠けていることが分かるように枠だけ出す */
function CompareCell({ run, player, shot }: CompareCellProps) {
  const result = supportedResult(run);
  const screenshot = result ? findScreenshot(result, player, shot) : undefined;
  return (
    <div className="min-w-0 rounded-lg border border-zinc-200 bg-white p-2 dark:border-zinc-800 dark:bg-zinc-900">
      <div className="mb-2 flex items-center gap-2 text-sm">
        <Link to="/runs/$id" params={{ id: run.id }} className="font-medium">
          {result?.minecraft.version ?? run.id}
        </Link>
        <StatusBadge status={runBadgeStatus(run)} />
      </div>
      {screenshot ? (
        <ScreenshotThumb run={run} shot={screenshot} caption={`${player} · ${result?.minecraft.version ?? run.id}`} />
      ) : (
        <div className="flex aspect-video items-center justify-center rounded-md border border-dashed border-zinc-300 text-xs text-zinc-500 dark:border-zinc-700">
          {result ? "No screenshot" : "Result unavailable"}
        </div>
      )}
    </div>
  );
}
