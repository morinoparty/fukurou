import { Link, useParams } from "@tanstack/react-router";
import type { CSSProperties } from "react";
import { css } from "styled-system/css";
import { Message } from "../components/Message";
import { PageNav } from "../components/PageNav";
import { ShotLinks } from "../components/overview/ShotLinks";
import { runGridStyle } from "../components/overview/grid";
import { StatusBadge } from "../components/StatusBadge";
import { VersionRow } from "../components/test/VersionRow";
import type { ManifestTest } from "../contract";
import { passedCount, testLabel } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { eyebrowStyle, pageTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
const header = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "3" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted", fontSize: "xs" });
// 列見出し。行と同じグリッドにしてプレイヤー列の位置を揃える。縦積みになる狭い画面では隠す
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

  const players = test.players;
  // 列数は CSS 変数で渡す（Panda のクラスは静的なので、実行時に決まる値はここで入れる）
  const gridStyle = { "--players": Math.max(players.length, 1) } as CSSProperties;
  const count = passedCount(test);

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
            {count.passed}/{count.total} versions passed
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

      {manifest.runs.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>This report contains no runs.</p>
      ) : (
        <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
          <div className={columnHeads}>
            <span>Minecraft</span>
            {players.map((player) => (
              <span key={player} className={css({ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" })}>
                {player}
              </span>
            ))}
          </div>
          {manifest.runs.map((run) => (
            <VersionRow key={run.id} run={run} testId={test.id} players={players} />
          ))}
        </div>
      )}
    </div>
  );
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
