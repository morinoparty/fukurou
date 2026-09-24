import { useEffect, useState } from "react";

/**
 * ログ本文の読み込み状態。
 * - blocked: file:// で開いたページから fetch できない（Chromium はローカルファイルの fetch を拒否する）
 * - missing: サーバーが 404 などを返した（include-logs 無しでサイトを作った場合など）
 */
export type LogTextState =
  | { kind: "loading" }
  | { kind: "loaded"; text: string }
  | { kind: "blocked" }
  | { kind: "missing"; status: number }
  | { kind: "failed"; message: string };

/** 同じログを行き来するたびに取り直さないよう、読み込んだ本文をページ内で覚えておく */
const cache = new Map<string, string>();

/** file:// で開かれているか。この場合ブラウザはローカルファイルの fetch を許さない */
function isFileProtocol(): boolean {
  return window.location.protocol === "file:";
}

/** url のテキストを読み込む。url が変わったら前の読み込みの結果は捨てる */
export function useLogText(url: string): LogTextState {
  const [state, setState] = useState<LogTextState>(() => {
    if (isFileProtocol()) return { kind: "blocked" };
    const cached = cache.get(url);
    return cached === undefined ? { kind: "loading" } : { kind: "loaded", text: cached };
  });

  useEffect(() => {
    const cached = cache.get(url);
    if (cached !== undefined) {
      setState({ kind: "loaded", text: cached });
      return;
    }
    // file:// で開いたページからは Chromium も Firefox もローカルファイルの fetch を拒否し、
    // 試すだけでコンソールにエラーが出る。試さずに「生ログを開く」案内へ切り替える
    if (isFileProtocol()) {
      setState({ kind: "blocked" });
      return;
    }
    setState({ kind: "loading" });
    const controller = new AbortController();
    fetch(url, { signal: controller.signal })
      .then(async (response) => {
        // 静的ホスティングでは存在しないファイルが 404 になる（include-logs 無しで作ったサイトなど）
        if (!response.ok) {
          setState({ kind: "missing", status: response.status });
          return;
        }
        const text = await response.text();
        cache.set(url, text);
        setState({ kind: "loaded", text });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setState({ kind: "failed", message: error instanceof Error ? error.message : String(error) });
      });
    return () => controller.abort();
  }, [url]);

  return state;
}
