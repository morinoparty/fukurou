import { css } from "styled-system/css";
import type { ManifestRun } from "../contract";
import { runBadgeStatus, unsupportedReason } from "../lib/runs";
import { panelStyle } from "../styles";

// パネルの枠線を点線にして、通常の run と見た目で区別する
const card = css(panelStyle, { p: "4", borderStyle: "dashed", borderColor: "border" });
import { StatusBadge } from "./StatusBadge";

interface UnsupportedRunCardProps {
  run: ManifestRun;
}

/** result が無い、または未対応の schemaVersion の run を、ページを壊さずに知らせるカード */
export function UnsupportedRunCard({ run }: UnsupportedRunCardProps) {
  return (
    <div className={card}>
      <div className={css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" })}>
        <span className={css({ fontWeight: "semibold", color: "colorPalette.fg" })}>{run.id}</span>
        <StatusBadge status={runBadgeStatus(run)} />
        <span className={css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" })}>{run.artifact}</span>
      </div>
      <p className={css({ mt: "2", color: "fg.muted" })}>{unsupportedReason(run)}</p>
    </div>
  );
}
