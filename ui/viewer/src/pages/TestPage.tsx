import { Link, useParams } from "@tanstack/react-router";
import type { CSSProperties } from "react";
import { css } from "styled-system/css";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { ShotLinks } from "../components/overview/ShotLinks";
import { runGridStyle } from "../components/overview/grid";
import { StatusBadge } from "../components/StatusBadge";
import { VersionRow } from "../components/test/VersionRow";
import type { ManifestRun, ManifestTest } from "../contract";
import { passedCount, runGroupLabel, runLabel, runPlayersOf, runsWithTest, supportedResult, testLabel } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { eyebrowStyle, pageTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
const header = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted", fontSize: "xs" });
// 列見出し。行と同じグリッドにしてプレイヤー列の位置を揃える。縦積みになる狭い画面では隠す
const notRunLine = css({ fontSize: "sm", color: "fg.muted", overflowWrap: "anywhere" });
const columnHeads = css(runGridStyle, eyebrowStyle, { display: "none", px: "3", md: { display: "grid" } });

/** #/tests/<testId> : 1 テストのバージョン（行）× プレイヤー（列）のスクリーンショット一覧 */
export function TestPage() {
  const { testId } = useParams({ from: "/tests/$testId" });
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

  const count = passedCount(test);
  // そのテストを含む run だけを行にする。含まない run は、同じラベル（同じサーバー定義）のものだけ 1 行にまとめて示す
  const runs = runsWithTest(manifest.runs, test.id);
  const missing = missingRuns(manifest.runs, runs);
  // 行ごとに、その run でテストに居たプレイヤーだけを並べる（v3 の複数サーバーのテストではサーバーごとにプレイヤーが違う）。
  // 全 run で顔ぶれが同じなら列をそろえて上に見出し行を出し、違うならカードの中でプレイヤー名を示す
  const rows = runs.map((run) => ({ run, players: runPlayersOf(run, test.id, test.players) }));
  const first = rows[0]?.players ?? [];
  const uniform = rows.every((row) => row.players.join("\n") === first.join("\n"));
  // 列数は CSS 変数で渡す（Panda のクラスは静的なので、実行時に決まる値はここで入れる）
  const gridStyle = { "--players": Math.max(first.length, 1) } as CSSProperties;

  return (
    <div className={page} style={gridStyle}>
      <PageNav
        crumbs={[{ key: "test", label: testLabel(test) }]}
        previous={neighbour(manifest.tests, test, -1)}
        next={neighbour(manifest.tests, test, 1)}
      />
      <header>
        <div className={header}>
          <h1 className={pageTitle}>{testLabel(test)}</h1>
          <StatusBadge status={test.status} size="md" />
          <span className={css({ color: "fg.muted", fontVariantNumeric: "tabular-nums" })}>
            {count.passed}/{count.total} {count.total === 1 ? "run" : "runs"} passed
          </span>
        </div>
        {test.tags.length > 0 && (
          <div className={css({ mt: "2", display: "flex", flexWrap: "wrap", gap: "1" })}>
            {test.tags.map((name) => (
              <span key={name} className={tag}>
                {name}
              </span>
            ))}
          </div>
        )}
      </header>
      <ShotLinks testId={test.id} shots={test.shots} />

      {runs.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>No run in this report has a readable result for this test.</p>
      ) : (
        <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
          {uniform && first.length > 0 && (
            <div className={columnHeads}>
              <span>Run</span>
              {first.map((player) => (
                <span key={player} className={css({ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" })} title={player}>
                  {player}
                </span>
              ))}
            </div>
          )}
          {rows.map(({ run, players }) => (
            <VersionRow key={run.id} run={run} testId={test.id} players={players} aligned={uniform} />
          ))}
        </div>
      )}
      {missing.length > 0 && (
        <p className={notRunLine}>
          Not run in:{" "}
          {missing.map((run, index) => (
            <span key={run.id}>
              {index > 0 && " · "}
              <Link to="/runs/$runId" params={{ runId: run.id }}>
                {runLabel(run)}
              </Link>
              {!supportedResult(run) && " (result unavailable)"}
            </span>
          ))}
        </p>
      )}
    </div>
  );
}

/**
 * テストを含まない run のうち、テストを含む run と同じラベルのもの（フィルタやバージョン指定で外れたもの）。
 * 別のラベルの run は元々別のテストクラスなので並べない。result が読めない run はラベルが分かれば同じ扱い、分からなければ含める
 */
function missingRuns(all: ManifestRun[], withTest: ManifestRun[]): ManifestRun[] {
  const labels = new Set(withTest.map(runGroupLabel));
  return all.filter((run) => !withTest.includes(run) && (labels.has(runGroupLabel(run)) || (!supportedResult(run) && !run.label)));
}

/** スイート順で前後のテストへのリンク */
function neighbour(tests: ManifestTest[], current: ManifestTest, offset: -1 | 1) {
  const index = tests.indexOf(current);
  const target = index >= 0 ? tests[index + offset] : undefined;
  if (!target) return undefined;
  return (
    <Link to="/tests/$testId" params={{ testId: target.id }}>
      {offset < 0 ? `‹ ${target.id}` : `${target.id} ›`}
    </Link>
  );
}
