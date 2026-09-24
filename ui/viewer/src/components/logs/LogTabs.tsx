import { Link } from "@tanstack/react-router";
import { useEffect, useRef, type RefObject } from "react";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import { logLabels } from "../../lib/logs";
import type { RunLog } from "../../lib/runs";
import { buttonLink } from "../../styles";

// Chlorophyll に Tabs が無いので、Button を横に並べてタブ代わりにする。
// 狭い画面では折り返さずに横スクロールさせ、1行に収める
const list = css({
  display: "flex",
  // ボタンは既定の flex-shrink: 1 だと縮んで文字が切れる（Button は overflow: hidden）。縮めずに列ごと横スクロールさせる
  "& > *": { flexShrink: "0" },
  // 選択中のタブの offsetLeft をこの列からの位置として読むため
  position: "relative",
  gap: "2",
  overflowX: "auto",
  pb: "1",
  // スクロールバーの分だけ下に余白を取り、フォーカスリングが切れないよう上下に少し逃がす
  pt: "1",
  px: "1",
  mx: "-1",
});

interface LogTabsProps {
  /** flatLogs(result) */
  logs: RunLog[];
  /** 選択中のログの index */
  current: number;
  /** ログビューアのページではルーターのリンク、run のページではその場の切り替え */
  mode: { kind: "link"; runId: string } | { kind: "select"; onSelect: (index: number) => void };
  label: string;
}

/**
 * run の全ログ（flatLogs）を切り替えるタブ列。
 * ページを移るリンクのときは nav + aria-current、その場で切り替えるボタンのときは aria-pressed で選択中を伝える
 */
export function LogTabs({ logs, current, mode, label }: LogTabsProps) {
  const Container = mode.kind === "link" ? "nav" : "div";
  const labels = logLabels(logs);
  const listRef = useRef<HTMLElement>(null);

  // 選択中のタブが横スクロールの外（スマホ幅で右端のクラッシュレポートなど）にあれば、見える位置まで列を送る。
  // scrollIntoView はページ全体も縦に動かしてしまうので、列の scrollLeft だけを動かす
  useEffect(() => {
    const list = listRef.current;
    const tab = list?.children[current];
    if (!list || !(tab instanceof HTMLElement)) return;
    const right = tab.offsetLeft + tab.offsetWidth;
    if (tab.offsetLeft < list.scrollLeft) list.scrollLeft = tab.offsetLeft;
    else if (right > list.scrollLeft + list.clientWidth) list.scrollLeft = right - list.clientWidth;
  }, [current]);
  return (
    <Container
      ref={listRef as RefObject<HTMLDivElement>}
      aria-label={label}
      role={mode.kind === "link" ? undefined : "group"}
      className={list}
    >
      {logs.map((log, index) => {
        const selected = index === current;
        const intent = selected ? "primary" : "secondary";
        if (mode.kind === "link") {
          return (
            <Button key={`${log.session ?? "run"}:${log.path}`} asChild size="sm" intent={intent} className={buttonLink}>
              <Link
                to="/runs/$runId/logs/$logIndex"
                params={{ runId: mode.runId, logIndex: String(index) }}
                aria-current={selected ? "page" : undefined}
                // 表示名はファイル名を省いているので、ホバーでパスを確かめられるようにする
                title={log.path}
                replace
              >
                {labels[index]}
              </Link>
            </Button>
          );
        }
        return (
          <Button
            key={`${log.session ?? "run"}:${log.path}`}
            type="button"
            size="sm"
            intent={intent}
            aria-pressed={selected}
            onClick={() => mode.onSelect(index)}
            title={log.path}
          >
            {labels[index]}
          </Button>
        );
      })}
    </Container>
  );
}
