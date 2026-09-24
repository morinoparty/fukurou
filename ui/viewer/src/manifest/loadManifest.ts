import type { ManifestV2 } from "../contract";

declare global {
  interface Window {
    /** サイトの manifest.js が設定する。file:// でも読めるようにクラシックスクリプトで渡す */
    __FUKUROU_MANIFEST__?: unknown;
  }
}

/** manifest の読み込み結果。画面側はこの3通りだけを考えればよい */
export type ManifestState = { kind: "loaded"; manifest: ManifestV2 } | { kind: "missing" } | { kind: "invalid"; reason: string };

/** ビューアが理解できる manifest の schemaVersion */
const SUPPORTED_MANIFEST_VERSION = 2;

/**
 * manifest を読み込む。
 * まず manifest.js が設定したグローバル変数を使い、無ければ manifest.json を fetch する。
 * file:// では fetch が失敗するため、例外は握りつぶして「見つからない」として扱う。
 */
export async function loadManifest(): Promise<ManifestState> {
  const fromScript = window.__FUKUROU_MANIFEST__;
  if (fromScript !== undefined) {
    return validateManifest(fromScript);
  }
  const fromJson = await fetchManifestJson();
  return fromJson === undefined ? { kind: "missing" } : validateManifest(fromJson);
}

/** manifest.json を取得する。取得できなければ undefined */
async function fetchManifestJson(): Promise<unknown> {
  try {
    const response = await fetch("./manifest.json", { cache: "no-cache" });
    // 静的ホスティングでは存在しないファイルが 404 の HTML で返ることがある
    if (!response.ok) return undefined;
    return (await response.json()) as unknown;
  } catch {
    return undefined;
  }
}

/** 最低限の形だけ確かめる。各 run の result は表示時に個別に検査する */
function validateManifest(value: unknown): ManifestState {
  if (typeof value !== "object" || value === null) {
    return { kind: "invalid", reason: "The manifest is not a JSON object." };
  }
  const candidate = value as Partial<ManifestV2> & { schemaVersion?: unknown };
  if (candidate.schemaVersion !== SUPPORTED_MANIFEST_VERSION) {
    // v1 のサイトはこのビューアでは読まない（設計 §8: v1 の読み取りアダプタは載せない）
    const hint =
      typeof candidate.schemaVersion === "number" && candidate.schemaVersion < SUPPORTED_MANIFEST_VERSION
        ? "It was built by fukurou/ui v1; rebuild the site with fukurou/ui v2 from v2 artifacts."
        : "Use a newer fukurou/ui.";
    return {
      kind: "invalid",
      reason: `Unsupported manifest schemaVersion ${String(candidate.schemaVersion)}. This viewer understands version ${SUPPORTED_MANIFEST_VERSION}. ${hint}`,
    };
  }
  if (!Array.isArray(candidate.runs)) {
    return { kind: "invalid", reason: "The manifest has no runs array." };
  }
  return { kind: "loaded", manifest: withDefaults(candidate as ManifestV2) };
}

/** 欠けていても表示できるフィールドを補う */
function withDefaults(manifest: ManifestV2): ManifestV2 {
  return {
    ...manifest,
    title: manifest.title ?? "fukurou",
    players: manifest.players ?? [],
    tests: (manifest.tests ?? []).map((test) => ({
      ...test,
      tags: test.tags ?? [],
      players: test.players ?? [],
      shots: test.shots ?? [],
      cells: test.cells ?? {},
    })),
    warnings: manifest.warnings ?? [],
  };
}
