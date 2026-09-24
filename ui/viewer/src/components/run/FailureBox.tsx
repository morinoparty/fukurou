import type { Failure } from "../../contract";

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
    <div className="mt-6 rounded-lg border border-red-300 bg-red-50 p-4 text-red-900 dark:border-red-500/40 dark:bg-red-500/10 dark:text-red-200">
      <p className="text-sm font-semibold">
        Failed during <code>{failure.phase}</code>
        {failure.stepIndex !== null && (
          <>
            {" "}
            at{" "}
            <button type="button" onClick={scrollToStep} className="underline underline-offset-2">
              step {failure.stepIndex}
            </button>
          </>
        )}
      </p>
      <pre className="mt-2 overflow-x-auto whitespace-pre-wrap break-words text-sm">{failure.message}</pre>
    </div>
  );
}
