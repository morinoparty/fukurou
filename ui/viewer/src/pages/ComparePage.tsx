import { Link, useParams } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Breadcrumb } from "../chlorophyll";
import { ShotLinks } from "../components/overview/ShotLinks";
import { ScreenshotThumb } from "../components/ScreenshotThumb";
import { StatusBadge } from "../components/StatusBadge";
import type { ManifestRun } from "../contract";
import { findScreenshot, runBadgeStatus, supportedResult } from "../lib/runs";
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

/** #/compare/<shot> : 同じ名前のスクリーンショットを、プレイヤーごとに全バージョン横並びで比べる */
export function ComparePage() {
  const { shot } = useParams({ from: "/compare/$shot" });
  const manifest = useManifest();
  const players = manifest.players;

  return (
    <div className={page}>
      <Breadcrumb.Root>
        <Breadcrumb.List>
          <Breadcrumb.Item>
            <Breadcrumb.Link asChild>
              <Link to="/" activeOptions={{ exact: true }}>
                All runs
              </Link>
            </Breadcrumb.Link>
          </Breadcrumb.Item>
          <Breadcrumb.Separator />
          <Breadcrumb.Item>
            <Breadcrumb.Page>Compare {shot}</Breadcrumb.Page>
          </Breadcrumb.Item>
        </Breadcrumb.List>
      </Breadcrumb.Root>
      <h1 className={pageTitle}>
        Compare <code>{shot}</code>
      </h1>
      <ShotLinks shots={manifest.shots} current={shot} />
      {players.length === 0 && <p className={css({ color: "fg.muted" })}>This report has no players.</p>}
      {players.map((player) => (
        <section key={player}>
          <h2 className={playerHeading}>{player}</h2>
          <div className={grid}>
            {manifest.runs.map((run) => (
              <CompareCell key={run.id} run={run} player={player} shot={shot} />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

interface CompareCellProps {
  run: ManifestRun;
  player: string;
  shot: string;
}

/** 比較の1枚分。スクリーンショットが無い run も、欠けていることが分かるように枠だけ出す */
function CompareCell({ run, player, shot }: CompareCellProps) {
  const result = supportedResult(run);
  const screenshot = result ? findScreenshot(result, player, shot) : undefined;
  return (
    <div className={cell}>
      <div className={cellHeader}>
        <Link to="/runs/$id" params={{ id: run.id }} className={css({ fontWeight: "semibold" })}>
          {result?.minecraft.version ?? run.id}
        </Link>
        <StatusBadge status={runBadgeStatus(run)} />
      </div>
      {screenshot ? (
        <ScreenshotThumb run={run} shot={screenshot} caption={`${player} · ${result?.minecraft.version ?? run.id}`} />
      ) : (
        <div className={missing}>{result ? "No screenshot" : "Result unavailable"}</div>
      )}
    </div>
  );
}
