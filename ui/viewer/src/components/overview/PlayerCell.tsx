import type { ManifestRun, ResultV1 } from "../../contract";
import { screenshotsOf } from "../../lib/runs";
import { ScreenshotThumb } from "../ScreenshotThumb";

interface PlayerCellProps {
  run: ManifestRun;
  result: ResultV1;
  player: string;
}

/** 一覧の1マス（1バージョン × 1プレイヤー）。そのプレイヤーのスクリーンショットを撮影順に並べる */
export function PlayerCell({ run, result, player }: PlayerCellProps) {
  const shots = screenshotsOf(result, player);
  const info = result.players.find((candidate) => candidate.name === player);
  return (
    <div className="min-w-0">
      {/* 狭い画面では列見出しが無いので、マスの中にプレイヤー名を出す */}
      <p className="mb-1 text-xs font-medium text-zinc-500 md:hidden">{player}</p>
      {shots.length > 0 ? (
        <div className="grid grid-cols-2 gap-2 md:grid-cols-1 lg:grid-cols-2">
          {shots.map((shot) => (
            <ScreenshotThumb key={`${shot.name}:${shot.path}`} run={run} shot={shot} />
          ))}
        </div>
      ) : (
        <p className="rounded-md border border-dashed border-zinc-300 p-3 text-center text-xs text-zinc-500 dark:border-zinc-700">
          {emptyReason(info)}
        </p>
      )}
    </div>
  );
}

/** スクリーンショットが無い理由を、分かる範囲で短く書く */
function emptyReason(info: ResultV1["players"][number] | undefined): string {
  if (!info) return "Not in this run";
  if (!info.joined) return "Did not join";
  return "No screenshots";
}
