import type { ReactNode } from "react";
import { css } from "styled-system/css";
import { panelStyle } from "../styles";

const box = css(panelStyle, { mx: "auto", mt: "16", maxWidth: "xl", p: "6", textAlign: "center" });

interface MessageProps {
  title: string;
  children?: ReactNode;
}

/** manifest が無い、run が見つからないなど、ページ全体で伝えたいメッセージ */
export function Message({ title, children }: MessageProps) {
  return (
    <div className={box}>
      <h1 className={css({ textStyle: "xl", fontWeight: "semibold", color: "colorPalette.fg" })}>{title}</h1>
      {children && (
        <div className={css({ mt: "3", display: "flex", flexDirection: "column", gap: "2", color: "fg.muted" })}>{children}</div>
      )}
    </div>
  );
}
