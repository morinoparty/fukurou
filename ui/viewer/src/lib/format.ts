/** ミリ秒を "1m 12s" / "850ms" のような短い表記にする */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "–";
  if (ms < 1000) return `${ms}ms`;
  const totalSeconds = Math.round(ms / 1000);
  if (totalSeconds < 60) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m ${seconds}s`;
}

/** ISO 8601 の日時を閲覧者のローカル時刻で表示する。解釈できなければそのまま返す */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "–";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

/** sha256 やコミット SHA を先頭だけに縮める */
export function shortHash(hash: string | null | undefined, length = 7): string {
  if (!hash) return "–";
  return hash.slice(0, length);
}

/** class ファイルの major 番号から必要な Java のバージョンを求める（例: 65 → 21） */
export function javaFromClassFileMajor(major: number | null | undefined): number | null {
  if (major === null || major === undefined) return null;
  return major - 44;
}
