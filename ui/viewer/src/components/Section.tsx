import type { ReactNode } from "react";
import { css } from "styled-system/css";
import { Separator } from "../chlorophyll";
import { sectionTitle } from "../styles";

interface SectionProps {
  title: string;
  /** 見出しの右側に並べる補足（件数やリンクなど） */
  aside?: ReactNode;
  children: ReactNode;
}

/** 見出し付きのまとまり。詳細ページの各ブロックで共通に使い、区切り線で前のブロックと分ける */
export function Section({ title, aside, children }: SectionProps) {
  return (
    <section className={css({ mt: "8" })}>
      <Separator className={css({ mb: "6" })} />
      <div
        className={css({
          mb: "3",
          display: "flex",
          flexWrap: "wrap",
          alignItems: "baseline",
          justifyContent: "space-between",
          gap: "2",
        })}
      >
        <h2 className={sectionTitle}>{title}</h2>
        {aside && <div className={css({ fontSize: "sm", color: "fg.muted" })}>{aside}</div>}
      </div>
      {children}
    </section>
  );
}
