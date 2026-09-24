import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import { buttonLink, panelStyle } from "../../styles";
import type { LogTextState } from "./useLogText";

const box = css(panelStyle, { p: "4", display: "flex", flexDirection: "column", gap: "3", alignItems: "flex-start" });

interface LogUnavailableProps {
  state: Exclude<LogTextState, { kind: "loading" } | { kind: "loaded" }>;
  url: string;
  artifact: string;
}

/**
 * ログ本文を表示できないときの案内。
 * file:// で開いたページは Chromium がローカルファイルの fetch を拒否するので、エラーではなく
 * 「生のログを開く」リンクを出す（リンクで開くぶんには file:// でも読める）。
 */
export function LogUnavailable({ state, url, artifact }: LogUnavailableProps) {
  return (
    <div className={box} role="status">
      <p className={css({ fontWeight: "semibold", color: "colorPalette.fg" })}>{title(state)}</p>
      <p className={css({ color: "fg.muted" })}>{detail(state, artifact)}</p>
      {state.kind !== "missing" && (
        <Button asChild size="sm" intent="primary" className={buttonLink}>
          <a href={url} target="_blank" rel="noreferrer">
            Open raw log
          </a>
        </Button>
      )}
    </div>
  );
}

/** 状態ごとの見出し */
function title(state: LogUnavailableProps["state"]): string {
  switch (state.kind) {
    case "blocked":
      return "This browser does not let a page opened from a file read other local files";
    case "missing":
      return `This log is not part of the site (HTTP ${state.status})`;
    case "failed":
      return "The log could not be loaded";
  }
}

/** 状態ごとの説明 */
function detail(state: LogUnavailableProps["state"], artifact: string): string {
  switch (state.kind) {
    case "blocked":
      return "Open the raw log in a new tab instead, or serve the report over HTTP (for example python3 -m http.server) to use the log viewer.";
    case "missing":
      return `The site was probably built without logs. Download the ${artifact} artifact to read it.`;
    case "failed":
      return state.message;
  }
}
