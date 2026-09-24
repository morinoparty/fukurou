import { Link, useParams } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { css } from "styled-system/css";
import { LogTabs } from "../components/logs/LogTabs";
import { LogView } from "../components/logs/LogView";
import { Message } from "../components/Message";
import { RunNav } from "../components/run/RunNav";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import type { LogInfo, ManifestRun } from "../contract";
import { findLogIndex, logLabels } from "../lib/logs";
import { supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { pageTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "4" });
const header = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });

/** #/runs/<id>/logs/<index> : result.logs の1つを行番号・強調・絞り込み付きで読む */
export function LogPage() {
  const { id, logIndex } = useParams({ from: "/runs/$id/logs/$logIndex" });
  const manifest = useManifest();
  const run = manifest.runs.find((candidate) => candidate.id === id);
  const result = run ? supportedResult(run) : null;
  const index = Number.parseInt(logIndex, 10);
  const log = result && Number.isInteger(index) ? result.logs[index] : undefined;

  if (!run) {
    return (
      <>
        <RunNav runs={manifest.runs} current={id} logLabel="Logs" />
        <Message title={`Run "${id}" is not in this report`} />
      </>
    );
  }
  if (!result) {
    return (
      <div className={page}>
        <RunNav runs={manifest.runs} current={id} logLabel="Logs" />
        <UnsupportedRunCard run={run} />
      </div>
    );
  }
  if (!log) {
    return (
      <>
        <RunNav runs={manifest.runs} current={id} logLabel="Logs" />
        <Message title={`Log ${logIndex} is not in this run`}>
          <p>
            <Link to="/runs/$id" params={{ id }}>
              Back to the run
            </Link>
          </p>
        </Message>
      </>
    );
  }

  // 前後の run では同じ種類（同じプレイヤー）のログを開く。無ければ run のページへ
  const neighbour = (target: ManifestRun, children: ReactNode) => {
    const targetLogs = supportedResult(target)?.logs ?? [];
    const targetIndex = sameLogIndex(targetLogs, log);
    return targetIndex >= 0 ? (
      <Link to="/runs/$id/logs/$logIndex" params={{ id: target.id, logIndex: String(targetIndex) }}>
        {children}
      </Link>
    ) : (
      <Link to="/runs/$id" params={{ id: target.id }}>
        {children}
      </Link>
    );
  };

  // タブと同じ表示名（同じ名前が並ぶときは番号付き）を見出しとパンくずにも使う
  const label = logLabels(result.logs)[index] ?? log.kind;

  return (
    <div className={page}>
      <RunNav runs={manifest.runs} current={id} logLabel={label} renderNeighbour={neighbour} />
      <header className={header}>
        {/* Minecraft のバージョンはパンくずに出ているので、見出しはログの名前だけにしてスマホでも1行に収める */}
        <h1 className={pageTitle}>{label} log</h1>
        <StatusBadge status={result.status} size="md" />
      </header>
      <p className={css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" })}>
        {run.artifact} / {log.path}
      </p>
      <LogTabs logs={result.logs} current={index} mode={{ kind: "link", runId: run.id }} label="Logs of this run" />
      <LogView key={`${run.id}:${log.path}`} run={run} log={log} />
    </div>
  );
}

/** 別の run の logs から、同じ種類・同じプレイヤー（クラッシュレポートは同じファイル名）のログを探す */
function sameLogIndex(logs: LogInfo[], log: LogInfo): number {
  if (log.kind === "crash") return logs.findIndex((candidate) => candidate.path === log.path);
  return findLogIndex(logs, log.kind, log.player ?? undefined);
}
