import type { ManifestRun } from "../contract";

/** "https:" や "file:" などのスキームで始まるか */
const ABSOLUTE_URL = /^[a-z][a-z0-9+.-]*:/i;

/**
 * result 内の相対パスを、サイトの index.html からの URL に変換する（run.base + path）。
 *
 * new URL() は file:// やサブパス配信で基準がずれるため使わず、文字列連結で組み立てる。
 * パスの各要素だけをエンコードし、プレイヤー名やスクリーンショット名に "#" や空白が入っても壊れないようにする。
 */
export function assetUrl(run: ManifestRun, path: string): string {
  const encodedPath = path
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");
  return joinBase(run.base, encodedPath);
}

/** base の末尾スラッシュの有無にかかわらず1つのスラッシュでつなぐ */
function joinBase(base: string, encodedPath: string): string {
  if (base === "") return encodedPath;
  // base 自体はビルド側が作る安全な相対パスだが、念のため絶対 URL 以外は空白などだけエスケープする
  const safeBase = ABSOLUTE_URL.test(base) ? base : encodeURI(base);
  return safeBase.endsWith("/") ? safeBase + encodedPath : `${safeBase}/${encodedPath}`;
}
