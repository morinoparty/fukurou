import { css } from "styled-system/css";
import type { ManifestRun, ResultV1 } from "../../contract";
import { screenshotsOf } from "../../lib/runs";
import { emptyBox } from "../../styles";
import { ScreenshotThumb } from "../ScreenshotThumb";

// そのマスのスクリーンショット。狭い画面と lg 以上では2列、md（列が細い）では1列
const shotsGrid = css({
  display: "grid",
  gap: "2",
  gridTemplateColumns: { base: "repeat(2, minmax(0, 1fr))", md: "minmax(0, 1fr)", lg: "repeat(2, minmax(0, 1fr))" },
});

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
    <div className={css({ minWidth: "0" })}>
      {/* 狭い画面では列見出しが無いので、マスの中にプレイヤー名を出す */}
      <p className={css({ mb: "1", fontSize: "xs", fontWeight: "semibold", color: "fg.muted", md: { display: "none" } })}>
        {player}
      </p>
      {shots.length > 0 ? (
        <div className={shotsGrid}>
          {shots.map((shot) => (
            <ScreenshotThumb key={`${shot.name}:${shot.path}`} run={run} shot={shot} />
          ))}
        </div>
      ) : (
        <p className={emptyBox}>{emptyReason(info)}</p>
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
