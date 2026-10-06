import { useMemo, useState } from "react";
import { css } from "styled-system/css";
import { StatusBadge } from "../components/StatusBadge";
import { Button } from "../chlorophyll";
import { StatusGrid } from "../components/overview/StatusGrid";
import { SummaryHeader } from "../components/overview/SummaryHeader";
import { Warnings } from "../components/overview/Warnings";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import type { RunStatus } from "../contract";
import { groupRunsByLabel, sortFailuresFirstAmong, supportedResult, type RunGroup } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { sectionTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
const groups = css({ mt: "3", display: "flex", flexDirection: "column", gap: "6" });
const groupHead = css({ mb: "2", display: "flex", flexWrap: "wrap", alignItems: "center", columnGap: "3", rowGap: "1" });
const groupTitle = css({ fontWeight: "semibold", color: "colorPalette.fg", overflowWrap: "anywhere" });
const groupMeta = css({ fontSize: "sm", color: "fg.muted" });
const toolbar = css({ display: "flex", flexWrap: "wrap", alignItems: "center", justifyContent: "space-between", gap: "2" });

/** 「failures first」の並び順を覚えておく localStorage のキー */
const FAILURES_FIRST_KEY = "fukurou.failuresFirst";

/** localStorage は file:// やプライベートウィンドウで例外を投げることがあるので、読み書きとも失敗は無視する */
function readFailuresFirst(): boolean {
  try {
    return window.localStorage.getItem(FAILURES_FIRST_KEY) === "1";
  } catch {
    return false;
  }
}

function writeFailuresFirst(value: boolean) {
  try {
    window.localStorage.setItem(FAILURES_FIRST_KEY, value ? "1" : "0");
  } catch {
    // 保存できなくても並び替え自体は効く
  }
}

/** run の状態のうち最も悪いもの（error > failed > passed） */
function worstRunStatus(runs: RunGroup["runs"]): RunStatus {
  if (runs.some((run) => run.status === "error")) return "error";
  if (runs.some((run) => run.status === "failed")) return "failed";
  return "passed";
}

/** #/ : ラベルごとに、テスト（行）× バージョン（列）のステータスグリッド */
export function OverviewPage() {
  const manifest = useManifest();
  const { tests, runs } = manifest;
  const [failuresFirst, setFailuresFirst] = useState(readFailuresFirst);
  // fukurou v3 では 1 バージョンで複数の run（JUnit の GameServerExtension ごと、label で区別）ができるので、
  // ラベルごとに表を分け、列はそのラベルが走ったバージョンだけにする。ラベルが 1 種類なら表は 1 つ
  const runGroups = useMemo(() => groupRunsByLabel(runs, tests), [runs, tests]);
  // 読めない run（result 無し / v1）は列としては出すが、理由をカードでも示す
  const unsupported = runs.filter((run) => !supportedResult(run));

  const toggle = () => {
    const next = !failuresFirst;
    setFailuresFirst(next);
    writeFailuresFirst(next);
  };

  return (
    <div className={page}>
      <SummaryHeader manifest={manifest} />
      <Warnings warnings={manifest.warnings} />

      {runs.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>This report contains no runs.</p>
      ) : (
        <section>
          <div className={toolbar}>
            <h2 className={sectionTitle}>Tests × versions</h2>
            <Button
              type="button"
              size="sm"
              intent={failuresFirst ? "primary" : "secondary"}
              aria-pressed={failuresFirst}
              onClick={toggle}
            >
              Failures first
            </Button>
          </div>
          {tests.length === 0 ? (
            <p className={css({ mt: "3", color: "fg.muted" })}>No tests were recorded in any run.</p>
          ) : (
            <div className={groups}>
              {runGroups.map((group, index) => (
                <RunGroupSection
                  key={group.label ?? ""}
                  group={group}
                  index={index}
                  failuresFirst={failuresFirst}
                  showHeading={runGroups.length > 1}
                />
              ))}
            </div>
          )}
        </section>
      )}

      {unsupported.length > 0 && (
        <section className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
          <h2 className={sectionTitle}>Runs without a readable result</h2>
          {unsupported.map((run) => (
            <UnsupportedRunCard key={run.id} run={run} />
          ))}
        </section>
      )}
    </div>
  );
}

interface RunGroupSectionProps {
  group: RunGroup;
  /** 見出しの id に使う通し番号（ラベルは id に使えない文字を含みうる） */
  index: number;
  failuresFirst: boolean;
  /** ラベルが 2 種類以上あるときだけ見出しを出す */
  showHeading: boolean;
}

/** 1 ラベル分のまとまり。見出し（ラベル、run とテストの数、run の状態）と表 */
function RunGroupSection({ group, index, failuresFirst, showHeading }: RunGroupSectionProps) {
  const ordered = useMemo(
    () => (failuresFirst ? sortFailuresFirstAmong(group.tests, group.runs) : group.tests),
    [group, failuresFirst],
  );
  const name = group.label ?? "Unlabelled runs";
  const headingId = `run-group-${index}`;
  const grid =
    group.tests.length === 0 ? (
      <p className={css({ color: "fg.muted" })}>None of these runs has a readable result.</p>
    ) : (
      <StatusGrid group={group} tests={ordered} label={showHeading ? `Tests of ${name} by Minecraft version` : "Tests by Minecraft version"} />
    );
  if (!showHeading) return grid;
  return (
    <section aria-labelledby={headingId}>
      <div className={groupHead}>
        <h3 id={headingId} className={groupTitle}>
          {name}
        </h3>
        <StatusBadge status={worstRunStatus(group.runs)} />
        <span className={groupMeta}>
          {group.runs.length} {group.runs.length === 1 ? "run" : "runs"} · {group.tests.length}{" "}
          {group.tests.length === 1 ? "test" : "tests"}
        </span>
      </div>
      {grid}
    </section>
  );
}
