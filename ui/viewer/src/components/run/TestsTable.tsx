import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { ManifestRun, TestResult } from "../../contract";
import { formatDuration } from "../../lib/format";
import { testLabel } from "../../lib/runs";
import { Hint } from "../Hint";
import { StatusBadge } from "../StatusBadge";

// 失敗したテストは行ごと赤く、実行しなかったテストは文字を薄くする
const failedRow = css({ bg: "bg.error", _hover: { bg: "bg.error" } });
const skippedRow = css({ color: "fg.muted" });
const numberCell = css({ fontVariantNumeric: "tabular-nums", color: "fg.muted", width: "1%" });
const durationCell = css({ fontVariantNumeric: "tabular-nums", textAlign: "end", whiteSpace: "nowrap" });
const name = css({ fontWeight: "semibold" });
const meta = css({ mt: "0.5", fontSize: "xs", color: "fg.muted", display: "flex", flexWrap: "wrap", gap: "1" });
const tag = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted" });
const failure = css({
  fontSize: "xs",
  color: "fg.error",
  lineClamp: "2",
  wordBreak: "break-word",
  cursor: "help",
  minWidth: "12rem",
  maxWidth: "28rem",
});

interface TestsTableProps {
  run: ManifestRun;
  tests: TestResult[];
}

/** run のテストを実行順に並べた表。各行はその test-run のページへ */
export function TestsTable({ run, tests }: TestsTableProps) {
  if (tests.length === 0) return <p className={css({ color: "fg.muted" })}>No tests were selected for this run.</p>;

  return (
    <Table.Root size="sm" scrollAreaLabel="Tests of this run">
      <Table.Header>
        <Table.Row>
          <Table.Head>#</Table.Head>
          <Table.Head>Test</Table.Head>
          <Table.Head>Status</Table.Head>
          <Table.Head className={css({ textAlign: "end" })}>Duration</Table.Head>
          <Table.Head>Failure</Table.Head>
        </Table.Row>
      </Table.Header>
      <Table.Body>
        {tests.map((test) => {
          const rowClass =
            test.status === "failed" || test.status === "error" ? failedRow : test.status === "skipped" ? skippedRow : undefined;
          return (
            <Table.Row key={test.id} className={rowClass}>
              <Table.Cell className={numberCell}>{test.order}</Table.Cell>
              <Table.Cell className={css({ minWidth: "12rem" })}>
                <Link to="/runs/$runId/tests/$testId" params={{ runId: run.id, testId: test.id }} className={name}>
                  {testLabel(test)}
                </Link>
                <div className={meta}>
                  <span>{test.isolation}</span>
                  {test.session !== null && <span>· session {test.session}</span>}
                  {test.tags.map((tagName) => (
                    <span key={tagName} className={tag}>
                      {tagName}
                    </span>
                  ))}
                </div>
              </Table.Cell>
              <Table.Cell>
                <StatusBadge status={test.status} />
              </Table.Cell>
              <Table.Cell className={durationCell}>{formatDuration(test.durationMs)}</Table.Cell>
              <Table.Cell>
                {test.failure ? (
                  // 2行で切った失敗メッセージの全文はツールチップで見せる
                  <Hint content={`${test.failure.phase}: ${test.failure.message}`}>
                    <p className={failure} tabIndex={0}>
                      {test.failure.phase}: {test.failure.message}
                    </p>
                  </Hint>
                ) : test.status === "skipped" ? (
                  <span className={css({ fontSize: "xs", color: "fg.muted" })}>{test.skipReason ?? "skipped"}</span>
                ) : (
                  "–"
                )}
              </Table.Cell>
            </Table.Row>
          );
        })}
      </Table.Body>
    </Table.Root>
  );
}
