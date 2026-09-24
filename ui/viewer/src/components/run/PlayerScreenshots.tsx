import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { ManifestRun, ResultV2, TestResult } from "../../contract";
import { findLogIndex } from "../../lib/logs";
import { flatLogs, playersOf, screenshotsOf } from "../../lib/runs";
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
  result: ResultV2;
  test: TestResult;
}

/**
 * 1 テストのスクリーンショットをプレイヤーごとに撮影順で並べ（failure は赤枠で最後）、
 * そのプレイヤーのクライアントログ（このテストの行範囲付き）へのリンクを添える
 */
export function PlayerScreenshots({ run, result, test }: PlayerScreenshotsProps) {
  const players = playersOf(test);
  const logs = flatLogs(result);
  if (players.length === 0) return <p className={css({ color: "fg.muted" })}>No players were configured for this test.</p>;

  return (
    <div className={css({ display: "flex", flexDirection: "column", gap: "6" })}>
      {players.map((player) => {
        const info = test.players.find((candidate) => candidate.name === player);
        const joined = result.players.find((candidate) => candidate.name === player)?.joined;
        const shots = screenshotsOf(test, player);
        // このテストのクライアントログ。logRanges にそのプレイヤーのログ（再起動後の <player>.2.log も含む）があればそれとその範囲、
        // 無ければこのテストのセッションの最初のクライアントログ
        const fromRanges = Object.entries(test.logRanges ?? {})
          .map(([path, range]) => ({ index: logs.findIndex((log) => log.path === path), range }))
          .find((entry) => entry.index >= 0 && logs[entry.index]?.kind === "client" && logs[entry.index]?.player === player);
        const clientLog = fromRanges?.index ?? findLogIndex(logs, "client", player, test.session ?? undefined);
        const range = fromRanges?.range;
        return (
          <div key={player}>
            <h3 className={heading}>
              {player}
              <span className={css({ fontSize: "xs", fontWeight: "normal", color: "fg.muted" })}>
                {info ? (info.op ? "op" : "not op") : "not in this test"}
                {joined === false && " · did not join"}
              </span>
              {clientLog >= 0 && (
                <Button asChild size="sm" intent="plain" className={buttonLink}>
                  <Link
                    to="/runs/$runId/logs/$logIndex"
                    params={{ runId: run.id, logIndex: String(clientLog) }}
                    search={range ? { from: range.from, to: range.to } : {}}
                  >
                    Client log{range ? ` (lines ${range.from}–${range.to})` : ""}
                  </Link>
                </Button>
              )}
            </h3>
            {shots.length > 0 ? (
              <div className={grid}>
                {shots.map((shot) => (
                  <ScreenshotThumb key={`${shot.name}:${shot.path}`} run={run} testId={test.id} shot={shot} />
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
