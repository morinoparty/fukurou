import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { ManifestRun, ResultV2, TestResult } from "../../contract";
import { findLogIndexByPath, logLabels } from "../../lib/logs";
import { flatLogs } from "../../lib/runs";
import { buttonLink } from "../../styles";

const list = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" });
const plain = css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" });

interface LogRangeLinksProps {
  run: ManifestRun;
  result: ResultV2;
  test: TestResult;
}

/**
 * テストの logRanges（ログのパス → 行範囲）から、その範囲へスクロールしたログビューアへのリンクを並べる。
 * パスが run のログに見つからないときはリンクにせず文字だけ出す
 */
export function LogRangeLinks({ run, result, test }: LogRangeLinksProps) {
  const ranges = test.logRanges ? Object.entries(test.logRanges) : [];
  if (ranges.length === 0) {
    return (
      <p className={css({ color: "fg.muted" })}>
        {test.status === "skipped" ? "This test did not run, so it has no log range." : "No log ranges were recorded."}
      </p>
    );
  }
  const logs = flatLogs(result);
  const labels = logLabels(logs);
  return (
    <div className={list}>
      {ranges.map(([path, range]) => {
        const index = findLogIndexByPath(logs, path);
        const text = `lines ${range.from}–${range.to}`;
        if (index < 0) {
          return (
            <span key={path} className={plain}>
              {path}: {text}
            </span>
          );
        }
        return (
          <Button key={path} asChild size="sm" intent="secondary" className={buttonLink}>
            <Link
              to="/runs/$runId/logs/$logIndex"
              params={{ runId: run.id, logIndex: String(index) }}
              search={{ from: range.from, to: range.to }}
              title={path}
            >
              {labels[index]} · {text}
            </Link>
          </Button>
        );
      })}
    </div>
  );
}
