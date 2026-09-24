import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { ManifestRun, ManifestTest } from "../../contract";
import { passedCount, runBadgeStatus, runLabel, supportedResult, testLabel } from "../../lib/runs";
import { RunChannelBadge } from "../ChannelBadge";
import { Hint } from "../Hint";
import { StatusBadge } from "../StatusBadge";

// 行見出し（テスト名）の列は横スクロールしても左端に残す。バージョンが多いとスマホでは列が画面に収まらない
const testHead = css({
  position: "sticky",
  left: "0",
  zIndex: "1",
  bg: "bg.panel",
  minWidth: "11rem",
  maxWidth: "16rem",
  textAlign: "start",
  fontWeight: "normal",
  // 右側との境目が分かるように影を落とす
  boxShadow: "1px 0 0 0 {colors.border.subtle}",
});
const testName = css({
  fontWeight: "semibold",
  color: "colorPalette.fg",
  textDecoration: "none",
  _hover: { textDecoration: "underline" },
});
const testMeta = css({ mt: "0.5", display: "flex", flexWrap: "wrap", gap: "1", fontSize: "xs", color: "fg.muted" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted" });
const versionHead = css({ whiteSpace: "nowrap", textAlign: "center" });
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
  /** 表示順に並べたテスト */
  tests: ManifestTest[];
  runs: ManifestRun[];
}

/**
 * テスト（行）× バージョン（列）のステータスグリッド。
 * セルは test-run のページ、行見出しはテストのページ、列見出しは run のページへのリンク
 */
export function StatusGrid({ tests, runs }: StatusGridProps) {
  return (
    <Table.Root size="sm" scrollAreaLabel="Tests by Minecraft version">
      <Table.Header>
        <Table.Row>
          <Table.Head className={testHead}>Test</Table.Head>
          {runs.map((run) => (
            <Table.Head key={run.id} className={versionHead}>
              <div className={css({ display: "flex", flexDirection: "column", alignItems: "center", gap: "1" })}>
                <Link to="/runs/$runId" params={{ runId: run.id }} className={versionLink} title={run.id}>
                  {runLabel(run)}
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

interface TestRowProps {
  test: ManifestTest;
  runs: ManifestRun[];
}

/** 1 テストの行。名前とタグと "5/6"、それからバージョンごとの状態 */
function TestRow({ test, runs }: TestRowProps) {
  const count = passedCount(test);
  return (
    <Table.Row>
      <Table.Head scope="row" className={testHead}>
        <Link to="/tests/$testId" params={{ testId: test.id }} className={testName}>
          {testLabel(test)}
        </Link>
        <div className={testMeta}>
          <span className={css({ fontVariantNumeric: "tabular-nums" })}>
            {count.passed}/{count.total} passed
          </span>
          {test.tags.map((name) => (
            <span key={name} className={tag}>
              {name}
            </span>
          ))}
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
      <Hint content="This version has no readable result.json">
        <span className={unavailable} tabIndex={0} aria-label="result unavailable">
          –
        </span>
      </Hint>
    );
  }
  return <StatusBadge status="not-run" />;
}
