import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import type { ManifestRun } from "../../contract";
import { formatDuration } from "../../lib/format";
import { supportedResult } from "../../lib/runs";
import { panelStyle } from "../../styles";
import { Hint } from "../Hint";
import { StatusBadge } from "../StatusBadge";
import { UnsupportedRunCard } from "../UnsupportedRunCard";
import { runGridStyle } from "./grid";
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

interface RunRowProps {
  run: ManifestRun;
  players: string[];
}

/** 一覧の1行（1バージョン）。左に run の情報、右にプレイヤーごとのスクリーンショット */
export function RunRow({ run, players }: RunRowProps) {
  const result = supportedResult(run);
  if (!result) return <UnsupportedRunCard run={run} />;

  return (
    <div className={row}>
      <div className={css({ minWidth: "0" })}>
        <Link to="/runs/$id" params={{ id: run.id }} className={version}>
          {result.minecraft.version}
        </Link>
        <div className={css({ mt: "1", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" })}>
          <StatusBadge status={result.status} />
          <span className={css({ fontSize: "xs", color: "fg.muted" })}>{formatDuration(result.durationMs)}</span>
        </div>
        <p className={css({ mt: "1", fontSize: "xs", color: "fg.muted" })}>
          {result.minecraft.server}
          {result.minecraft.build !== null && ` #${result.minecraft.build}`}
          {result.java.server !== null && ` · Java ${result.java.server}`}
        </p>
        {result.failure && (
          // 3行で切った失敗メッセージの全文はツールチップで見せる
          <Hint content={`${result.failure.phase}: ${result.failure.message}`}>
            <p className={failure} tabIndex={0}>
              {result.failure.phase}: {result.failure.message}
            </p>
          </Hint>
        )}
      </div>
      {players.map((player) => (
        <PlayerCell key={player} run={run} result={result} player={player} />
      ))}
    </div>
  );
}
