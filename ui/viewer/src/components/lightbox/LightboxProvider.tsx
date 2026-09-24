import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useRouterState } from "@tanstack/react-router";
import { LightboxContext, type LightboxImage } from "./LightboxContext";

interface LightboxProviderProps {
  children: ReactNode;
}

/**
 * スクリーンショットを原寸（画面に収まる最大サイズ）で重ねて表示する。
 * 背景クリックか Escape で閉じ、元画像は新しいタブでも開けるようにする。
 */
export function LightboxProvider({ children }: LightboxProviderProps) {
  const [image, setImage] = useState<LightboxImage | null>(null);
  const close = useCallback(() => setImage(null), []);
  // 戻る・進むなどでページが変わったら、前のページの画像を重ねたままにしない
  const href = useRouterState({ select: (state) => state.location.href });

  useEffect(() => {
    setImage(null);
  }, [href]);

  useEffect(() => {
    if (!image) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [image, close]);

  return (
    <LightboxContext.Provider value={setImage}>
      {children}
      {image && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={image.caption}
          className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-3 bg-black/85 p-4"
          onClick={close}
        >
          <img src={image.src} alt={image.caption} className="max-h-[85vh] max-w-full object-contain [image-rendering:auto]" />
          <div className="flex flex-wrap items-center justify-center gap-4 text-sm text-zinc-200">
            <span>{image.caption}</span>
            {/* 画像クリックでは閉じず、リンクだけは開けるように伝播を止める */}
            <a
              href={image.src}
              target="_blank"
              rel="noreferrer"
              className="text-sky-300"
              onClick={(event) => event.stopPropagation()}
            >
              Open original
            </a>
            <button type="button" className="rounded border border-zinc-500 px-2 py-0.5 hover:bg-zinc-800" onClick={close}>
              Close (Esc)
            </button>
          </div>
        </div>
      )}
    </LightboxContext.Provider>
  );
}
