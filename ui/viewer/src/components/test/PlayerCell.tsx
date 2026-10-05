import { css } from "styled-system/css";
import type { ManifestRun, TestResult } from "../../contract";
import { screenshotsOf } from "../../lib/runs";
import { emptyBox } from "../../styles";
import { ScreenshotThumb } from "../ScreenshotThumb";

interface PlayerCellProps {
  run: ManifestRun;
  test: TestResult;
  player: string;
  /** run に参加できたか。テストの players に無いプレイヤーは undefined */
  joined: boolean | undefined;
  /** 列見出しが無いレイアウト（run ごとに顔ぶれが違う）では、広い画面でもマスの中にプレイヤー名を出す */
  showName?: boolean;
}

const playerNameStyle = css.raw({ mb: "1", fontSize: "xs", fontWeight: "semibold", color: "fg.muted" });
const playerName = css(playerNameStyle);
const playerNameNarrow = css(playerNameStyle, { md: { display: "none" } });
// この run のテストに居ないプレイヤーは、枠を出さずに薄い 1 行だけにする（v3 の複数サーバーのテストではよくある）
const absent = css({ py: "1", fontSize: "xs", color: "fg.muted" });

/**
 * テストページの1マス（1バージョン × 1プレイヤー）。
 * そのプレイヤーの最後のスクリーンショット（failure があればそれ）を出す。全部はこのテストの test-run ページで見る
 */
export function PlayerCell({ run, test, player, joined, showName = false }: PlayerCellProps) {
  const shots = screenshotsOf(test, player);
  const last = shots[shots.length - 1];
  const inTest = test.players.some((candidate) => candidate.name === player);
  return (
    <div className={css({ minWidth: "0" })}>
      {/* 狭い画面（と、列見出しの無いレイアウト）ではマスの中にプレイヤー名を出す */}
      <p className={showName ? playerName : playerNameNarrow}>{player}</p>
      {!last && !inTest && test.status !== "skipped" ? (
        <p className={absent}>Not in this run</p>
      ) : last ? (
        <ScreenshotThumb
          run={run}
          testId={test.id}
          shot={last}
          caption={shots.length > 1 ? `${last.name} (+${shots.length - 1} more)` : last.name}
        />
      ) : (
        <p className={emptyBox}>{emptyReason(test, joined)}</p>
      )}
    </div>
  );
}

/** スクリーンショットが無い理由を、分かる範囲で短く書く */
function emptyReason(test: TestResult, joined: boolean | undefined): string {
  if (test.status === "skipped") return test.skipReason ? `Skipped: ${test.skipReason}` : "Skipped";
  if (joined === false) return "Did not join";
  return "No screenshots";
}
