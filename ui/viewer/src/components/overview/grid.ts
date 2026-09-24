import { css } from "styled-system/css";

// 一覧の行と列見出しで共有するグリッド。
// 列数は CSS 変数 --players で渡し、md 以上でだけ横に並べる（狭い画面では縦積み）
export const runGridStyle = css.raw({
  display: "grid",
  gap: "4",
  md: { gridTemplateColumns: "11rem repeat(var(--players), minmax(0, 1fr))" },
});

export const runGrid = css(runGridStyle);
