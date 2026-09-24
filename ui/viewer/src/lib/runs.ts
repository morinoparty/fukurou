import type { BadgeStatus } from "../components/StatusBadge";
import type { ManifestRun, ResultV1, ScreenshotInfo } from "../contract";

/** 失敗時に撮るスクリーンショットの名前（契約で固定） */
export const FAILURE_SHOT = "failure";

/**
 * このビューアで表示できる result（schemaVersion 1）なら返す。
 * result が無い run や、将来の schemaVersion の run は null を返し、呼び出し側で専用カードを出す。
 */
export function supportedResult(run: ManifestRun): ResultV1 | null {
  const result = run.result;
  if (typeof result !== "object" || result === null) return null;
  if (result.schemaVersion !== 1) return null;
  const v1 = result as ResultV1;
  // pydantic は既定値も書き出すが、手で作られた JSON でも落ちないように配列だけは補う
  return {
    ...v1,
    plugins: v1.plugins ?? [],
    players: v1.players ?? [],
    steps: v1.steps ?? [],
    screenshots: v1.screenshots ?? [],
    logs: v1.logs ?? [],
  };
}

/** 表示できない run の理由を短い英文で返す */
export function unsupportedReason(run: ManifestRun): string {
  if (run.result === null || typeof run.result !== "object") {
    return "No result.json was found for this run (the job may have been cancelled or failed before writing it).";
  }
  return `This run uses result schemaVersion ${String(run.result.schemaVersion)}, which this viewer does not support. Use a newer fukurou/ui.`;
}

/** run の表示名。result があれば Minecraft のバージョン、無ければ run id */
export function runLabel(run: ManifestRun): string {
  return supportedResult(run)?.minecraft.version ?? run.id;
}

/** 指定したプレイヤー・名前のスクリーンショットを探す */
export function findScreenshot(result: ResultV1, player: string, name: string): ScreenshotInfo | undefined {
  return result.screenshots.find((shot) => shot.player === player && shot.name === name);
}

/** プレイヤーのスクリーンショットを撮影順（stepIndex 順、failure は最後）に並べる */
export function screenshotsOf(result: ResultV1, player: string): ScreenshotInfo[] {
  return result.screenshots
    .filter((shot) => shot.player === player)
    .sort((a, b) => (a.stepIndex ?? Number.MAX_SAFE_INTEGER) - (b.stepIndex ?? Number.MAX_SAFE_INTEGER));
}

/** run に登場するプレイヤー名。players とスクリーンショットの和集合を元の順序で返す */
export function playersOf(result: ResultV1): string[] {
  const names = [...result.players.map((player) => player.name), ...result.screenshots.map((shot) => shot.player)];
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
