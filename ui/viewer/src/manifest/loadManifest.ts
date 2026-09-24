import type { ManifestV1 } from "../contract";

declare global {
  interface Window {
    /** サイトの manifest.js が設定する。file:// でも読めるようにクラシックスクリプトで渡す */
    __FUKUROU_MANIFEST__?: unknown;
  }
}

/** manifest の読み込み結果。画面側はこの3通りだけを考えればよい */
export type ManifestState = { kind: "loaded"; manifest: ManifestV1 } | { kind: "missing" } | { kind: "invalid"; reason: string };

/** ビューアが理解できる manifest の schemaVersion */
const SUPPORTED_MANIFEST_VERSION = 1;

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
  const candidate = value as Partial<ManifestV1> & { schemaVersion?: unknown };
  if (candidate.schemaVersion !== SUPPORTED_MANIFEST_VERSION) {
    return {
      kind: "invalid",
      reason: `Unsupported manifest schemaVersion ${String(candidate.schemaVersion)}. This viewer understands version ${SUPPORTED_MANIFEST_VERSION}.`,
    };
  }
  if (!Array.isArray(candidate.runs)) {
    return { kind: "invalid", reason: "The manifest has no runs array." };
  }
  return { kind: "loaded", manifest: withDefaults(candidate as ManifestV1) };
}

/** 欠けていても表示できる配列フィールドを空配列で補う */
function withDefaults(manifest: ManifestV1): ManifestV1 {
  return {
    ...manifest,
    title: manifest.title ?? "fukurou",
    players: manifest.players ?? [],
    shots: manifest.shots ?? [],
    warnings: manifest.warnings ?? [],
  };
}
