import { Link, useParams } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../chlorophyll";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { Section } from "../components/Section";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import { BuildInfo } from "../components/run/BuildInfo";
import { FailureBox } from "../components/run/FailureBox";
import { PluginsTable } from "../components/run/PluginsTable";
import { RunLogs } from "../components/run/RunLogs";
import { runCrumb, runNeighbour } from "../components/run/runNav";
import { SessionsList } from "../components/run/SessionsList";
import { TestsTable } from "../components/run/TestsTable";
import { formatDuration } from "../lib/format";
import { findLogIndex } from "../lib/logs";
import { flatLogs, supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { pageTitle } from "../styles";

const header = css({ mt: "4", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });

/** #/runs/<runId> : 1バージョン分の実行結果（セッション、テストの表、環境、プラグイン、ログ） */
export function RunPage() {
  const { runId } = useParams({ from: "/runs/$runId" });
  const manifest = useManifest();
  const run = manifest.runs.find((candidate) => candidate.id === runId);
  const nav = (
    <PageNav
      crumbs={[runCrumb(run, runId, false)]}
      previous={runNeighbour(manifest.runs, runId, -1)}
      next={runNeighbour(manifest.runs, runId, 1)}
    />
  );

  if (!run) {
    return (
      <>
        {nav}
        <Message title={`Run "${runId}" is not in this report`} />
      </>
    );
  }

  const result = supportedResult(run);
  if (!result) {
    return (
      <div className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
        {nav}
        <UnsupportedRunCard run={run} />
      </div>
    );
  }

  const logs = flatLogs(result);
  const serverLog = findLogIndex(logs, "server");
  const summary = result.summary;
  return (
    <div>
      {nav}
      <header className={header}>
        <h1 className={pageTitle}>Minecraft {result.minecraft.version}</h1>
        <StatusBadge status={result.status} size="md" />
        <span className={css({ color: "fg.muted" })}>
          {run.id} · {formatDuration(result.durationMs)}
        </span>
        {serverLog >= 0 && (
          <Button asChild size="sm" intent="secondary" className={css({ ml: { md: "auto" }, textDecoration: "none" })}>
            <Link to="/runs/$runId/logs/$logIndex" params={{ runId: run.id, logIndex: String(serverLog) }}>
              Server log
            </Link>
          </Button>
        )}
      </header>
      {result.failure && <FailureBox failure={result.failure} title="This version could not be tested:" />}

      <Section
        title="Tests"
        aside={
          summary
            ? `${summary.passed} passed · ${summary.failed} failed · ${summary.error} error · ${summary.skipped} skipped of ${summary.total}`
            : `${result.tests.length} tests`
        }
      >
        <TestsTable run={run} tests={result.tests} />
      </Section>
      <Section title="Sessions" aside={`${result.sessions.length} ${result.sessions.length === 1 ? "session" : "sessions"}`}>
        <SessionsList run={run} result={result} />
      </Section>
      <Section title="Logs" aside={`${logs.length} files`}>
        {/* 前後の run へ移ってもこのページは使い回されるので、run ごとに作り直して選択中のタブを持ち越さない */}
        <RunLogs key={run.id} run={run} logs={logs} />
      </Section>
      <Section title="Environment">
        <BuildInfo result={result} />
      </Section>
      <Section title="Plugins">
        <PluginsTable plugins={result.plugins} />
      </Section>
    </div>
  );
}
