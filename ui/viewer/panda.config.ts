import { createPreset, stone } from "@morinoparty/chlorophyll-react/preset";
import { defineConfig } from "@pandacss/dev";

// このビューアで使う Chlorophyll のレシピ。Chlorophyll のレシピはすべて staticCss: ["*"] なので、
// 使わないもの（Drawer / Select / SkinViewer など）もそのままでは全バリアントの CSS が出力されてバンドルが太る
const USED_RECIPES = ["badge", "button", "separator", "skeleton", "spinner"];
const USED_SLOT_RECIPES = ["breadcrumb", "modalDialog", "table", "tooltip"];

export default defineConfig({
  preflight: true,
  // 生成物のクラス名・CSS 変数を Chlorophyll 自身（mpc）や他のスタイルと衝突させない
  prefix: "fk",
  presets: ["@pandacss/preset-base", createPreset({ brandColor: "mori", grayColor: stone, radius: "md" })],
  include: [
    "./src/**/*.{ts,tsx}",
    // Chlorophyll のコンポーネントは css() / recipe をソースのまま持つので、使うものだけ静的解析の対象にする。
    // （レシピ自体は staticCss: ["*"] で常に生成される）
    "./node_modules/@morinoparty/chlorophyll-react/src/components/{badge,breadcrumb,button,modal-dialog,separator,skeleton,spinner,styled,table,tooltip}/**/*.tsx",
    "./node_modules/@morinoparty/chlorophyll-react/src/components/button.tsx",
  ],
  exclude: [],
  jsxFramework: "react",
  outdir: "styled-system",
  hooks: {
    // プリセットを合成した後の設定から、使わないレシピと umi パレットのトークンを取り除く
    "config:resolved": ({ config, utils }) => {
      const unused = [
        "theme.tokens.colors.umi",
        "theme.semanticTokens.colors.umi",
        ...Object.keys(config.theme?.recipes ?? {})
          .filter((name) => !USED_RECIPES.includes(name))
          .map((name) => `theme.recipes.${name}`),
        ...Object.keys(config.theme?.slotRecipes ?? {})
          .filter((name) => !USED_SLOT_RECIPES.includes(name))
          .map((name) => `theme.slotRecipes.${name}`),
      ];
      return utils.omit(config, unused) as typeof config;
    },
  },
  globalCss: {
    html: {
      // Portal で body 直下に出る Tooltip なども mori パレットで描かれるよう、文書全体の既定にする
      colorPalette: "mori",
      // Chlorophyll はライトテーマのみなので、OS がダークでもライトで表示する
      colorScheme: "light",
    },
    body: {
      bg: "colorPalette.bg",
      color: "fg",
      textStyle: "sm",
      WebkitFontSmoothing: "antialiased",
    },
    a: {
      color: "colorPalette.fg",
      textDecoration: "underline",
      textDecorationColor: "colorPalette.border.emphasized",
      textUnderlineOffset: "2px",
      _hover: { textDecorationColor: "currentColor" },
    },
    "code, pre, kbd": {
      fontFamily: "mono",
      fontSize: "0.9em",
    },
  },
});
