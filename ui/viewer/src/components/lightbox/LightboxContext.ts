import { createContext, useContext } from "react";

/** ライトボックスに表示する画像 */
export interface LightboxImage {
  src: string;
  caption: string;
}

/** 画像を全画面で開く関数。Provider の外では何もしない */
export const LightboxContext = createContext<(image: LightboxImage) => void>(() => {});

/** スクリーンショットを全画面表示するための関数を取得する */
export function useLightbox(): (image: LightboxImage) => void {
  return useContext(LightboxContext);
}
