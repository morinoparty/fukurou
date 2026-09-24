import { Link, useParams } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../chlorophyll";
import { ChannelBadge } from "../components/ChannelBadge";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { Section } from "../components/Section";
import { StatusBadge } from "../components/StatusBadge";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import { FailureBox } from "../components/run/FailureBox";
import { LogRangeLinks } from "../components/run/LogRangeLinks";
import { PlayerScreenshots } from "../components/run/PlayerScreenshots";
import { runCrumb, testRunCrumb, testRunNeighbour } from "../components/run/runNav";
import { StepTimeline } from "../components/run/StepTimeline";
import { formatDateTime, formatDuration, shortHash } from "../lib/format";
import { findTest, supportedResult, testLabel } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { buttonLinkStyle, mutedText, pageTitle, panelStyle } from "../styles";

const header = css({ mt: "4", display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });
const facts = css(panelStyle, {
  mt: "4",
  px: "4",
  py: "3",
  display: "flex",
  flexWrap: "wrap",
  columnGap: "5",
  rowGap: "1",
  fontSize: "sm",
});
const fact = css({ display: "inline-flex", gap: "1.5", alignItems: "baseline" });
const factLabel = css({ fontSize: "xs", color: "fg.muted", textTransform: "uppercase", letterSpacing: "wide" });
const skippedBox = css(panelStyle, { mt: "6", p: "4", borderStyle: "dashed", color: "fg.muted" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted", fontSize: "xs" });
const compareButton = css(buttonLinkStyle, { ml: { md: "auto" } });

/** #/runs/<runId>/tests/<testId> : 1 バージョンでの 1 テストの詳細 */
export function TestRunPage() {
  const { runId, testId } = useParams({ from: "/runs/$runId/tests/$testId" });
  const manifest = useManifest();
  const run = manifest.runs.find((candidate) => candidate.id === runId);
  const manifestTest = manifest.tests.find((candidate) => candidate.id === testId);
  const result = run ? supportedResult(run) : null;
  const test = result ? findTest(result, testId) : undefined;

  // prev/next は同じテストの隣のバージョン
  const nav = (
    <PageNav
      crumbs={[runCrumb(run, runId, true), testRunCrumb(run, manifestTest, testId)]}
      previous={testRunNeighbour(manifest.runs, runId, testId, -1)}
      next={testRunNeighbour(manifest.runs, runId, testId, 1)}
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
  if (!result) {
    return (
      <div className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
        {nav}
        <UnsupportedRunCard run={run} />
      </div>
    );
  }
  if (!test) {
    return (
      <>
        {nav}
        <Message title={`${testId} was not run on Minecraft ${result.minecraft.version}`}>
          <p>This version's run does not contain the test (it may have been filtered out with tests / tags inputs).</p>
          <p>
            <Link to="/runs/$runId" params={{ runId }}>
              Back to the run
            </Link>
            {manifestTest && (
              <>
                {" · "}
                <Link to="/tests/$testId" params={{ testId }}>
                  All versions of this test
                </Link>
              </>
            )}
          </p>
        </Message>
      </>
    );
  }

  return (
    <div>
      {nav}
      <header className={header}>
        <h1 className={pageTitle}>{testLabel(test)}</h1>
        <StatusBadge status={test.status} size="md" />
        <span className={css({ color: "fg.muted" })}>
          Minecraft {result.minecraft.version} · {formatDuration(test.durationMs)}
        </span>
        <ChannelBadge channel={result.minecraft.channel} />
        {test.tags.map((name) => (
          <span key={name} className={tag}>
            {name}
          </span>
        ))}
        {manifestTest && (
          <Button asChild size="sm" intent="secondary" className={compareButton}>
            <Link to="/tests/$testId" params={{ testId: test.id }}>
              Compare versions
            </Link>
          </Button>
        )}
      </header>

      {test.failure && <FailureBox failure={test.failure} />}
      {test.status === "skipped" && (
        <div className={skippedBox} role="status">
          <p className={css({ fontWeight: "semibold", color: "fg" })}>This test was skipped</p>
          <p className={css({ mt: "1" })}>{test.skipReason ?? "No reason was recorded."}</p>
        </div>
      )}

      <dl className={facts}>
        <Fact label="Order">{test.order}</Fact>
        <Fact label="Isolation">{test.isolation}</Fact>
        <Fact label="Session">{test.session ?? "–"}</Fact>
        <Fact label="Timeout">{test.timeout}s</Fact>
        {test.versions && <Fact label="Versions">{test.versions}</Fact>}
        <Fact label="Players">
          {test.players.length > 0 ? test.players.map((player) => `${player.name}${player.op ? " (op)" : ""}`).join(", ") : "–"}
        </Fact>
        <Fact label="Started">{formatDateTime(test.startedAt)}</Fact>
        <Fact label="Source">
          <span className={css({ wordBreak: "break-all" })}>
            {test.source} <span className={mutedText}>sha256 {shortHash(test.sha256, 12)}</span>
          </span>
        </Fact>
      </dl>

      <Section title="Screenshots" aside={`${test.screenshots.length} total`}>
        <PlayerScreenshots run={run} result={result} test={test} />
      </Section>
      <Section title="Steps" aside={`${test.steps.length} steps`}>
        {/* 同じテストの別バージョンへ移ってもルートのコンポーネントは再利用されるので、
            key で作り直して折りたたみ状態を前のバージョンから持ち越さない */}
        <StepTimeline key={`${run.id}/${test.id}`} run={run} test={test} />
      </Section>
      <Section title="Logs">
        <LogRangeLinks run={run} result={result} test={test} />
      </Section>
    </div>
  );
}

/** 見出し下の定義リストの 1 項目 */
function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={fact}>
      <dt className={factLabel}>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
