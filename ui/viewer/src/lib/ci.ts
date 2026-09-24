import type { ManifestCi } from "../contract";

/** runUrl（https://host/owner/repo/actions/runs/N）からリポジトリの URL を取り出す */
const RUN_URL = /^(https?:\/\/[^/]+\/[^/]+\/[^/]+)\/actions\/runs\//;

/**
 * manifest の CI 情報からコミットの URL を作る。
 * manifest には serverUrl が無いので、runUrl のホストとリポジトリ部分を流用する。
 */
export function commitUrl(ci: ManifestCi | null): string | null {
  if (!ci?.runUrl || !ci.sha) return null;
  const match = RUN_URL.exec(ci.runUrl);
  return match ? `${match[1]}/commit/${ci.sha}` : null;
}
