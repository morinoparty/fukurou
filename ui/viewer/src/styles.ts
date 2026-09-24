import { css } from "styled-system/css";

// 複数の画面で使う見た目をここにまとめる。
// Panda は css() / css.raw() に渡したリテラルを静的解析して CSS を作るので、値は必ずここに直接書く（変数を渡さない）。
//
// Panda のクラスは1プロパティ1クラスのアトミック CSS なので、同じプロパティを持つクラスを cx() で並べても
// どちらが勝つかはクラスの順序ではなく CSS 内の順序で決まってしまう。上書きしたいときは
// *Style（css.raw）を css(base, override) に渡して、プロパティ単位でマージしてから使う

/** 白いパネル（カード）。Chlorophyll の Table と同じ面・枠線・角丸に揃える */
export const panelStyle = css.raw({
  bg: "bg.panel",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.subtle",
  borderRadius: "panel",
});

/** 補足の小さな文字 */
export const mutedTextStyle = css.raw({ color: "fg.muted", fontSize: "xs" });

/** 見出し（ページ） */
export const pageTitleStyle = css.raw({ textStyle: "2xl", fontWeight: "bold", letterSpacing: "tight", color: "colorPalette.fg" });

/** 見出し（セクション） */
export const sectionTitleStyle = css.raw({ textStyle: "lg", fontWeight: "semibold", color: "colorPalette.fg" });

/** 見出し行の小さなラベル（定義リストの dt、列見出しなど） */
export const eyebrowStyle = css.raw({
  fontSize: "xs",
  fontWeight: "semibold",
  textTransform: "uppercase",
  letterSpacing: "wide",
  color: "fg.muted",
});

/** Button を asChild で <a> / Link にしたときに、グローバルのリンク下線を消す */
export const buttonLinkStyle = css.raw({ textDecoration: "none", _hover: { textDecoration: "none" } });

/** 等幅・折り返しありのコード片（エラーメッセージなど） */
export const codeBlockStyle = css.raw({
  fontFamily: "mono",
  fontSize: "xs",
  whiteSpace: "pre-wrap",
  wordBreak: "break-word",
  overflowX: "auto",
});

/** 空の状態（スクリーンショットが無いマスなど）の点線枠 */
export const emptyBoxStyle = css.raw({
  borderWidth: "1px",
  borderStyle: "dashed",
  borderColor: "border",
  borderRadius: "control",
  color: "fg.muted",
  fontSize: "xs",
  textAlign: "center",
  p: "3",
});

export const panel = css(panelStyle);
export const mutedText = css(mutedTextStyle);
export const pageTitle = css(pageTitleStyle);
export const sectionTitle = css(sectionTitleStyle);
export const eyebrow = css(eyebrowStyle);
export const buttonLink = css(buttonLinkStyle);
export const codeBlock = css(codeBlockStyle);
export const emptyBox = css(emptyBoxStyle);
