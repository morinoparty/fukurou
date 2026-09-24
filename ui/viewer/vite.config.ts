import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { classicScript } from "./build-plugins/classicScript.ts";

// ビルド結果は ui/dist にコミットし、build_manifest.py がそのままサイトへコピーする。
// サブパス配信と file:// の両方で動かすため、アセットは相対パス（base: "./"）で参照する。
export default defineConfig(({ command }) => ({
  base: "./",
  plugins: [react(), tailwindcss(), classicScript()],
  // dev サーバーでだけ dev/ のサンプル manifest.js と画像を配信する。ビルド成果物には含めない
  publicDir: command === "serve" ? "dev" : false,
  build: {
    outDir: "../dist",
    // outDir が viewer の外にあるため、明示しないと Vite は古いファイルを消さない
    emptyOutDir: true,
    // file:// では modulepreload も使えないので、ポリフィルごと無効にする
    modulePreload: false,
    rolldownOptions: {
      output: {
        // 1ファイルの IIFE にして、クラシックスクリプトとして読めるようにする
        format: "iife",
        codeSplitting: false,
      },
    },
  },
}));
