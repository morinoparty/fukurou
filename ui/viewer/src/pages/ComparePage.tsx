import { Link, useParams } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { ShotLinks } from "../components/overview/ShotLinks";
import { ScreenshotThumb } from "../components/ScreenshotThumb";
import { StatusBadge } from "../components/StatusBadge";
import type { ManifestRun } from "../contract";
import { findScreenshot, findTest, runBadgeStatus, runLabel, supportedResult, testLabel } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { emptyBoxStyle, pageTitle, panelStyle, sectionTitleStyle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
const grid = css({
  display: "grid",
  gap: "4",
  gridTemplateColumns: {
    base: "minmax(0, 1fr)",
    sm: "repeat(2, minmax(0, 1fr))",
    lg: "repeat(3, minmax(0, 1fr))",
    xl: "repeat(4, minmax(0, 1fr))",
  },
});
const cell = css(panelStyle, { minWidth: "0", p: "2" });
const cellHeader = css({ mb: "2", display: "flex", alignItems: "center", gap: "2" });
const playerHeading = css(sectionTitleStyle, { mb: "3" });
const missing = css(emptyBoxStyle, { aspectRatio: "16 / 9", display: "flex", alignItems: "center", justifyContent: "center" });

/** #/tests/<testId>/compare/<shot> : 1 テストの同じ名前のスクリーンショットを、プレイヤーごとに全バージョン横並びで比べる */
export function ComparePage() {
  const { testId, shot } = useParams({ from: "/tests/$testId/compare/$shot" });
  const manifest = useManifest();
  const test = manifest.tests.find((candidate) => candidate.id === testId);

  if (!test) {
    return (
      <div className={page}>
        <PageNav crumbs={[{ key: "test", label: testId }]} />
        <Message title={`Test "${testId}" is not in this report`} />
      </div>
    );
  }

  const players = test.players;
  const crumbs = [
    {
      key: "test",
      label: testLabel(test),
      link: (
        <Link to="/tests/$testId" params={{ testId: test.id }} activeOptions={{ exact: true }}>
          {testLabel(test)}
        </Link>
      ),
    },
    { key: "compare", label: `Compare ${shot}` },
  ];

  return (
    <div className={page}>
      <PageNav crumbs={crumbs} />
      <h1 className={pageTitle}>
        Compare <code>{shot}</code>
      </h1>
      <ShotLinks testId={test.id} shots={test.shots} current={shot} />
      {players.length === 0 && <p className={css({ color: "fg.muted" })}>This test has no players.</p>}
      {players.map((player) => (
        <section key={player}>
          <h2 className={playerHeading}>{player}</h2>
          <div className={grid}>
            {manifest.runs.map((run) => (
              <CompareCell key={run.id} run={run} testId={test.id} player={player} shot={shot} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

interface CompareCellProps {
  run: ManifestRun;
  testId: string;
  player: string;
  shot: string;
}

/** 比較の1枚分。スクリーンショットが無い run も、欠けていることが分かるように枠だけ出す */
function CompareCell({ run, testId, player, shot }: CompareCellProps) {
  const result = supportedResult(run);
  const test = result ? findTest(result, testId) : undefined;
  const screenshot = test ? findScreenshot(test, player, shot) : undefined;
  const version = runLabel(run);
  return (
    <div className={cell}>
      <div className={cellHeader}>
        {test ? (
          <Link to="/runs/$runId/tests/$testId" params={{ runId: run.id, testId }} className={css({ fontWeight: "semibold" })}>
            {version}
          </Link>
        ) : (
          <Link to="/runs/$runId" params={{ runId: run.id }} className={css({ fontWeight: "semibold" })}>
            {version}
          </Link>
        )}
        <StatusBadge status={test ? test.status : result ? "not-run" : runBadgeStatus(run)} />
      </div>
      {screenshot ? (
        <ScreenshotThumb run={run} testId={testId} shot={screenshot} caption={`${player} · ${version}`} />
      ) : (
        <div className={missing}>{missingReason(result !== null, test)}</div>
      )}
    </div>
  );
}

/** 画像が無い理由 */
function missingReason(hasResult: boolean, test: ReturnType<typeof findTest>): string {
  if (!hasResult) return "Result unavailable";
  if (!test) return "Not run";
  if (test.status === "skipped") return "Skipped";
  return "No screenshot";
}
