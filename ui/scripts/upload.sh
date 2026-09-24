#!/usr/bin/env bash
# build_manifest.py が作ったサイトを S3 互換ストレージ（Cloudflare R2 など）へアップロードする。
#
# 入力はすべて環境変数で受け取る（action の input をシェルに直接埋め込まないため）:
#   SITE_DIR      アップロードするサイトのディレクトリ（必須）
#   S3_BUCKET     バケット名（必須）
#   S3_PREFIX     バケット内の置き場所（必須。前後のスラッシュは取り除く）
#   S3_ENDPOINT   S3 互換エンドポイント URL（空なら AWS S3 本家）
#   S3_REGION     リージョン（既定 "auto"。R2 はこれで良い）
#   AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY  認証情報
#
# 以前のアップロード結果を消さないよう --delete は付けない。
set -euo pipefail

fail() {
  echo "upload.sh: $*" >&2
  exit 2
}

[[ -n "${SITE_DIR:-}" ]] || fail "SITE_DIR is required"
[[ -d "${SITE_DIR}" ]] || fail "SITE_DIR ${SITE_DIR} is not a directory"
[[ -f "${SITE_DIR}/index.html" ]] || fail "${SITE_DIR}/index.html not found; run build_manifest.py first"
[[ -n "${S3_BUCKET:-}" ]] || fail "S3_BUCKET is required"
command -v aws >/dev/null 2>&1 || fail "the AWS CLI (aws) is not installed"

# 前後のスラッシュを取り除いて "a/b" の形にそろえる
prefix="${S3_PREFIX:-}"
prefix="${prefix#"${prefix%%[!/]*}"}"
prefix="${prefix%"${prefix##*[!/]}"}"
# 空のプレフィックスはバケット直下を上書きしてしまうので拒否する
[[ -n "${prefix}" ]] || fail "S3_PREFIX must not be empty"
# ".." を含むプレフィックスは意図しない場所を指しうるので拒否する
case "/${prefix}/" in
  */../* | */./*) fail "S3_PREFIX must not contain '.' or '..' segments: ${S3_PREFIX}" ;;
esac

# R2 などは新しい既定のチェックサム計算に対応していないため、必要なときだけにする
export AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED
export AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED
# ランナー上で EC2 メタデータを探しに行って待たされるのを防ぐ
export AWS_EC2_METADATA_DISABLED=true
export AWS_DEFAULT_REGION="${S3_REGION:-auto}"

destination="s3://${S3_BUCKET}/${prefix}/"
common_args=(--no-progress --only-show-errors)
if [[ -n "${S3_ENDPOINT:-}" ]]; then
  common_args+=(--endpoint-url "${S3_ENDPOINT}")
fi

# 1回目: スクリーンショットや assets/ など、同じパスで中身が変わらないものを長期キャッシュで送る。
# 参照される側を先に置くことで、manifest が先に見えて画像が 404 になる時間を作らない
echo "Uploading immutable files to ${destination}"
aws s3 sync "${SITE_DIR}" "${destination}" "${common_args[@]}" \
  --exclude "index.html" \
  --exclude "manifest.json" \
  --exclude "manifest.js" \
  --exclude "runs/*/result.json" \
  --cache-control "public, max-age=31536000, immutable"

# 2回目: 再実行で中身が変わりうる入口のファイルは毎回再検証させる
echo "Uploading index.html, manifest and results to ${destination}"
aws s3 sync "${SITE_DIR}" "${destination}" "${common_args[@]}" \
  --exclude "*" \
  --include "index.html" \
  --include "manifest.json" \
  --include "manifest.js" \
  --include "runs/*/result.json" \
  --cache-control "no-cache"

echo "Uploaded to ${destination}"
# action が公開 URL を組み立てられるよう、正規化したプレフィックスを出力する
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "prefix=${prefix}" >>"${GITHUB_OUTPUT}"
fi
