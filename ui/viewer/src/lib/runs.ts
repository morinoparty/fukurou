import type { BadgeStatus } from "../components/StatusBadge";
import type { LogInfo, ManifestRun, ManifestTest, ResultV2, ScreenshotInfo, TestResult, TestStatus } from "../contract";

/** 失敗時に撮るスクリーンショットの名前（契約で固定） */
export const FAILURE_SHOT = "failure";

/** 表示できる result の schemaVersion */
const SUPPORTED_RESULT_VERSION = 2;

/**
 * このビューアで表示できる result（schemaVersion 2）なら返す。
 * result が無い run や、別の schemaVersion の run は null を返し、呼び出し側で専用カードを出す。
 */
export function supportedResult(run: ManifestRun): ResultV2 | null {
  const result = run.result;
  if (typeof result !== "object" || result === null) return null;
  if (result.schemaVersion !== SUPPORTED_RESULT_VERSION) return null;
  const v2 = result as ResultV2;
  // pydantic は既定値も書き出すが、手で作られた JSON でも落ちないように配列だけは補う
  return {
    ...v2,
    plugins: v2.plugins ?? [],
    players: v2.players ?? [],
    sessions: (v2.sessions ?? []).map((session) => ({
      ...session,
      players: session.players ?? [],
      tests: session.tests ?? [],
      logs: session.logs ?? [],
    })),
    tests: (v2.tests ?? []).map((test) => ({
      ...test,
      tags: test.tags ?? [],
      players: test.players ?? [],
      steps: test.steps ?? [],
      screenshots: test.screenshots ?? [],
    })),
    logs: v2.logs ?? [],
  };
}

/** 表示できない run の理由を短い英文で返す */
export function unsupportedReason(run: ManifestRun): string {
  if (run.result === null || typeof run.result !== "object") {
    return "No result.json was found for this run (the job may have been cancelled or failed before writing it).";
  }
  const version = run.result.schemaVersion;
  if (typeof version === "number" && version < SUPPORTED_RESULT_VERSION) {
    return `This run was produced by fukurou v1 (result schemaVersion ${String(version)}), which this viewer does not read. Run the tests with fukurou v2, or keep using fukurou/ui v1 for old artifacts.`;
  }
  return `This run uses result schemaVersion ${String(version)}, which this viewer does not support. Use a newer fukurou/ui.`;
}

/** run の表示名。result があれば Minecraft のバージョン、無ければ run id */
export function runLabel(run: ManifestRun): string {
  return supportedResult(run)?.minecraft.version ?? run.id;
}

/** run の中の 1 テストを id で探す */
export function findTest(result: ResultV2, id: string): TestResult | undefined {
  return result.tests.find((test) => test.id === id);
}

/** テストの表示名。name が id と違うときだけ両方を見せる */
export function testLabel(test: Pick<TestResult, "id" | "name"> | Pick<ManifestTest, "id" | "name">): string {
  return test.name && test.name !== test.id ? `${test.name} (${test.id})` : test.id;
}

/** ビューアで扱うログ。どのセッションのものかを添える（run 全体のログは null） */
export interface RunLog extends LogInfo {
  session: number | null;
}

/**
 * run のログを 1 本の配列にする（[...result.logs, ...sessions.flatMap(s => s.logs)]）。
 * ログビューアの URL の logIndex はこの配列の添字（契約 §6）
 */
export function flatLogs(result: ResultV2): RunLog[] {
  return [
    ...result.logs.map((log) => ({ ...log, session: null })),
    ...result.sessions.flatMap((session) => session.logs.map((log) => ({ ...log, session: session.index }))),
  ];
}

/** 指定したプレイヤー・名前のスクリーンショットをテストの中から探す */
export function findScreenshot(test: TestResult, player: string, name: string): ScreenshotInfo | undefined {
  return test.screenshots.find((shot) => shot.player === player && shot.name === name);
}

/**
 * テストの中のプレイヤーのスクリーンショットを撮影順（stepIndex 順、failure は最後）に並べる。
 * repeat は計画時に展開され stepIndex は計画順なので、repeat の中で撮ったものは回の順（iteration 1, 2, ...）になる
 */
export function screenshotsOf(test: TestResult, player: string): ScreenshotInfo[] {
  return test.screenshots
    .filter((shot) => shot.player === player)
    .sort((a, b) => {
      // failure は撮影順に関係なく最後に置く（ステップの後に撮るため stepIndex は失敗したステップと同じ）
      if ((a.name === FAILURE_SHOT) !== (b.name === FAILURE_SHOT)) return a.name === FAILURE_SHOT ? 1 : -1;
      return (a.stepIndex ?? Number.MAX_SAFE_INTEGER) - (b.stepIndex ?? Number.MAX_SAFE_INTEGER);
    });
}

/** テストに登場するプレイヤー名。players とスクリーンショットの和集合を元の順序で返す */
export function playersOf(test: TestResult): string[] {
  const names = [...test.players.map((player) => player.name), ...test.screenshots.map((shot) => shot.player)];
  return [...new Set(names)];
}

/**
 * run のバッジに出す状態。
 * result が無い run は契約上 status "error"、未対応の schemaVersion の run は unsupported とする。
 */
export function runBadgeStatus(run: ManifestRun): BadgeStatus {
  const result = supportedResult(run);
  if (result) return result.status;
  return run.result === null || typeof run.result !== "object" ? run.status : "unsupported";
}

/** 「失敗を上に」の並び替えで使う優先度。小さいほど上 */
const STATUS_RANK: Record<TestStatus, number> = { error: 0, failed: 1, passed: 2, skipped: 3 };

/** テストの一覧を failures first で並べる。同じ状態の中では元の順（スイート順）を保つ */
export function sortFailuresFirst(tests: ManifestTest[]): ManifestTest[] {
  return tests
    .map((test, index) => ({ test, index }))
    .sort((a, b) => (STATUS_RANK[a.test.status] ?? 9) - (STATUS_RANK[b.test.status] ?? 9) || a.index - b.index)
    .map((entry) => entry.test);
}

/** テストの行ラベルに添える "5/6"（run が有り、かつ passed の数 / cells の数）。cells に無い run は数えない */
export function passedCount(test: ManifestTest): { passed: number; total: number } {
  const statuses = Object.values(test.cells);
  return { passed: statuses.filter((status) => status === "passed").length, total: statuses.length };
}
