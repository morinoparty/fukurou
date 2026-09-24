import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { classicScript } from "./build-plugins/classicScript.ts";

/** この設定ファイルからの相対パスを絶対パスにする（@types/node を入れずに済むよう URL で解決する） */
const fromHere = (path: string) => decodeURIComponent(new URL(path, import.meta.url).pathname);

// ビルド結果は ui/dist にコミットし、build_manifest.py がそのままサイトへコピーする。
// サブパス配信と file:// の両方で動かすため、アセットは相対パス（base: "./"）で参照する。
export default defineConfig(({ command }) => ({
  base: "./",
  plugins: [react(), classicScript()],
  resolve: {
    alias: {
      // Chlorophyll のコンポーネントは "styled-system/css" などを素の指定子で import する。
      // panda codegen が生成したこのプロジェクトの styled-system に向ける
      "styled-system": fromHere("./styled-system"),
      // Chlorophyll のコンポーネントを1つずつ読むための別名（理由は src/chlorophyll.ts）
      "chlorophyll-components": fromHere("./node_modules/@morinoparty/chlorophyll-react/src/components"),
    },
  },
  // dev サーバーでだけ dev/ のサンプル manifest.js と画像を配信する。ビルド成果物には含めない
  publicDir: command === "serve" ? "dev" : false,
  build: {
    outDir: "../dist",
    // outDir が viewer の外にあるため、明示しないと Vite は古いファイルを消さない
    emptyOutDir: true,
    // file:// では modulepreload も使えないので、ポリフィルごと無効にする
    modulePreload: false,
    // file:// のために1ファイルにまとめているので、分割を勧める警告は出さない（サイズは README に記録している）
    chunkSizeWarningLimit: 700,
    rolldownOptions: {
      output: {
        // 1ファイルの IIFE にして、クラシックスクリプトとして読めるようにする
        format: "iife",
        codeSplitting: false,
      },
    },
  },
}));
