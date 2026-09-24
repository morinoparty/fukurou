import { Link } from "@tanstack/react-router";
import type { ManifestRun, ManifestTest } from "../../contract";
import { findTest, runLabel, supportedResult, testLabel } from "../../lib/runs";
import type { Crumb } from "../PageNav";

/** 前後のバージョンの run ページへのリンク */
export function runNeighbour(runs: ManifestRun[], current: string, offset: -1 | 1) {
  const index = runs.findIndex((run) => run.id === current);
  const target = index >= 0 ? runs[index + offset] : undefined;
  if (!target) return undefined;
  return (
    <Link to="/runs/$runId" params={{ runId: target.id }}>
      {offset < 0 ? `‹ ${runLabel(target)}` : `${runLabel(target)} ›`}
    </Link>
  );
}

/**
 * 同じテストの前後のバージョンへのリンク（test-run ページ用）。
 * 隣のバージョンにそのテストが無ければ、その run のページへ飛ぶ（ページ側で「not run」を示す）
 */
export function testRunNeighbour(runs: ManifestRun[], current: string, testId: string, offset: -1 | 1) {
  const index = runs.findIndex((run) => run.id === current);
  const target = index >= 0 ? runs[index + offset] : undefined;
  if (!target) return undefined;
  const label = offset < 0 ? `‹ ${runLabel(target)}` : `${runLabel(target)} ›`;
  return (
    <Link to="/runs/$runId/tests/$testId" params={{ runId: target.id, testId }}>
      {label}
    </Link>
  );
}

/** run ページのパンくず（Overview › Minecraft X）。link が true なら途中の段としてリンクにする */
export function runCrumb(run: ManifestRun | undefined, runId: string, link: boolean): Crumb {
  // result が読めない run はバージョンが分からないので run id のまま
  const label = run && supportedResult(run) ? `Minecraft ${runLabel(run)}` : runId;
  return {
    key: "run",
    label,
    link: link ? (
      <Link to="/runs/$runId" params={{ runId }} activeOptions={{ exact: true }}>
        {label}
      </Link>
    ) : undefined,
  };
}

/** test-run ページのパンくずの末尾（テスト名）。manifest のテスト名があればそれ、無ければ run の中の名前か id */
export function testRunCrumb(run: ManifestRun | undefined, manifestTest: ManifestTest | undefined, testId: string): Crumb {
  const result = run ? supportedResult(run) : null;
  const inRun = result ? findTest(result, testId) : undefined;
  const label = manifestTest ? testLabel(manifestTest) : inRun ? testLabel(inRun) : testId;
  return { key: "test", label };
}
