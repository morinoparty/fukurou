import type { Plugin } from "vite";

/**
 * ビルドした index.html のバンドル読み込みを「クラシックスクリプト + defer」に書き換える。
 *
 * Chrome などは file:// から開いたページで type="module" や crossorigin 付きの読み込みを
 * CORS エラーとして拒否する。成果物のサイトを zip で落としてローカルで開けるように、
 * バンドルは IIFE で出力し、ここで module / crossorigin 属性を外す。
 * defer にしておけば、head の manifest.js（同期スクリプト）の後、DOM 構築後に実行される。
 */
export function classicScript(): Plugin {
  return {
    name: "fukurou:classic-script",
    apply: "build",
    transformIndexHtml: {
      order: "post",
      handler(html) {
        const rewritten = html
          // バンドル本体: module をやめて defer で読む
          .replace(/<script type="module" crossorigin src=/g, "<script defer src=")
          // CSS: crossorigin があると file:// で CORS リクエストになり読み込めない
          .replace(/<link rel="stylesheet" crossorigin href=/g, '<link rel="stylesheet" href=')
          // vite-ignore を外した跡に残る空白を消し、manifest.js のタグを元の形に戻す
          .replace(/<script src="\.\/manifest\.js"\s+>/, '<script src="./manifest.js">');
        // Vite の出力形式が変わって置換が効かなくなったら、file:// が静かに壊れる前にビルドを止める
        if (/type="module"|crossorigin/.test(rewritten)) {
          throw new Error("fukurou:classic-script could not rewrite the module script tag; file:// support would break.");
        }
        return rewritten;
      },
    },
  };
}
