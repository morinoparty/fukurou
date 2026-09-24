import { Link, useParams } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../chlorophyll";
import { Message } from "../components/Message";
import { Section } from "../components/Section";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import { BuildInfo } from "../components/run/BuildInfo";
import { FailureBox } from "../components/run/FailureBox";
import { PlayerScreenshots } from "../components/run/PlayerScreenshots";
import { PluginsTable } from "../components/run/PluginsTable";
import { RunLogs } from "../components/run/RunLogs";
import { RunNav } from "../components/run/RunNav";
import { StepTimeline } from "../components/run/StepTimeline";
import { formatDuration } from "../lib/format";
import { findLogIndex } from "../lib/logs";
import { supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { pageTitle } from "../styles";

const header = css({ mt: "4", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });

/** #/runs/<id> : 1バージョン分の実行結果の詳細 */
export function RunPage() {
  const { id } = useParams({ from: "/runs/$id" });
  const manifest = useManifest();
  const run = manifest.runs.find((candidate) => candidate.id === id);

  if (!run) {
    return (
      <>
        <RunNav runs={manifest.runs} current={id} />
        <Message title={`Run "${id}" is not in this report`} />
      </>
    );
  }

  const result = supportedResult(run);
  if (!result) {
    return (
      <div className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
        <RunNav runs={manifest.runs} current={id} />
        <UnsupportedRunCard run={run} />
      </div>
    );
  }

  const serverLog = findLogIndex(result.logs, "server");
  return (
    <div>
      <RunNav runs={manifest.runs} current={id} />
      <header className={header}>
        <h1 className={pageTitle}>Minecraft {result.minecraft.version}</h1>
        <StatusBadge status={result.status} size="md" />
        <span className={css({ color: "fg.muted" })}>
          {run.id} · {formatDuration(result.durationMs)}
        </span>
        {serverLog >= 0 && (
          <Button asChild size="sm" intent="secondary" className={css({ ml: { md: "auto" }, textDecoration: "none" })}>
            <Link to="/runs/$id/logs/$logIndex" params={{ id: run.id, logIndex: String(serverLog) }}>
              Server log
            </Link>
          </Button>
        )}
      </header>
      {result.failure && <FailureBox failure={result.failure} />}

      <Section title="Screenshots" aside={`${result.screenshots.length} total`}>
        <PlayerScreenshots run={run} result={result} />
      </Section>
      <Section title="Steps" aside={`${result.steps.length} steps`}>
        <StepTimeline run={run} steps={result.steps} />
      </Section>
      <Section title="Logs" aside={`${result.logs.length} files`}>
        {/* 前後の run へ移ってもこのページは使い回されるので、run ごとに作り直して選択中のタブを持ち越さない */}
        <RunLogs key={run.id} run={run} logs={result.logs} />
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
