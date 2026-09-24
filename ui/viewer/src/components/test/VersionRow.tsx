import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import type { ManifestRun } from "../../contract";
import { formatDuration } from "../../lib/format";
import { findTest, runBadgeStatus, supportedResult } from "../../lib/runs";
import { emptyBoxStyle, panelStyle } from "../../styles";
import { ChannelBadge } from "../ChannelBadge";
import { Hint } from "../Hint";
import { StatusBadge } from "../StatusBadge";
import { runGridStyle } from "../overview/grid";
import { PlayerCell } from "./PlayerCell";

const row = css(panelStyle, runGridStyle, { p: "3" });
const version = css({
  textStyle: "lg",
  fontWeight: "bold",
  color: "colorPalette.fg",
  textDecoration: "none",
  _hover: { textDecoration: "underline" },
});
const failure = css({
  mt: "2",
  fontSize: "xs",
  color: "fg.error",
  lineClamp: "3",
  wordBreak: "break-word",
  cursor: "help",
  borderRadius: "item",
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "border.error" },
});
const note = css({ mt: "2", fontSize: "xs", color: "fg.muted" });
// このバージョンでテストが走らなかった行は、プレイヤー列を 1 つにまとめて理由だけ出す
const emptyRow = css(emptyBoxStyle, { md: { gridColumn: "2 / -1" } });

interface VersionRowProps {
  run: ManifestRun;
  testId: string;
  players: string[];
}

/** テストページの1行（1バージョン）。左にバージョンとそのテストの状態、右にプレイヤーごとの最後のスクリーンショット */
export function VersionRow({ run, testId, players }: VersionRowProps) {
  const result = supportedResult(run);
  const test = result ? findTest(result, testId) : undefined;

  return (
    <div className={row}>
      <div className={css({ minWidth: "0" })}>
        {test ? (
          <Link to="/runs/$runId/tests/$testId" params={{ runId: run.id, testId }} className={version}>
            {result?.minecraft.version ?? run.id}
          </Link>
        ) : (
          <Link to="/runs/$runId" params={{ runId: run.id }} className={version}>
            {result?.minecraft.version ?? run.id}
          </Link>
        )}
        <div className={css({ mt: "1", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" })}>
          <StatusBadge status={test ? test.status : result ? "not-run" : runBadgeStatus(run)} />
          <ChannelBadge channel={result?.minecraft.channel} />
          {test?.durationMs !== null && test?.durationMs !== undefined && (
            <span className={css({ fontSize: "xs", color: "fg.muted" })}>{formatDuration(test.durationMs)}</span>
          )}
        </div>
        {result && (
          <p className={css({ mt: "1", fontSize: "xs", color: "fg.muted" })}>
            {result.minecraft.server}
            {result.minecraft.build !== null && ` #${result.minecraft.build}`}
            {result.java.server !== null && ` · Java ${result.java.server}`}
          </p>
        )}
        {test?.failure && (
          // 3行で切った失敗メッセージの全文はツールチップで見せる
          <Hint content={`${test.failure.phase}: ${test.failure.message}`}>
            <p className={failure} tabIndex={0}>
              {test.failure.phase}: {test.failure.message}
            </p>
          </Hint>
        )}
        {test?.status === "skipped" && test.skipReason && <p className={note}>{test.skipReason}</p>}
      </div>
      {test && test.status !== "skipped" ? (
        players.map((player) => (
          <PlayerCell
            key={player}
            run={run}
            test={test}
            player={player}
            joined={result?.players.find((candidate) => candidate.name === player)?.joined}
          />
        ))
      ) : (
        <p className={emptyRow}>{emptyRowReason(run, test !== undefined)}</p>
      )}
    </div>
  );
}

/** プレイヤー列に何も出せない理由 */
function emptyRowReason(run: ManifestRun, hasTest: boolean): string {
  if (hasTest) return "This test was skipped in this version";
  if (!supportedResult(run)) return "Result unavailable for this version";
  return "This test was not run in this version";
}
