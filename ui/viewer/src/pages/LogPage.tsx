import { Link, useParams, useSearch } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { LogTabs } from "../components/logs/LogTabs";
import { LogView } from "../components/logs/LogView";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { runCrumb } from "../components/run/runNav";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import type { LogRange, ManifestRun } from "../contract";
import { findLogIndex, findLogIndexByPath, logLabels } from "../lib/logs";
import { flatLogs, runLabel, supportedResult, type RunLog } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { pageTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "4" });
const header = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });
const rangeNote = css({ fontSize: "xs", color: "mori.fg", fontWeight: "semibold" });

/** #/runs/<runId>/logs/<index>?from=&to= : flatLogs(result) の1つを行番号・強調・絞り込み付きで読む */
export function LogPage() {
  const { runId, logIndex } = useParams({ from: "/runs/$runId/logs/$logIndex" });
  const search = useSearch({ from: "/runs/$runId/logs/$logIndex" });
  const manifest = useManifest();
  const run = manifest.runs.find((candidate) => candidate.id === runId);
  const result = run ? supportedResult(run) : null;
  const logs = result ? flatLogs(result) : [];
  const index = Number.parseInt(logIndex, 10);
  const log = Number.isInteger(index) ? logs[index] : undefined;
  // from だけでも範囲として扱う（to は末尾まで）。to だけは意味が無いので無視する
  const range: LogRange | null =
    search.from !== undefined ? { from: search.from, to: search.to ?? Number.MAX_SAFE_INTEGER } : null;

  if (!run) {
    return (
      <>
        <PageNav crumbs={[runCrumb(undefined, runId, true), { key: "log", label: "Logs" }]} />
        <Message title={`Run "${runId}" is not in this report`} />
      </>
    );
  }
  if (!result) {
    return (
      <div className={page}>
        <PageNav crumbs={[runCrumb(run, runId, true), { key: "log", label: "Logs" }]} />
        <UnsupportedRunCard run={run} />
      </div>
    );
  }
  if (!log) {
    return (
      <>
        <PageNav crumbs={[runCrumb(run, runId, true), { key: "log", label: "Logs" }]} />
        <Message title={`Log ${logIndex} is not in this run`}>
          <p>
            <Link to="/runs/$runId" params={{ runId }}>
              Back to the run
            </Link>
          </p>
        </Message>
      </>
    );
  }

  // タブと同じ表示名（同じ名前が並ぶときは番号付き）を見出しとパンくずにも使う
  const label = logLabels(logs)[index] ?? log.kind;

  return (
    <div className={page}>
      <PageNav
        crumbs={[runCrumb(run, runId, true), { key: "log", label }]}
        previous={neighbour(manifest.runs, runId, log, -1)}
        next={neighbour(manifest.runs, runId, log, 1)}
      />
      <header className={header}>
        {/* Minecraft のバージョンはパンくずに出ているので、見出しはログの名前だけにしてスマホでも1行に収める */}
        <h1 className={pageTitle}>{label} log</h1>
        <StatusBadge status={result.status} size="md" />
        {range && (
          <span className={rangeNote}>
            lines {range.from}–{range.to} highlighted
          </span>
        )}
      </header>
      <p className={css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" })}>
        {run.artifact} / {log.path}
      </p>
      <LogTabs logs={logs} current={index} mode={{ kind: "link", runId: run.id }} label="Logs of this run" />
      <LogView key={`${run.id}:${index}:${log.path}`} run={run} log={log} range={range} />
    </div>
  );
}

/**
 * 前後の run では同じログ（同じパス、無ければ同じ種類・同じプレイヤー）を開く。無ければ run のページへ。
 * 行範囲は run ごとに違うので引き継がない
 */
function neighbour(runs: ManifestRun[], current: string, log: RunLog, offset: -1 | 1) {
  const index = runs.findIndex((run) => run.id === current);
  const target = index >= 0 ? runs[index + offset] : undefined;
  if (!target) return undefined;
  const label = offset < 0 ? `‹ ${runLabel(target)}` : `${runLabel(target)} ›`;
  const targetResult = supportedResult(target);
  const targetLogs = targetResult ? flatLogs(targetResult) : [];
  const byPath = findLogIndexByPath(targetLogs, log.path);
  const targetIndex = byPath >= 0 ? byPath : findLogIndex(targetLogs, log.kind, log.player ?? undefined);
  return targetIndex >= 0 ? (
    <Link to="/runs/$runId/logs/$logIndex" params={{ runId: target.id, logIndex: String(targetIndex) }}>
      {label}
    </Link>
  ) : (
    <Link to="/runs/$runId" params={{ runId: target.id }}>
      {label}
    </Link>
  );
}
