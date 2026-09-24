import { css } from "styled-system/css";
import type { Failure } from "../../contract";
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
  failure: Failure;
}

/** 失敗の段階とメッセージを目立たせて表示する */
export function FailureBox({ failure }: FailureBoxProps) {
  // ハッシュルーティングなので #step-N のアンカーは使えない。スクロールは JS で行う
  const scrollToStep = () => {
    document.getElementById(`step-${failure.stepIndex}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  };
  return (
    <div className={box} role="alert">
      <p className={css({ fontWeight: "semibold" })}>
        Failed during <code>{failure.phase}</code>
        {failure.stepIndex !== null && (
          <>
            {" "}
            at{" "}
            <button type="button" onClick={scrollToStep} className={stepButton}>
              step {failure.stepIndex}
            </button>
          </>
        )}
      </p>
      <pre className={message}>{failure.message}</pre>
    </div>
  );
}
