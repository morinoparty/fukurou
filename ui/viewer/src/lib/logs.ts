import type { LogInfo } from "../contract";

/** 1行ごとの重要度。error / warn は強調し、chat（クライアントのチャット行）は控えめに色を付ける */
export type LogLevel = "error" | "warn" | "chat" | "plain";

/** 解析済みの1行。number は元ファイルでの 1 始まりの行番号（絞り込んでも変わらない） */
export interface LogLine {
  number: number;
  text: string;
  level: LogLevel;
}

/** ログ全体の解析結果と、ツールバーに出す件数 */
export interface ParsedLog {
  lines: LogLine[];
  errors: number;
  warnings: number;
}

// Minecraft（Log4j）の行頭: "[07:37:44] [Server thread/WARN]: ..."。レベルはスラッシュの後ろ
const LOG4J_HEADER = /^\[\d{1,2}:\d{2}:\d{2}(?:\.\d+)?\] \[[^\]]*\/([A-Z]+)\]/;
// fukurou 自身（Python logging）の行頭: "2026-09-24 07:37:26,763 INFO fukurou....: ..."
const PYTHON_HEADER = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)? +([A-Z]+)\b/;
// クライアントのチャット欄に出た行（"[System] [CHAT] Alice joined the game" など）
const CHAT_MARKER = /\[(?:System\] \[)?CHAT\]/;

// クラッシュレポートには行頭のレベルが無いので、例外名・"Caused by:"・スタックトレースの行を error とみなす
const CRASH_EXCEPTION = /^(?:[\w$.]+(?:Exception|Error|Throwable)\b|Caused by:)/;
const CRASH_STACK_FRAME = /^\s+(?:at |\.\.\. \d+ more)/;

/** ログのレベル名を表示用の重要度に丸める */
function levelFromName(name: string): LogLevel {
  if (name === "ERROR" || name === "FATAL" || name === "SEVERE" || name === "CRITICAL") return "error";
  if (name === "WARN" || name === "WARNING") return "warn";
  return "plain";
}

/**
 * ログのテキストを行に分け、行ごとの重要度を決める。
 *
 * 行頭にタイムスタンプとレベルがある行はそのレベルを使う。
 * スタックトレースのような行頭の無い継続行は、直前のヘッダー行（error / warn）のレベルを引き継ぎ、
 * "Errors only" で絞り込んだときに例外の中身も一緒に残るようにする。
 * クラッシュレポート（kind が "crash"）は行頭のレベルを持たないため、例外とスタックトレースの行を error にする。
 */
export function parseLog(text: string, kind?: LogInfo["kind"]): ParsedLog {
  // 末尾の改行で空行が1つ増えないようにする。CRLF のログも同じ行として扱う
  const raw = text.replace(/\r\n?/g, "\n").replace(/\n$/, "");
  const lines: LogLine[] = [];
  let errors = 0;
  let warnings = 0;
  // 継続行に引き継ぐレベル。ヘッダー行が来るたびに更新する
  let inherited: LogLevel = "plain";

  const source = raw === "" ? [] : raw.split("\n");
  for (let index = 0; index < source.length; index++) {
    const lineText = source[index] ?? "";
    const header = LOG4J_HEADER.exec(lineText) ?? PYTHON_HEADER.exec(lineText);
    let level: LogLevel;
    if (header) {
      level = levelFromName(header[1] ?? "");
      // チャット行は INFO なので、レベルが plain のときだけチャットとして色を付ける
      if (level === "plain" && CHAT_MARKER.test(lineText)) level = "chat";
      // チャットは継続行を持たないので、引き継ぐのは error / warn だけ
      inherited = level === "chat" ? "plain" : level;
      if (level === "error") errors++;
      if (level === "warn") warnings++;
    } else if (kind === "crash") {
      // 例外とスタックトレースの行を error にし、"Errors only" でそれだけが残るようにする。
      // 件数はほかのログと揃えて例外の行（ヘッダーに相当）だけを数え、スタックトレースの行は数えない
      const exception = CRASH_EXCEPTION.test(lineText);
      level = exception || CRASH_STACK_FRAME.test(lineText) ? "error" : "plain";
      if (exception) errors++;
    } else {
      level = inherited;
    }
    lines.push({ number: index + 1, text: lineText, level });
  }
  return { lines, errors, warnings };
}

/** 絞り込み条件 */
export interface LogFilter {
  /** 大文字小文字を区別しない部分一致。空なら絞り込まない */
  query: string;
  /** error（とその継続行）だけにする */
  errorsOnly: boolean;
  /** error に加えて warn も残す（errorsOnly のときだけ意味がある） */
  includeWarnings?: boolean;
}

/** 条件に合う行だけを返す。元の行番号は保つ */
export function filterLines(lines: LogLine[], filter: LogFilter): LogLine[] {
  const needle = filter.query.trim().toLowerCase();
  if (needle === "" && !filter.errorsOnly) return lines;
  return lines.filter((line) => {
    if (filter.errorsOnly && line.level !== "error" && !(filter.includeWarnings && line.level === "warn")) return false;
    return needle === "" || line.text.toLowerCase().includes(needle);
  });
}

/**
 * ログの種類ごとの表示名。クライアントやクラッシュレポートはプレイヤー名を添える。
 * クラッシュレポートのファイル名は長くスマホ幅のタブに収まらないので、表示名には含めない（区別は logLabels で番号を振る）
 */
export function logLabel(log: LogInfo): string {
  switch (log.kind) {
    case "server":
      return "Server";
    case "harness":
      return "Harness";
    case "client":
      return log.player ? `Client · ${log.player}` : "Client";
    case "crash":
      return log.player ? `Crash · ${log.player}` : "Crash";
    default:
      // 契約に無い種類が増えても表示は壊さない
      return String((log as { kind: unknown }).kind);
  }
}

/**
 * result.logs 全体の表示名。同じ表示名が複数ある（同じプレイヤーのクラッシュレポートが2つ以上など）ときは
 * 2つ目以降に " #2" のような番号を付け、タブや見出しで区別できるようにする
 */
export function logLabels(logs: LogInfo[]): string[] {
  const seen = new Map<string, number>();
  return logs.map((log) => {
    const label = logLabel(log);
    const count = (seen.get(label) ?? 0) + 1;
    seen.set(label, count);
    return count === 1 ? label : `${label} #${count}`;
  });
}

/** 保存するときのファイル名。run id を前に付けて、複数の run のログを並べても区別できるようにする */
export function logFileName(runId: string, log: LogInfo): string {
  const file = log.path.split("/").pop() ?? "log.txt";
  const player = log.kind === "client" || log.kind === "crash" ? `${log.player ?? "unknown"}-` : "";
  return `${runId}-${player}${file}`;
}

/** 指定した種類（とプレイヤー）のログが result.logs の何番目かを返す。無ければ -1 */
export function findLogIndex(logs: LogInfo[], kind: LogInfo["kind"], player?: string): number {
  return logs.findIndex((log) => log.kind === kind && (player === undefined || log.player === player));
}
