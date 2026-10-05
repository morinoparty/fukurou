import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { ManifestRun, ManifestTest } from "../../contract";
import {
  passedCountAmong,
  runBadgeStatus,
  runLabel,
  runVersion,
  supportedResult,
  type RunGroup,
} from "../../lib/runs";
import { RunChannelBadge } from "../ChannelBadge";
import { Hint } from "../Hint";
import { StatusBadge } from "../StatusBadge";

// 行見出し（テスト名）の列は横スクロールしても左端に残す。バージョンが多いと列が画面に収まらない。
// Chlorophyll の見出しセルは nowrap なので、ここで折り返しを許す（幅の上限は中の div で決める。表のセルの max-width は効かない）
const stickyStyle = css.raw({
  position: "sticky",
  left: "0",
  zIndex: "1",
  textAlign: "start",
  whiteSpace: "normal",
  // 右側との境目が分かるように影を落とす
  boxShadow: "1px 0 0 0 {colors.border.subtle}",
});
const testHead = css(stickyStyle, { bg: "bg.panel", fontWeight: "normal", verticalAlign: "top" });
// 列見出し行の左端。thead の地色は半透明なので、パネルの白の上に重ねて下を流れる列が透けないようにする
const cornerHead = css(stickyStyle, {
  bg: "bg.panel",
  backgroundImage: "linear-gradient({colors.colorPalette.surface.subtle}, {colors.colorPalette.surface.subtle})",
});
const testBox = css({ width: { base: "11rem", md: "20rem" } });
const testName = css({
  fontWeight: "semibold",
  color: "colorPalette.fg",
  textDecoration: "none",
  lineClamp: "2",
  overflowWrap: "anywhere",
  _hover: { textDecoration: "underline" },
});
const testMeta = css({ mt: "0.5", display: "flex", flexWrap: "wrap", alignItems: "baseline", columnGap: "2", rowGap: "0.5", fontSize: "xs", color: "fg.muted" });
const testId = css({ fontFamily: "mono", overflowWrap: "anywhere", minWidth: "0" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted" });
const versionHead = css({ whiteSpace: "nowrap", textAlign: "center", verticalAlign: "top" });
const versionLink = css({ fontWeight: "semibold", textDecoration: "none", _hover: { textDecoration: "underline" } });
const cell = css({ textAlign: "center", whiteSpace: "nowrap" });
// バッジをリンクにする。下線は付けず、フォーカスリングで示す
const cellLink = css({
  display: "inline-block",
  textDecoration: "none",
  borderRadius: "full",
  _hover: { textDecoration: "none", opacity: 0.85 },
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "colorPalette.focus.ring", outlineOffset: "2px" },
});
const unavailable = css({ color: "fg.muted", cursor: "help" });

interface StatusGridProps {
  /** 1 ラベル分の run とテスト */
  group: RunGroup;
  /** 表示順に並べたこのまとまりのテスト */
  tests: ManifestTest[];
  /** スクロール領域の名前（表が複数あるときに区別できるように） */
  label: string;
}

/**
 * テスト（行）× バージョン（列）のステータスグリッド。1 ラベル分の run だけを列にする。
 * セルは test-run のページ、行見出しはテストのページ、列見出しは run のページへのリンク
 */
export function StatusGrid({ group, tests, label }: StatusGridProps) {
  const { runs } = group;
  const headings = columnHeadings(runs);
  return (
    <Table.Root size="sm" scrollAreaLabel={label}>
      <Table.Header>
        <Table.Row>
          <Table.Head className={cornerHead}>Test</Table.Head>
          {runs.map((run) => (
            <Table.Head key={run.id} className={versionHead}>
              <div className={css({ display: "flex", flexDirection: "column", alignItems: "center", gap: "1" })}>
                <Link to="/runs/$runId" params={{ runId: run.id }} className={versionLink} title={`${runLabel(run)} (${run.id})`}>
                  {headings.get(run.id)}
                </Link>
                <RunChannelBadge run={run} />
                <StatusBadge status={runBadgeStatus(run)} />
              </div>
            </Table.Head>
          ))}
        </Table.Row>
      </Table.Header>
      <Table.Body>
        {tests.map((test) => (
          <TestRow key={test.id} test={test} runs={runs} />
        ))}
      </Table.Body>
    </Table.Root>
  );
}

/** 列見出しの文字。基本はバージョンだけにし、同じバージョンの run が 2 つ以上あるときだけ run id で区別する */
function columnHeadings(runs: ManifestRun[]): Map<string, string> {
  const versions = runs.map(runVersion);
  return new Map(
    runs.map((run, index) => {
      const version = versions[index] ?? run.id;
      const duplicated = versions.filter((candidate) => candidate === version).length > 1;
      return [run.id, duplicated ? run.id : version];
    }),
  );
}

interface TestRowProps {
  test: ManifestTest;
  runs: ManifestRun[];
}

/** 1 テストの行。名前（2 行まで）と id・"5/6"・タグ、それからバージョンごとの状態 */
function TestRow({ test, runs }: TestRowProps) {
  const count = passedCountAmong(test, runs);
  const hasName = Boolean(test.name) && test.name !== test.id;
  return (
    <Table.Row>
      <Table.Head scope="row" className={testHead}>
        <div className={testBox}>
          <Link to="/tests/$testId" params={{ testId: test.id }} className={testName} title={hasName ? `${test.name} (${test.id})` : test.id}>
            {hasName ? test.name : test.id}
          </Link>
          <div className={testMeta}>
            {hasName && <span className={testId}>{test.id}</span>}
            <span className={css({ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" })}>
              {count.passed}/{count.total} passed
            </span>
            {test.tags.map((name) => (
              <span key={name} className={tag}>
                {name}
              </span>
            ))}
          </div>
        </div>
      </Table.Head>
      {runs.map((run) => (
        <Table.Cell key={run.id} className={cell}>
          <Cell test={test} run={run} />
        </Table.Cell>
      ))}
    </Table.Row>
  );
}

interface CellProps {
  test: ManifestTest;
  run: ManifestRun;
}

/**
 * 1 マス。cells にある run は test-run ページへのリンク付きのバッジ、
 * result が読めない run は "–"、それ以外（フィルタなどでその run に含まれなかった）は "not run"
 */
function Cell({ test, run }: CellProps) {
  const status = test.cells[run.id];
  if (status) {
    return (
      <Link
        to="/runs/$runId/tests/$testId"
        params={{ runId: run.id, testId: test.id }}
        className={cellLink}
        aria-label={`${test.id} on ${runLabel(run)}: ${status}`}
      >
        <StatusBadge status={status} />
      </Link>
    );
  }
  if (!supportedResult(run)) {
    return (
      <Hint content="This run has no readable result.json">
        <span className={unavailable} tabIndex={0} aria-label="result unavailable">
          –
        </span>
      </Hint>
    );
  }
  return <StatusBadge status="not-run" />;
}
