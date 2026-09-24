import { css } from "styled-system/css";
import { codeBlockStyle } from "../../styles";

const box = css({
  mt: "6",
  p: "4",
  bg: "bg.error",
  color: "fg.error",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.error",
  borderRadius: "panel",
});

const message = css(codeBlockStyle, { mt: "2", fontSize: "sm" });

const stepButton = css({
  textDecoration: "underline",
  textUnderlineOffset: "2px",
  cursor: "pointer",
  fontWeight: "semibold",
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "border.error" },
});

interface FailureBoxProps {
  /** run の failure（phase と message）と test の failure（stepIndex 付き）のどちらでも受ける */
  failure: { phase: string; message: string; stepIndex?: number | null };
  /** 見出し。既定は "Failed during <phase>"。run の失敗では "This version could not be tested" などにする */
  title?: string;
}

/** 失敗の段階とメッセージを目立たせて表示する */
export function FailureBox({ failure, title }: FailureBoxProps) {
  const stepIndex = failure.stepIndex ?? null;
  // ハッシュルーティングなので #step-N のアンカーは使えない。スクロールは JS で行う
  const scrollToStep = () => {
    const row = document.getElementById(`step-${stepIndex}`);
    if (!row) return;
    // ステップが折りたたまれたまとまりの中にあるなら、先にそのまとまりの見出しボタンで開く
    const collapsed = row.closest<HTMLElement>("[hidden]");
    const toggle = collapsed?.id ? document.querySelector<HTMLButtonElement>(`[aria-controls="${collapsed.id}"]`) : null;
    if (toggle) {
      toggle.click();
      // 開いた後の描画を待ってからスクロールする
      requestAnimationFrame(() => row.scrollIntoView({ behavior: "smooth", block: "center" }));
      return;
    }
    row.scrollIntoView({ behavior: "smooth", block: "center" });
  };
  return (
    <div className={box} role="alert">
      <p className={css({ fontWeight: "semibold" })}>
        {title ?? "Failed during"} <code>{failure.phase}</code>
        {stepIndex !== null && (
          <>
            {" "}
            at{" "}
            <button type="button" onClick={scrollToStep} className={stepButton}>
              step {stepIndex}
            </button>
          </>
        )}
      </p>
      <pre className={message}>{failure.message}</pre>
    </div>
  );
}
