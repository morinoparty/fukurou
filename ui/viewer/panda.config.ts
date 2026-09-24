import { createPreset, stone } from "@morinoparty/chlorophyll-react/preset";
import { defineConfig, defineSemanticTokens } from "@pandacss/dev";

// Chlorophyll のパレット。セマンティックトークン（bg / fg / border / colorPalette.*）はすべてこの 12 段スケールを参照する
// umi（海）パレットはこのビューアでは使わないので、トークンごと外す（下の config:resolved フック）
const PALETTES = ["gray", "mori", "red", "yellow", "blue"] as const;
const STEPS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] as const;

/**
 * Chlorophyll 0.4.8 のセマンティックカラーはライト固定（"ダークモード非対応のため light ランプに固定"）。
 * リファレンストークンには dark ランプもあるので、各パレットの 1〜12 / a1〜a12 だけを
 * OS の配色設定（_osDark = prefers-color-scheme: dark）で dark ランプに切り替える。
 * bg・fg・border・colorPalette.* はこのスケールを参照しているため、まとめて暗色に追従する。
 */
function darkAwareScales() {
  const entries = PALETTES.map((name) => {
    const scale = Object.fromEntries(
      STEPS.flatMap((step) => [
        [step, { value: { base: `{colors.${name}.light.${step}}`, _osDark: `{colors.${name}.dark.${step}}` } }],
        [`a${step}`, { value: { base: `{colors.${name}.light.a${step}}`, _osDark: `{colors.${name}.dark.a${step}}` } }],
      ]),
    );
    return [name, scale];
  });
  return Object.fromEntries(entries);
}

// スケールを参照せず白や dark ランプを直接指しているトークンだけ、暗色時の値を個別に与える
const darkOverrides = defineSemanticTokens.colors({
  bg: {
    // 白いパネル（表・カード）。暗色ではページ地より一段明るい面にする
    panel: {
      DEFAULT: { value: { base: "{colors.white}", _osDark: "{colors.gray.dark.2}" } },
      hover: { value: { base: "{colors.gray.1}", _osDark: "{colors.gray.dark.3}" } },
    },
    // Tooltip などの反転面。暗色では明るい面に反転させる
    inverted: { value: { base: "{colors.gray.dark.1}", _osDark: "{colors.gray.light.1}" } },
  },
  fg: {
    inverted: { value: { base: "{colors.white}", _osDark: "{colors.gray.light.12}" } },
  },
  overlay: {
    // 暗い画面の上ではモーダルの暗幕を濃くしないと背景と区別できない
    DEFAULT: { value: { base: "rgba(0, 0, 0, 0.4)", _osDark: "rgba(0, 0, 0, 0.7)" } },
  },
});

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
  theme: {
    extend: {
      semanticTokens: {
        colors: { ...darkAwareScales(), ...darkOverrides },
      },
    },
  },
  globalCss: {
    html: {
      // Portal で body 直下に出る Tooltip なども mori パレットで描かれるよう、文書全体の既定にする
      colorPalette: "mori",
      colorScheme: "light dark",
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
