#!/usr/bin/env bash
# ルートのアクション（action.yml）の outputs とステップの要約を書く。
# OUT_DIR の下の run ディレクトリ（<run id>/result.json）をすべて jq で集計する。
#   入力の環境変数: OUT_DIR（必須）、MINECRAFT_VERSION（要約の見出し）、GITHUB_OUTPUT / GITHUB_STEP_SUMMARY（あれば書く）
set -euo pipefail

if ! command -v jq > /dev/null; then
  echo "::error::jq is required to summarize result.json (install it, or do not set skip-system-deps: true)."
  echo "result=error" >> "${GITHUB_OUTPUT:-/dev/null}"
  exit 1
fi

# result.json が 1 つも無い（Gradle がテストの前に失敗した等）ときも空の配列で集計し、error 扱いにする
shopt -s nullglob
files=("${OUT_DIR:?}"/*/result.json)
shopt -u nullglob

valid=()
broken=()
for file in "${files[@]}"; do
  # 書きかけ・壊れた result.json は集計から外し、その run は error として数える
  if jq -e 'type == "object"' "$file" > /dev/null 2>&1; then
    valid+=("$file")
  else
    broken+=("$file")
    echo "::warning::Could not read $file; counting it as an errored run."
  fi
done

# result.json は大きくなりうるので、コマンドラインの引数ではなく一時ファイル（1 つの配列）で jq に渡す
runs_file="$(mktemp)"
trap 'rm -f "$runs_file"' EXIT
if [ "${#valid[@]}" -gt 0 ]; then
  jq -s '.' "${valid[@]}" > "$runs_file"
else
  echo "[]" > "$runs_file"
fi

# jq の集計: $runs は result.json の配列、$broken は読めなかった数
aggregate="$(jq -cn --slurpfile all "$runs_file" --argjson broken "${#broken[@]}" '
  $all[0] as $runs |
  def run_status: if (.status | type) == "string" and (.status == "passed" or .status == "failed" or .status == "error") then .status else "error" end;
  # GITHUB_OUTPUT は 1 行 1 値なので、ラベルに改行があっても次の出力を作らないよう空白に潰す。空のラベルは id で代える
  def run_name: (if (.label | type) == "string" and .label != "" then .label else (.id // "?") end) | tostring | gsub("[\r\n]+"; " ");
  {
    result: (
      if ($runs | length) + $broken == 0 then "error"
      elif $broken > 0 or any($runs[]; run_status == "error") then "error"
      elif any($runs[]; run_status == "failed") then "failed"
      else "passed" end
    ),
    summary: (
      reduce ("total", "passed", "failed", "error", "skipped") as $key ({};
        .[$key] = ([$runs[] | (.summary | if type == "object" then . else {} end)[$key] // 0 | numbers] | add // 0))
    ),
    failed: ([$runs[] | run_name as $run | (.tests // [])[]?
              | select(.status == "failed" or .status == "error")
              | "\($run)/\(.id | tostring | gsub("[\r\n]+"; " "))"] | join(","))
  }')"

result="$(jq -r '.result' <<< "$aggregate")"
summary="$(jq -c '.summary' <<< "$aggregate")"
failed="$(jq -r '.failed' <<< "$aggregate")"
result_file="${valid[0]:-${files[0]:-}}"

echo "fukurou result: $result ($summary)"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "result=$result"
    echo "tests-summary=$summary"
    echo "failed-tests=$failed"
    echo "result-file=$result_file"
  } >> "$GITHUB_OUTPUT"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  jq -rn --slurpfile all "$runs_file" --argjson summary "$summary" \
      --arg version "${MINECRAFT_VERSION:-?}" --arg result "$result" \
      --argjson broken "${#broken[@]}" '
    $all[0] as $runs |
    # Markdown の表が壊れないよう、改行とパイプを潰す
    def cell: tostring | gsub("[\r\n]+"; " ") | gsub("\\|"; "\\|");
    def run_name: (if (.label | type) == "string" and .label != "" then .label else (.id // "?") end) | tostring;
    def duration: if (.durationMs | type) == "number" then "\((.durationMs / 100 | round) / 10)s" else "–" end;
    def failure_of:
      if (.failure | type) == "object" and (.failure.message // "") != "" then "\(.failure.message) (\(.failure.phase // "?"))"
      elif (.reset | type) == "object" and (.reset.error // "") != "" then .reset.error
      else .skipReason // "" end;
    def counts: [("passed", "failed", "error", "skipped") as $key
                 | select(($summary[$key] // 0) > 0) | "\($summary[$key]) \($key)"] | join(", ");
    ["### fukurou: Minecraft \($version) (\($result))", ""]
    + (if ($runs | length) + $broken == 0 then ["No result.json was written (the build failed before the tests ran?).", ""] else [] end)
    + (if $broken > 0 then ["⚠️ \($broken) result.json could not be read.", ""] else [] end)
    + ([$runs[] | select((.failure | type) == "object")
        | "⚠️ **\(run_name | cell)** \(.failure.phase // "?"): \(.failure.message // "" | cell)"]
       | if length > 0 then . + [""] else . end)
    + [if counts == "" then "\($summary.total) tests" else "\($summary.total) tests: \(counts)" end, ""]
    + (if any($runs[]; ((.tests // []) | length) > 0) then
        ["| Run | Test | Status | Duration | Failure |", "| --- | --- | --- | --- | --- |"]
        + [$runs[] | run_name as $run | (.tests // [])[]?
           | "| \($run | cell) | \(.id | cell) | \(.status | cell) | \(duration) | \(failure_of | cell) |"]
        + [""]
      else [] end)
    | .[]' >> "$GITHUB_STEP_SUMMARY"
fi
