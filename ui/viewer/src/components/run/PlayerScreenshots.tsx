import type { ManifestRun, ResultV1 } from "../../contract";
import { playersOf, screenshotsOf } from "../../lib/runs";
import { ScreenshotThumb } from "../ScreenshotThumb";

interface PlayerScreenshotsProps {
  run: ManifestRun;
  result: ResultV1;
}

/** プレイヤーごとにスクリーンショットを撮影順で並べる */
export function PlayerScreenshots({ run, result }: PlayerScreenshotsProps) {
  const players = playersOf(result);
  if (players.length === 0) return <p className="text-sm text-zinc-500">No players were configured.</p>;

  return (
    <div className="space-y-6">
      {players.map((player) => {
        const info = result.players.find((candidate) => candidate.name === player);
        const shots = screenshotsOf(result, player);
        return (
          <div key={player}>
            <h3 className="mb-2 flex flex-wrap items-center gap-2 font-medium">
              {player}
              {info && (
                <span className="text-xs font-normal text-zinc-500">
                  {info.op ? "op" : "not op"} · {info.joined ? "joined" : "did not join"}
                </span>
              )}
            </h3>
            {shots.length > 0 ? (
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {shots.map((shot) => (
                  <ScreenshotThumb key={`${shot.name}:${shot.path}`} run={run} shot={shot} />
                ))}
              </div>
            ) : (
              <p className="text-sm text-zinc-500">No screenshots.</p>
            )}
          </div>
        );
      })}
    </div>
  );
}
