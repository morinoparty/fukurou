import { useDeferredValue, useMemo, useState } from "react";
import { css } from "styled-system/css";
import { Button, Skeleton, Spinner } from "../../chlorophyll";
import type { LogInfo, LogRange, ManifestRun } from "../../contract";
import { assetUrl } from "../../lib/assets";
import { filterLines, logFileName, parseLog, type ParsedLog } from "../../lib/logs";
import type { RunLog } from "../../lib/runs";
import { buttonLink, panelStyle } from "../../styles";
import { LogLines } from "./LogLines";
import { LogUnavailable } from "./LogUnavailable";
import { useLogText } from "./useLogText";

const toolbar = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2", mb: "2" });

// Chlorophyll には文字入力のコンポーネントが無いので、Select の Trigger と同じ高さ・角丸・枠線で揃えた素の input を使う
const filterInput = css({
  flex: "1 1 14rem",
  minWidth: "0",
  height: "control.sm",
  px: "3",
  bg: "bg.panel",
  color: "fg",
  fontSize: "sm",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.interactive",
  borderRadius: "control",
  _placeholder: { color: "fg.placeholder" },
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "colorPalette.focus.ring", outlineOffset: "1px" },
});

const counts = css({ fontSize: "xs", color: "fg.muted", fontVariantNumeric: "tabular-nums" });
const errorCount = css({ color: "fg.error", fontWeight: "semibold" });
const warnCount = css({ color: "fg.warning", fontWeight: "semibold" });

const loadingBox = css(panelStyle, { p: "4", display: "flex", flexDirection: "column", gap: "2" });

interface LogViewProps {
  run: ManifestRun;
  log: RunLog;
  /** run のページに埋め込むときの小さい表示。高さを抑え、最初に描く行数も減らす */
  compact?: boolean;
  /** テストの行範囲（logRanges）。強調して、最初にその先頭までスクロールする */
  range?: LogRange | null;
}

/**
 * ログ本文のビューア。ツールバー（絞り込み・Errors only・折り返し・生ログ・ダウンロード）と行番号付きの本文を出す。
 * 本文は fetch(run.base + path) で読み、file:// で読めないときは生ログへのリンクに切り替える。
 */
export function LogView({ run, log, compact = false, range = null }: LogViewProps) {
  const url = assetUrl(run, log.path);
  const state = useLogText(url);

  if (state.kind === "loading") {
    return (
      <div className={loadingBox} aria-busy="true">
        <div className={css({ display: "flex", alignItems: "center", gap: "2", color: "fg.muted" })}>
          <Spinner size="sm" aria-label="Loading log" />
          Loading {log.path}…
        </div>
        <Skeleton />
        <Skeleton className={css({ width: "4/5" })} />
        <Skeleton className={css({ width: "3/5" })} />
      </div>
    );
  }
  if (state.kind !== "loaded") return <LogUnavailable state={state} url={url} artifact={run.artifact} />;

  // key で読み込み直すたびに絞り込み状態を初期化する
  return (
    <LoadedLog
      key={url}
      text={state.text}
      kind={log.kind}
      url={url}
      fileName={logFileName(run.id, log)}
      compact={compact}
      range={range}
    />
  );
}

interface LoadedLogProps {
  text: string;
  /** 行の重要度の決め方がログの種類（クラッシュレポートかどうか）で変わる */
  kind: LogInfo["kind"];
  url: string;
  fileName: string;
  compact: boolean;
  range: LogRange | null;
}

/** 読み込み済みのログ。解析はテキストが変わったときだけ行う */
function LoadedLog({ text, kind, url, fileName, compact, range }: LoadedLogProps) {
  const parsed: ParsedLog = useMemo(() => parseLog(text, kind), [text, kind]);
  const [query, setQuery] = useState("");
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [wrap, setWrap] = useState(false);
  // テストの行範囲だけに絞るか。既定では前後の文脈も見えるよう絞らず、範囲は強調とスクロールで示す
  const [rangeOnly, setRangeOnly] = useState(false);
  // 入力のたびに数 MB のログを絞り込み直すと打鍵が詰まるので、描画は React に後回しにさせる
  const deferredQuery = useDeferredValue(query);
  const activeRange = rangeOnly ? range : null;
  const visible = useMemo(
    () => filterLines(parsed.lines, { query: deferredQuery, errorsOnly, range: activeRange }),
    [parsed, deferredQuery, errorsOnly, activeRange],
  );
  const filtering = deferredQuery.trim() !== "" || errorsOnly || activeRange !== null;

  return (
    <div>
      <div className={toolbar}>
        <input
          type="search"
          className={filterInput}
          placeholder="Filter lines…"
          aria-label="Filter lines"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <Button
          type="button"
          size="sm"
          intent={errorsOnly ? "primary" : "secondary"}
          aria-pressed={errorsOnly}
          onClick={() => setErrorsOnly((value) => !value)}
          disabled={parsed.errors === 0 && !errorsOnly}
        >
          Errors only
        </Button>
        {range && (
          <Button
            type="button"
            size="sm"
            intent={rangeOnly ? "primary" : "secondary"}
            aria-pressed={rangeOnly}
            onClick={() => setRangeOnly((value) => !value)}
            title="Show only the lines that belong to this test"
          >
            Only lines {range.from}–{range.to}
          </Button>
        )}
        <Button
          type="button"
          size="sm"
          intent={wrap ? "primary" : "secondary"}
          aria-pressed={wrap}
          onClick={() => setWrap((value) => !value)}
        >
          Wrap
        </Button>
        <Button asChild size="sm" intent="plain" className={buttonLink}>
          <a href={url} target="_blank" rel="noreferrer">
            Raw
          </a>
        </Button>
        <Button asChild size="sm" intent="plain" className={buttonLink}>
          <a href={url} download={fileName}>
            Download
          </a>
        </Button>
      </div>
      <p className={counts} aria-live="polite">
        {filtering && `${visible.length.toLocaleString()} matching of `}
        {parsed.lines.length.toLocaleString()} {parsed.lines.length === 1 ? "line" : "lines"} ·{" "}
        <span className={parsed.errors > 0 ? errorCount : undefined}>
          {parsed.errors.toLocaleString()} {parsed.errors === 1 ? "error" : "errors"}
        </span>{" "}
        ·{" "}
        <span className={parsed.warnings > 0 ? warnCount : undefined}>
          {parsed.warnings.toLocaleString()} {parsed.warnings === 1 ? "warning" : "warnings"}
        </span>
      </p>
      <LogLines
        // 絞り込み条件が変わったら表示範囲を先頭（range があればその先頭）に戻す
        key={`${deferredQuery}\u0000${errorsOnly}\u0000${rangeOnly}`}
        lines={visible}
        totalLines={parsed.lines.length}
        query={deferredQuery}
        wrap={wrap}
        compact={compact}
        highlight={range}
        scrollTo={range?.from ?? null}
      />
    </div>
  );
}
