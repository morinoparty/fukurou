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
}

/**
 * テストページの1マス（1バージョン × 1プレイヤー）。
 * そのプレイヤーの最後のスクリーンショット（failure があればそれ）を出す。全部はこのテストの test-run ページで見る
 */
export function PlayerCell({ run, test, player, joined }: PlayerCellProps) {
  const shots = screenshotsOf(test, player);
  const last = shots[shots.length - 1];
  const inTest = test.players.some((candidate) => candidate.name === player);
  return (
    <div className={css({ minWidth: "0" })}>
      {/* 狭い画面では列見出しが無いので、マスの中にプレイヤー名を出す */}
      <p className={css({ mb: "1", fontSize: "xs", fontWeight: "semibold", color: "fg.muted", md: { display: "none" } })}>
        {player}
      </p>
      {last ? (
        <ScreenshotThumb
          run={run}
          testId={test.id}
          shot={last}
          caption={shots.length > 1 ? `${last.name} (+${shots.length - 1} more)` : last.name}
        />
      ) : (
        <p className={emptyBox}>{emptyReason(test, inTest, joined)}</p>
      )}
    </div>
  );
}

/** スクリーンショットが無い理由を、分かる範囲で短く書く */
function emptyReason(test: TestResult, inTest: boolean, joined: boolean | undefined): string {
  if (test.status === "skipped") return test.skipReason ? `Skipped: ${test.skipReason}` : "Skipped";
  if (!inTest) return "Not in this test";
  if (joined === false) return "Did not join";
  return "No screenshots";
}
