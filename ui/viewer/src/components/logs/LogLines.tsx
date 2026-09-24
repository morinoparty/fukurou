import { memo, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { LogRange } from "../../contract";
import type { LogLevel, LogLine } from "../../lib/logs";
import { panelStyle } from "../../styles";

/** 一度に DOM に足す行数。数 MB（数万行）のログでも最初の描画を軽く保つ */
const PAGE = 1000;
/** 埋め込み表示で最初に描く行数 */
const COMPACT_PAGE = 200;
/** 「全部表示」を出す上限。これより多いと一度に描くと固まるので、ページ送りと絞り込みに任せる */
const SHOW_ALL_LIMIT = 20000;
/** 指定の行へスクロールするとき、その行の上に残しておく行数（前後の文脈が見えるように） */
const CONTEXT_LINES = 40;

const frameStyle = css.raw({
  fontFamily: "mono",
  fontSize: "xs",
  lineHeight: "1.6",
  overflow: "auto",
  // 長い行の横スクロールと、縦に長いログのスクロールをこの枠の中に閉じ込める
  maxHeight: "calc(100vh - 16rem)",
  minHeight: "12rem",
});
const frame = css(panelStyle, frameStyle);
const compactFrame = css(panelStyle, frameStyle, { maxHeight: "24rem", minHeight: "0" });

// 行番号とテキストの2列。折り返さないときは内容の幅まで広げて横スクロールさせる
const rowStyle = css.raw({
  display: "grid",
  gridTemplateColumns: "var(--gutter) minmax(0, 1fr)",
  borderLeftWidth: "3px",
  borderLeftStyle: "solid",
  borderLeftColor: "transparent",
});

// 重要度ごとの行の見た目。error / warn は地色と左の線で強調し、チャット行はごく薄く色を付ける
const LEVEL_ROW: Record<LogLevel, string> = {
  error: css(rowStyle, { bg: "bg.error", color: "fg.error", borderLeftColor: "border.error" }),
  warn: css(rowStyle, { bg: "bg.warning", color: "fg.warning", borderLeftColor: "border.warning" }),
  // チャット行は情報扱い。bg.info だと error / warn と同じ強さになるので、一段薄い面の色にする
  chat: css(rowStyle, { bg: "blue.surface.subtle", color: "fg", borderLeftColor: "border.info" }),
  // 行番号の列を sticky にしているので、横スクロールしたときに下の文字が透けないよう地色を塗る
  plain: css(rowStyle, { bg: "bg.panel", color: "fg" }),
};

const numberCellStyle = css.raw({
  px: "2",
  textAlign: "end",
  color: "fg.subtle",
  userSelect: "none",
  fontVariantNumeric: "tabular-nums",
  // 行番号の列は横スクロールしても左端に残す
  position: "sticky",
  left: "0",
  bg: "inherit",
});
const numberCell = css(numberCellStyle);
// テストの行範囲（logRanges）に入る行。error / warn の地色はそのまま残し、行番号の列に太い緑の線と色を付けて範囲を示す。
// 行そのものの inset box-shadow は sticky な行番号の地色の下に隠れるので、行番号の列に付ける
const rangeNumberCell = css(numberCellStyle, {
  color: "mori.fg",
  fontWeight: "semibold",
  boxShadow: "inset 4px 0 0 0 {colors.mori.solid}",
});
const textNoWrap = css({ pr: "3", whiteSpace: "pre" });
const textWrap = css({ pr: "3", whiteSpace: "pre-wrap", wordBreak: "break-all" });
const body = css({ minWidth: "max-content", bg: "bg.panel" });
const bodyWrap = css({ minWidth: "0", bg: "bg.panel" });
// 一致箇所は error / warn の行の上でも見分けられるよう、黄色の面の中で一番濃い段を使う
const mark = css({ bg: "yellow.surface.active", color: "inherit", borderRadius: "xs" });
const moreBar = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2", px: "3", py: "2", color: "fg.muted" });
const empty = css({ p: "4", color: "fg.muted", fontFamily: "sans" });

/**
 * 折り返しを切り替える前に見ていた位置。
 * 折り返すと行の高さが変わり scrollTop のままでは別の行が見えてしまうので、切り替えの後にこの位置へ戻す
 */
type ScrollAnchor =
  /** 末尾までスクロールしていた（Jump to end の直後など）。切り替え後も末尾に留める */
  | { kind: "bottom" }
  /** 枠の上端にかかっていた行（描画中の行の中での順番）と、その行の上端の枠からのずれ */
  | { kind: "row"; index: number; offset: number };

/** 「末尾にいる」とみなす余裕（px）。小数の scrollTop の誤差を吸収する */
const BOTTOM_SLACK = 4;

/** 今の表示位置を記録する。描画中の行を二分探索し、枠の上端にかかる最初の行を探す */
function captureAnchor(frame: HTMLElement, body: HTMLElement): ScrollAnchor | null {
  if (frame.scrollHeight - frame.scrollTop - frame.clientHeight <= BOTTOM_SLACK && frame.scrollTop > 0) {
    return { kind: "bottom" };
  }
  const rows = body.children;
  if (rows.length === 0) return null;
  const top = frame.getBoundingClientRect().top;
  let low = 0;
  let high = rows.length - 1;
  while (low < high) {
    const mid = (low + high) >> 1;
    if ((rows[mid] as HTMLElement).getBoundingClientRect().bottom <= top) low = mid + 1;
    else high = mid;
  }
  const row = rows[low] as HTMLElement;
  return { kind: "row", index: low, offset: row.getBoundingClientRect().top - top };
}

/** 記録しておいた位置へ戻す */
function restoreAnchor(frame: HTMLElement, body: HTMLElement, anchor: ScrollAnchor) {
  if (anchor.kind === "bottom") {
    frame.scrollTop = frame.scrollHeight;
    return;
  }
  const row = body.children[anchor.index] as HTMLElement | undefined;
  if (!row) return;
  const offset = row.getBoundingClientRect().top - frame.getBoundingClientRect().top;
  frame.scrollTop += offset - anchor.offset;
}

interface LogLinesProps {
  /** 絞り込み済みの行（元の行番号付き） */
  lines: LogLine[];
  /** 絞り込み前の行数。行番号の桁数を揃えるのに使う */
  totalLines: number;
  /** 一致箇所を強調する文字列 */
  query: string;
  wrap: boolean;
  compact: boolean;
  /** 強調する行番号の範囲（テストの logRanges）。両端含む */
  highlight?: LogRange | null;
  /** 最初にこの行番号（以上の最初の行）が見えるところまでスクロールする */
  scrollTo?: number | null;
}

/** lines の中で number >= target になる最初の添字。無ければ -1 */
function indexOfLine(lines: LogLine[], target: number): number {
  return lines.findIndex((line) => line.number >= target);
}

/**
 * 行番号付きのログ本文。
 * 全行を一度に描くと数万行のログで固まるため、[start, end) の範囲だけを描き、前後は「もっと表示」で広げる。
 * scrollTo があれば、その行が最初の描画範囲に入るようにし、描画後に枠をその行までスクロールする
 */
export function LogLines({ lines, totalLines, query, wrap, compact, highlight = null, scrollTo = null }: LogLinesProps) {
  const page = compact ? COMPACT_PAGE : PAGE;
  // 最初に見せたい行（scrollTo 以上の最初の行）。無ければ先頭から
  const targetIndex = scrollTo === null ? -1 : indexOfLine(lines, scrollTo);
  const [range, setRange] = useState(() => {
    const start = targetIndex < 0 ? 0 : Math.max(0, targetIndex - CONTEXT_LINES);
    return { start, end: Math.min(lines.length, start + page) };
  });
  const frameRef = useRef<HTMLDivElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  // 最初の描画で一度だけ scrollTo の行まで枠を動かす（絞り込みの変更では key で作り直されるので、そのたびに行う）
  const scrolledRef = useRef(false);
  useLayoutEffect(() => {
    if (scrolledRef.current || targetIndex < 0) return;
    scrolledRef.current = true;
    const frame = frameRef.current;
    const bodyElement = bodyRef.current;
    const target = lines[targetIndex];
    if (!frame || !bodyElement || !target) return;
    const row = bodyElement.querySelector<HTMLElement>(`[data-line="${target.number}"]`);
    if (!row) return;
    // scrollIntoView はページ全体も動かしてしまうので、枠の scrollTop だけを変える。行の少し上（2 行分）に余白を残す
    const offset = row.getBoundingClientRect().top - frame.getBoundingClientRect().top + frame.scrollTop;
    frame.scrollTop = Math.max(0, offset - row.offsetHeight * 2);
  }, [lines, targetIndex]);
  // スクロールのたびに更新する表示位置。折り返しの切り替えで使う
  const anchorRef = useRef<ScrollAnchor | null>(null);
  // ボタン操作の後で枠をどこへスクロールするか（描画し終えてから動かす）
  const pendingScroll = useRef<"top" | "bottom" | null>(null);
  const previousWrap = useRef(wrap);

  // 折り返しを切り替えたら、切り替える前に見ていた行（または末尾）を同じ位置に戻す
  useLayoutEffect(() => {
    if (previousWrap.current === wrap) return;
    previousWrap.current = wrap;
    const frame = frameRef.current;
    const bodyElement = bodyRef.current;
    if (frame && bodyElement && anchorRef.current) restoreAnchor(frame, bodyElement, anchorRef.current);
  }, [wrap]);

  // "Jump to end" / "Back to top" で表示範囲を変えたら、その端まで枠をスクロールする
  useLayoutEffect(() => {
    const frame = frameRef.current;
    if (!frame || pendingScroll.current === null) return;
    frame.scrollTop = pendingScroll.current === "bottom" ? frame.scrollHeight : 0;
    pendingScroll.current = null;
  }, [range]);

  const onScroll = () => {
    const frame = frameRef.current;
    const bodyElement = bodyRef.current;
    if (frame && bodyElement) anchorRef.current = captureAnchor(frame, bodyElement);
  };
  const start = Math.min(range.start, lines.length);
  const end = Math.min(range.end, lines.length);
  const shown = lines.slice(start, end);
  const remaining = lines.length - end;
  // 行番号の列幅。最大の行番号の桁数に合わせる（ch は等幅フォントの1文字幅）
  const gutter = `calc(${String(totalLines).length}ch + 1rem)`;

  if (lines.length === 0) {
    return (
      <div className={compact ? compactFrame : frame}>
        <p className={empty}>{totalLines === 0 ? "This log is empty." : "No lines match the filter."}</p>
      </div>
    );
  }

  return (
    <div
      ref={frameRef}
      className={compact ? compactFrame : frame}
      style={{ "--gutter": gutter } as CSSProperties}
      onScroll={onScroll}
    >
      {start > 0 && (
        <div className={moreBar}>
          <span>{start.toLocaleString()} earlier lines hidden</span>
          <Button type="button" size="sm" intent="secondary" onClick={() => setRange({ start: Math.max(0, start - page), end })}>
            Show {Math.min(page, start).toLocaleString()} earlier
          </Button>
          <Button
            type="button"
            size="sm"
            intent="plain"
            onClick={() => {
              pendingScroll.current = "top";
              setRange({ start: 0, end: Math.min(lines.length, page) });
            }}
          >
            Back to top
          </Button>
        </div>
      )}
      <div ref={bodyRef} className={wrap ? bodyWrap : body} role="log" aria-label="Log lines">
        {shown.map((line) => (
          <Row
            key={line.number}
            line={line}
            query={query}
            wrap={wrap}
            inRange={highlight !== null && line.number >= highlight.from && line.number <= highlight.to}
          />
        ))}
      </div>
      {remaining > 0 && (
        <div className={moreBar}>
          <span>{remaining.toLocaleString()} more lines</span>
          <Button type="button" size="sm" intent="secondary" onClick={() => setRange({ start, end: end + page })}>
            Show {Math.min(page, remaining).toLocaleString()} more
          </Button>
          {lines.length - start <= SHOW_ALL_LIMIT && (
            <Button type="button" size="sm" intent="plain" onClick={() => setRange({ start, end: lines.length })}>
              Show all
            </Button>
          )}
          {/* サーバーログは末尾（停止直前）に原因があることが多いので、最後の行へすぐ飛べるようにする */}
          <Button
            type="button"
            size="sm"
            intent="plain"
            onClick={() => {
              pendingScroll.current = "bottom";
              setRange({ start: Math.max(0, lines.length - page), end: lines.length });
            }}
          >
            Jump to end
          </Button>
        </div>
      )}
    </div>
  );
}

interface RowProps {
  line: LogLine;
  query: string;
  wrap: boolean;
  /** テストの行範囲に入っているか */
  inRange: boolean;
}

/** 1行分。ページ送りで行が増えても既存の行は描き直さないよう memo にする */
const Row = memo(function Row({ line, query, wrap, inRange }: RowProps) {
  return (
    <div className={LEVEL_ROW[line.level]} data-level={line.level} data-line={line.number} data-in-range={inRange || undefined}>
      <span className={inRange ? rangeNumberCell : numberCell} aria-hidden="true">
        {line.number}
      </span>
      <span className={wrap ? textWrap : textNoWrap}>{highlight(line.text, query)}</span>
    </div>
  );
});

/** 絞り込み文字列に一致した箇所を <mark> で囲む（大文字小文字は区別しない） */
function highlight(text: string, query: string): ReactNode {
  const needle = query.trim().toLowerCase();
  // 空行も1行分の高さを保つ
  if (needle === "") return text === "" ? " " : text;
  const lower = text.toLowerCase();
  const parts: ReactNode[] = [];
  let from = 0;
  let at = lower.indexOf(needle, from);
  while (at !== -1) {
    if (at > from) parts.push(text.slice(from, at));
    parts.push(
      <mark key={at} className={mark}>
        {text.slice(at, at + needle.length)}
      </mark>,
    );
    from = at + needle.length;
    at = lower.indexOf(needle, from);
  }
  if (from < text.length) parts.push(text.slice(from));
  return parts;
}
