import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { ManifestRun, ResultV1 } from "../../contract";
import { findLogIndex } from "../../lib/logs";
import { playersOf, screenshotsOf } from "../../lib/runs";
import { buttonLink } from "../../styles";
import { ScreenshotThumb } from "../ScreenshotThumb";

const heading = css({ mb: "2", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2", fontWeight: "semibold" });
const grid = css({
  display: "grid",
  gap: "3",
  gridTemplateColumns: { base: "minmax(0, 1fr)", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(3, minmax(0, 1fr))" },
});

interface PlayerScreenshotsProps {
  run: ManifestRun;
  result: ResultV1;
}

/** プレイヤーごとにスクリーンショットを撮影順で並べ、そのプレイヤーのクライアントログへのリンクを添える */
export function PlayerScreenshots({ run, result }: PlayerScreenshotsProps) {
  const players = playersOf(result);
  if (players.length === 0) return <p className={css({ color: "fg.muted" })}>No players were configured.</p>;

  return (
    <div className={css({ display: "flex", flexDirection: "column", gap: "6" })}>
      {players.map((player) => {
        const info = result.players.find((candidate) => candidate.name === player);
        const shots = screenshotsOf(result, player);
        const clientLog = findLogIndex(result.logs, "client", player);
        return (
          <div key={player}>
            <h3 className={heading}>
              {player}
              {info && (
                <span className={css({ fontSize: "xs", fontWeight: "normal", color: "fg.muted" })}>
                  {info.op ? "op" : "not op"} · {info.joined ? "joined" : "did not join"}
                </span>
              )}
              {clientLog >= 0 && (
                <Button asChild size="sm" intent="plain" className={buttonLink}>
                  <Link to="/runs/$id/logs/$logIndex" params={{ id: run.id, logIndex: String(clientLog) }}>
                    Client log
                  </Link>
                </Button>
              )}
            </h3>
            {shots.length > 0 ? (
              <div className={grid}>
                {shots.map((shot) => (
                  <ScreenshotThumb key={`${shot.name}:${shot.path}`} run={run} shot={shot} />
                ))}
              </div>
            ) : (
              <p className={css({ color: "fg.muted" })}>No screenshots.</p>
            )}
          </div>
        );
      })}
    </div>
  );
}
