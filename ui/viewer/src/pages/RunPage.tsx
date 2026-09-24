import { useParams } from "@tanstack/react-router";
import { Message } from "../components/Message";
import { Section } from "../components/Section";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import { BuildInfo } from "../components/run/BuildInfo";
import { FailureBox } from "../components/run/FailureBox";
import { LogLinks } from "../components/run/LogLinks";
import { PlayerScreenshots } from "../components/run/PlayerScreenshots";
import { PluginsTable } from "../components/run/PluginsTable";
import { RunNav } from "../components/run/RunNav";
import { StepTimeline } from "../components/run/StepTimeline";
import { formatDuration } from "../lib/format";
import { supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";

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
      <div className="space-y-4">
        <RunNav runs={manifest.runs} current={id} />
        <UnsupportedRunCard run={run} />
      </div>
    );
  }

  return (
    <div>
      <RunNav runs={manifest.runs} current={id} />
      <header className="mt-4 flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Minecraft {result.minecraft.version}</h1>
        <StatusBadge status={result.status} />
        <span className="text-sm text-zinc-500">
          {run.id} · {formatDuration(result.durationMs)}
        </span>
      </header>
      {result.failure && <FailureBox failure={result.failure} />}

      <Section title="Screenshots" aside={`${result.screenshots.length} total`}>
        <PlayerScreenshots run={run} result={result} />
      </Section>
      <Section title="Steps" aside={`${result.steps.length} steps`}>
        <StepTimeline run={run} steps={result.steps} />
      </Section>
      <Section title="Environment">
        <BuildInfo result={result} />
      </Section>
      <Section title="Plugins">
        <PluginsTable plugins={result.plugins} />
      </Section>
      <Section title="Logs">
        <LogLinks run={run} logs={result.logs} />
      </Section>
    </div>
  );
}
