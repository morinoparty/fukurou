import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useRouterState } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button, ModalDialog } from "../../chlorophyll";
import { buttonLink } from "../../styles";
import { LightboxContext, type LightboxImage } from "./LightboxContext";

// Chlorophyll の ModalDialog.Container は幅 560px 固定（min-width も 560px）で、スクリーンショットにもスマホにも狭すぎる。
// 画面幅に合わせて広げ、縦も画面に収まるようにする（utilities レイヤーなのでレシピの指定に勝つ）
const container = css({
  width: "min(96vw, 1400px)",
  minWidth: "0",
  maxWidth: "96vw",
  maxHeight: "96vh",
  borderRadius: "2xl",
  // タイトル・フッター用の上下の余白を詰め、画像に面積を回す
  gridTemplate: `". . ." 16px ". title ." auto ". . ." 12px "main main main" minmax(0, 1fr) ". . ." 12px ". footer ." auto ". . ." 16px`,
  gridTemplateColumns: "16px 1fr 16px",
});

const title = css({ textStyle: "sm", fontWeight: "semibold", wordBreak: "break-all" });

const imageClass = css({
  display: "block",
  mx: "auto",
  maxWidth: "full",
  maxHeight: "calc(96vh - 9rem)",
  objectFit: "contain",
  bg: "bg.muted",
});

const footer = css({ flexWrap: "wrap", gap: "2" });

/** 退場アニメーション（normal = 200ms 前後）が終わらなかったときに、それでも閉じるまでの待ち時間 */
const EXIT_FALLBACK_MS = 600;

/** ダイアログの中で Tab で移れる要素 */
const FOCUSABLE = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Tab / Shift+Tab のフォーカスをダイアログの中で循環させる。
 * aria-modal を名乗る以上、背面のページ（暗幕の下のリンク）へフォーカスが抜けないようにする
 */
function trapFocus(event: KeyboardEvent, container: HTMLElement) {
  const focusable = [...container.querySelectorAll<HTMLElement>(FOCUSABLE)];
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (!first || !last) {
    event.preventDefault();
    return;
  }
  const active = document.activeElement;
  const inside = active instanceof Node && container.contains(active);
  if (event.shiftKey && (!inside || active === first)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (!inside || active === last)) {
    event.preventDefault();
    first.focus();
  }
}

interface LightboxProviderProps {
  children: ReactNode;
}

/**
 * スクリーンショットを画面に収まる最大サイズで重ねて表示する（Chlorophyll の ModalDialog）。
 * 背景クリック・Escape・Close ボタンで閉じ、元画像は新しいタブでも開けるようにする。
 * 開いている間はフォーカスをダイアログの中に留め、閉じたら開く前の要素（サムネイル）にフォーカスを戻す。
 */
export function LightboxProvider({ children }: LightboxProviderProps) {
  const [image, setImage] = useState<LightboxImage | null>(null);
  const close = useCallback(() => setImage(null), []);
  // ModalDialog.Container は ref を受け取らない型なので、id で DOM を引く
  const dialogId = useId();
  const findContainer = useCallback(() => document.getElementById(dialogId), [dialogId]);

  // Escape と Close ボタンも、背景クリックと同じ ModalDialog 自身の退場アニメーションを通して閉じる。
  // ModalDialog にはプログラムから閉じる API が無く、暗幕のクリックでだけ退場を始めるので、暗幕（Container の2つ上）をクリックする
  const requestClose = useCallback(() => {
    const closing = findContainer();
    const overlay = closing?.parentElement?.parentElement;
    if (!overlay) {
      setImage(null);
      return;
    }
    overlay.click();
    // アニメーションが止められていて animationend が来ない環境でも閉じられるよう、少し待っても同じダイアログが残っていれば閉じる
    window.setTimeout(() => {
      if (closing.isConnected) setImage(null);
    }, EXIT_FALLBACK_MS);
  }, [findContainer]);
  // 戻る・進むなどでページが変わったら、前のページの画像を重ねたままにしない
  const href = useRouterState({ select: (state) => state.location.href });

  useEffect(() => {
    setImage(null);
  }, [href]);

  // ModalDialog は Escape もフォーカスの閉じ込めも扱わないので、開いている間だけここで受け取る
  useEffect(() => {
    if (!image) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") requestClose();
      else if (event.key === "Tab") {
        const container = findContainer();
        if (container) trapFocus(event, container);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [image, requestClose, findContainer]);

  // 開く前にフォーカスしていた要素（サムネイル）を覚えておき、閉じたらそこへ戻す（キーボードで続きのサムネイルを辿れるように）。
  // ダイアログが描かれると Close ボタンの autoFocus でフォーカスが移るので、開く操作の時点で記録する
  const openerRef = useRef<Element | null>(null);
  const open = useCallback((next: LightboxImage) => {
    openerRef.current = document.activeElement;
    setImage(next);
  }, []);
  const isOpen = image !== null;
  useEffect(() => {
    if (!isOpen) return;
    return () => {
      const opener = openerRef.current;
      openerRef.current = null;
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, [isOpen]);

  return (
    <LightboxContext.Provider value={open}>
      {children}
      {image && (
        // onClose を渡さないと ModalDialog は history.back() で閉じようとする。ハッシュルーティングでは前のページに戻ってしまう
        <ModalDialog.Root onClose={close}>
          <ModalDialog.Container id={dialogId} className={container} role="dialog" aria-modal="true" aria-label={image.caption}>
            <ModalDialog.Title className={title}>{image.caption}</ModalDialog.Title>
            <ModalDialog.Content>
              <img src={image.src} alt={image.caption} className={imageClass} />
            </ModalDialog.Content>
            <ModalDialog.Footer className={footer}>
              <Button asChild intent="secondary" size="sm" className={buttonLink}>
                <a href={image.src} target="_blank" rel="noreferrer">
                  Open original
                </a>
              </Button>
              <Button intent="primary" size="sm" onClick={requestClose} autoFocus>
                Close (Esc)
              </Button>
            </ModalDialog.Footer>
          </ModalDialog.Container>
        </ModalDialog.Root>
      )}
    </LightboxContext.Provider>
  );
}
