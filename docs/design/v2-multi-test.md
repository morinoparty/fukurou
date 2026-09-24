# fukurou v2 設計: 1 バージョン = 1 サーバーセッションで複数テストを実行する

## 0. 結論

- **方式**: 提案 1「session」を土台にする。Minecraft 1 バージョンにつき 1 ジョブ・1 プロセス・1 サーバーセッション。Paper を 1 回起動し、全テストのプレイヤーの和集合を 1 回だけ参加させ、テストを順に実行する。テストの間はハーネスがワールドとクライアントをリセットする。
- **修正した致命的な欠陥**:
  - `gamerule commandModificationBlockLimit` を使わない。アリーナは 32×24×32 = 24,576 ブロック（既定の上限 32,768 未満）に固定し、Suite モデルで `size*size*height <= 32768` を検証する。
  - `result.json` は「発見直後にスタブを書く → テスト 1 件ごとに書き直す → 後片付けで最終版を書く」。`timeout-minutes` 超過の SIGKILL やランナー消失でも完了済みのテストの結果が残る。
  - ログの照合はセッションのログファイルへの **バイトオフセットのウィンドウ** で行う（増分読み。O(n²) にならない）。`logRanges` は **パス** をキーにする（クライアント再起動で `latest.log` が切り替わるため）。
  - サーバーが途中で死んだ場合は **自動再起動しない**（提案 1 からの意図的な逸脱）。`run.failure.phase = "server"` を記録し、残りのテストは `skipped`（理由付き）にする。理由: 再起動経路は最も検証しにくく、サーバーの死はほぼプラグインのバグなのでそのまま見せる方がよい。クライアントの再起動（dirty / 死亡）と `isolation: fresh-server` は同じ 1 つの経路で実装し、1 回だけ試す。
- **移植したアイデア**: 提案 3 の `fixtures` + `use:`、ステップの `phase`、テストごとの `versions:` 制約（`skipped` + `skipReason`）、空の選択は exit 2、`selection` の記録。提案 2 のスタブ先行書き込み、早期検証（ランナーアクションが apt より前に `fukurou list` を実行）、ビューアの「not run」セル。
- **互換性**: **v2 メジャー**。理由は §8。`scenario` / `scenario-file` 入力は残す（ほぼ無料）が、**ui に v1 result.json の読み取りアダプタは載せない**（ユーザーの「古いのはどうでもいい」に従う。v1 の artifact は "unsupported" カードと警告になる）。
- **MineStamp**: 現在の 1 シナリオを `stamp-thinking-face` と `stamp-sleeping-face` の 2 テストに分割する。スタンプの表示は 3 秒で F2 の撮影は 1 枚 2 秒前後かかるので、前者は Alice → Bob、後者は Bob → Alice の順に撮り、両プレイヤーが表示中の画面を 1 枚ずつ持つようにする。リセットの `settle: 3` と fixture の `wait 2` がスタンプのクールダウン 3 秒を超える。

---

## 1. テストモデル

### 1.1 テスト = 1 つのシナリオファイル + メタデータ

v1 のシナリオ形式（`players` + `steps`）に、任意のトップレベルフィールドを足したもの。JSON / YAML どちらでもよい。

| field | default | 意味 |
| --- | --- | --- |
| `name` | ファイル名の stem | 表示名。**テスト id はファイル名の stem** で、`^[A-Za-z0-9][A-Za-z0-9_.-]*$` に一致すること（`tests/<id>/...` のパスとビューアのルートになる） |
| `tags` | `[]` | フィルタ用の文字列（`stamps`, `slow` など） |
| `isolation` | スイートの `isolation`（既定 `reset`） | `reset` = 共有セッションでハーネスのリセット後に実行 / `fresh-server` = ワールドを消してサーバーとクライアントを起動し直してから実行 |
| `timeout` | `600` 秒 | ステップの合間に確認するソフトな期限。超えたらテストは `error`（phase `timeout`） |
| `versions` | `null` | `fukurou versions` と同じ文法のうち `latest` 以外（`1.21.9-`, `1.21.6-1.21.11`, `1.21.11`。`latest` は Paper のチャンネルが要るので検証エラー）。実行中のバージョンが含まれなければ `skipped`（`skipReason: "versions: 1.21.9- does not include 1.21.6"`）。起動時に取得済みの Mojang のリリース一覧で判定するので追加の通信は無い |
| `use` | `[]` | スイートの `fixtures` の名前。この順にステップの前へ展開する |
| `players` | スイートの `players` | 省略時はスイートの一覧をそのまま使う。書いた場合はこのテストではそれで **完全に置き換える**（サブセットも可） |

- スクリーンショット名はテスト内でプレイヤーごとに一意であればよい（`tests/<id>/screenshots/<player>/<name>.png`）。`failure` は予約。
- pydantic モデル `fukurou.scenario.model.Scenario` に上のフィールドを追加し、`players` を任意にする（スイート無しで `players` が無ければ検証エラー）。`extra="forbid"` は維持。

### 1.2 スイートファイル（任意、`game-test/fukurou.yml`）

全テストで共有するものを置く。glob と相対パスは **スイートファイルのディレクトリ基準**。

```yaml
$schema: https://raw.githubusercontent.com/morinoparty/fukurou/v2/schema/suite.v1.json
scenarios:                 # 順序付き。各 glob はソートして展開。同じファイルが 2 回一致しても 1 回
  - scenarios/*.json
players:                   # players を省略したテストの既定。全テストの和集合が参加する
  - { name: Alice, op: true }
  - { name: Bob }
isolation: reset           # テストが指定しないときの既定
arena: { size: 32, height: 24 }   # リセット時に空気で埋める領域。x,z ∈ [-16,16)、y = -60..-37。false で無効
gamemode: survival         # リセット時に参加プレイヤーへ設定するゲームモード
spawn:                     # リセット時の tp 先（省略したプレイヤーは既定のスロット: (0.5 + 2i, -60, -8.5)）
  Alice: "0.5 -60 -8.5 0 0"
settle: 3                  # リセット後にクライアントがブロック更新を受け取るまで待つ秒数（既定 2）
fixtures:                  # 名前付きのステップ列。テストの use: で参照する
  arena:
    - { on: server, action: command, command: "fill 0 -60 0 0 -50 0 minecraft:stone" }
    - { on: server, action: command, command: "fill 0 -60 8 0 -50 8 minecraft:stone" }
    - { on: server, action: command, command: "tp Alice 0.5 -49 0.5 0 30" }
    - { on: server, action: command, command: "tp Bob 0.5 -49 8.5 180 -15" }
    - { action: wait, seconds: 2 }
  front-view:
    - { on: Alice, action: press_key, key: F5 }
    - { on: Alice, action: press_key, key: F5 }
beforeEach: []             # 全テストの前（fixtures より前）に実行するステップ
```

- pydantic モデル `fukurou.scenario.suite.Suite`（`extra="forbid"`）。`arena` は `ArenaSpec | Literal[False]`、`size*size*height <= 32768` を検証。
- `fukurou.scenario.discovery.discover_tests(selection) -> tuple[Suite | None, list[TestSpec]]`（純粋関数）。glob 展開、id の一意性（重複は `InvalidInputError`、`validate` でも報告）、フィルタ適用、fixture 名の解決（未知の名前はエラー）、`players` の解決、実行順の安定分割（`reset` を宣言順に並べ、その後に `fresh-server` を宣言順）。
- `TestSpec`: `id, name, order, source ("file:<path>" | "inline"), sha256, tags, isolation, timeout, versions, players: list[PlayerSpec], steps: list[PlannedStep]`（`beforeEach` → fixtures → テストの `steps` を展開済み。`PlannedStep = (phase, fixture, step)`）。
- fixture と beforeEach のステップは、そのテストの `players` に含まれないプレイヤーを対象にしていたら **そのテストではスキップ**（`status: skipped`, `error: "player Bob is not in this test"`）。これで fixture を 1 回書けばよい。

### 1.3 選択（CLI とアクションで共通）

最低 1 つ必要（どれも組み合わせ可）:
`--suite PATH`, `--scenarios GLOB`（繰り返し可。アクション入力 `scenarios` は改行/カンマ区切り）, `--scenario-file PATH`（繰り返し可、v1 の名前）, `--scenario TEXT`（インライン、id `inline`、v1 の名前）。

フィルタ: `--test ID_OR_GLOB`（繰り返し可、fnmatch で id と照合）, `--tag TAG`（繰り返し可、OR）。両方あれば AND。
**選択結果が 0 件なら exit 2**（workflow_dispatch の入力のタイポで緑の空実行を作らない）。`versions:` で除外されたテストは選択に含めたまま `skipped` にする（ビューアに行が出る）。

### 1.4 セットアップの 3 層

1. **ハーネスのリセット**（ステップではない。`tests[].reset: {durationMs, error}`）
2. **`beforeEach` と `use` の fixture**（`tests[].steps` に `phase: "beforeEach"` / `phase: "fixture", fixture: "arena"` で記録。ここで失敗すればテストは `failed` で `failure.phase = "beforeEach" | "fixture"`。プラグインのバグと区別できる）
3. **テスト自身の `steps`**（`phase: "test"`）

v1 の「参加時に `op: true` なら OP」は **テストごと** になる: リセット時に参加プレイヤーをそのテストの宣言に合わせて `op` / `deop` する。

---

## 2. 実行モデル

### 2.1 形: 1 バージョン = 1 ジョブ = 1 プロセス = 1 サーバーセッション（+ `fresh-server` のテストごとに 1 つ）

`versions/` はそのまま。N テスト × M バージョンのコストは M ジョブ。

### 2.2 `fukurou run` のライフサイクル

新規モジュール: `run/suite_run.py`（`SuiteRun`。`GameTestRun` / `orchestrator.py` を置き換える）, `run/session.py`（`GameSession`）, `run/isolation.py`（純粋関数）, `runner/log_window.py`（`LogWindow`）。

1. **setup**（1 回）: EULA 確認 → スイート/テストの発見（`discover_tests`）→ **ここで `result.json` を初めて書く**（全テスト `skipped`, `skipReason: "not run"`）→ `check_single_version` → プラグイン検査 → `resolve_version`（Mojang のリリース一覧も保持して `versions:` を判定し、該当テストを `skipped` にする）→ `_reset_work_dir`（**ジョブに 1 回だけ**。`clients/` を消すのはここだけ）→ 依存のダウンロード → Java 確認 → Paper 取得 → `prepare_server_dir(max_players = len(和集合))` → 和集合のクライアントをインストール。
2. **session 0**: `GameSession.start()`（`ServerProcess` は **セッションごとに新規作成**し、`log_path = work/logs/sessions/<n>/server-console.log` にする。`start_process` はログを `"wb"` で開くため、同じパスを使い回すと前のセッションのログが消える）→ プラグイン有効化の確認 → 和集合のプレイヤーを **1 人ずつ**（CPU の制約は不変）スイートの宣言順に参加させる。`op` は参加時には付けない（テストごとに付ける）。
3. **各テスト（`order` の順）**:
   - `skipped`（`versions:` / fail-fast / 先行のインフラ失敗）ならそのまま記録して次へ。
   - `isolation: fresh-server`（または `--isolation fresh-server`）なら `GameSession.restart_fresh()`: クライアント停止 → サーバー停止 → ログ回収 → `server/` だけ削除して `prepare_server_dir` をやり直す（`_reset_work_dir` は呼ばない。クライアントの再インストールを避ける）→ 新しい `ServerProcess` で起動 → 和集合を再参加 → `sessions[]` に `kind: "fresh-server"` を追加。
   - **dirty / 死亡クライアントの再起動**: このテストの参加者のうち `dirty`（passed で終わらなかったテストが、そのプレイヤーに `press_key` / `type_text` を実行していた。画面が開いたままの可能性がある。`chat` はアトミックなので dirty にしない）または `check_alive()` に失敗したプレイヤーは `GameSession.relaunch(player)`: 現在の `latest.log` を `logs/sessions/<n>/clients/<player>.<k>.log` として回収 → `PlayerSession.stop()` → `_window` キャッシュを破棄 → `VirtualDisplay` を起動し直す → `start(port)` → join を待つ。1 回だけ試す。失敗したら `run.failure = {phase: "client-join", ...}` で残りは `skipped`。
   - **リセット** `GameSession.reset(test)`: コマンド列は純粋関数 `isolation.reset_commands(test, joined_players, reset_spec) -> list[ResetCommand]` が作る（単体テスト可）。**この順序で実行する**（先に fill するとプレイヤーが柱から落ちて体力が減り、以後のスクリーンショットに残る）。リセットは `--scenario-file` / `--scenario` モードでも、最初のテストの前でも同じように走る（fukurou 自身の `examples/smoke.json` も対象）。
     1. 参加プレイヤーを退避: `gamemode <gamemode> <p>` → `tp <p> <spawn>`（`spawn` はスイートの `spawn:` マップ、無ければスロット i = `(0.5 + 2i, -60, -8.5, yaw 0, pitch 0)`。原点の列を避けるのは利用者の fixture が原点に柱を建てても衝突しないため）。
     2. 参加しない（が参加済みの）プレイヤー: `tp <p> 0.5 -60 200.5`（駐機場所。エンティティの追跡範囲より遠いので画面に出ない）。
     3. アリーナ（`arena` が false でなければ）: `fill -16 -60 -16 15 -37 15 minecraft:air` → `fill -16 -61 -16 15 -61 15 minecraft:grass_block` → `fill -16 -63 -16 15 -62 15 minecraft:dirt` → `fill -16 -64 -16 15 -64 15 minecraft:bedrock` → `kill @e[type=!player]`。
     4. ワールド: `time set noon`, `weather clear`。
     5. 参加プレイヤーの状態: `clear <p>`, `effect give <p> minecraft:instant_health 1 255 true`, `effect give <p> minecraft:saturation 1 255 true`, `effect clear <p>`, `experience set <p> 0 points`, `experience set <p> 0 levels`, `title <p> clear`, `spawnpoint <p> <spawn>`, そして `op <p>` / `deop <p>`。
     - **RCON の応答を検査する**（`ServerProcess.command` はエラーで例外を投げない）: 応答が `^(Unknown or incomplete command|Incorrect argument|Too many blocks|Cannot|That position is not loaded)` に一致すれば `reset.error` に記録し、テストは `error`（phase `reset`）。`kill` の `No entity was found` と `op`/`deop` の `Nothing changed` は無視。
     - クライアント側（参加プレイヤー）: `F3+d`（チャット HUD を消す）と F5 の正規化（`PlayerSession.perspective` にハーネスが送った F5 を数え、`(3 - n % 3) % 3` 回押して一人称に戻す。ベストエフォート）。
     - `settle` 秒待つ。
   - **ログウィンドウ**: セッションのサーバーコンソールログと、参加クライアントの `latest.log` に `LogWindow.mark()`（バイトオフセットと行番号を記録）。`ScenarioRunner` はファイル全体ではなく `LogWindow.read()`（前回のオフセットから増分で読み、テスト内では蓄積）を使う。これが無いと 2 つ目の `/st` テストが 1 つ目の `issued server command` 行に即一致して偽の成功になる。テスト終了時に `line_range()` を `logRanges[<artifact 内のパス>]` に記録。
   - `beforeEach` → fixtures → `steps` を `ScenarioRunner.run_step` で実行（`screenshots_dir = out/tests/<id>/screenshots`）。最初に失敗したステップで `failed`、残りのステップは `skipped`。参加プレイヤーの `failure.png` を撮る。`--fail-fast` でなければスイートは続行。
   - **テスト中のインフラ失敗**: クライアントのプロセス死亡 → テストは `error`（phase `client`）、そのプレイヤーを再起動対象に。タイムアウト → `error`（phase `timeout`）。サーバー死亡（RCON 不通または `is_running()` false）→ `run.failure = {phase: "server", message}`、残りは `skipped`（`skipReason: "server died during <id>"`）、ループ終了。
   - **テストが終わるたびに `result.json` を書く**。
4. **teardown**（1 回。fresh-server の切り替え時にも同じ回収を行う）: クライアント停止 → サーバー停止 → ログ回収。サーバーログは **コンソールの記録**（`server-console.log`。ログウィンドウの測定対象と同じファイル）を `logs/sessions/<n>/server.log` として回収する（v1 は `latest.log` を優先していたが、行番号がずれるので変更）。クライアントは `latest.log` を `logs/sessions/<n>/clients/<player>.log`（再起動後は `.<k>.log`）へ。最後に `result.json` を書く（割り込み時も）。

### 2.3 順序と並列性

- ジョブ内は直列（ソフトウェアレンダリングのクライアント 2 つで 4 vCPU は飽和）。
- 実行順 = 安定分割（`reset` を宣言順 → `fresh-server` を宣言順）。`fresh, shared, fresh` でも再起動は 1 回。
- バージョンは今までどおり並列（`max-parallel`）。シャーディングは初版では見送り（§9）。

### 2.4 所要時間の見積もり（ubuntu-24.04、プレイヤー 2 人）

固定費（バージョンジョブごと）: コールド ≈ 60 s（サーバー）+ 2 × 40–60 s（参加）= **2.5–3 分**（fukurou の外の apt/uv/checkout を含めると +1 分）。ウォーム（測定: 単一プレイヤーで 56 s）≈ 1 分。

テストごと: リセット 3–5 s（RCON はサブ秒、`settle` が支配）+ 本体。MineStamp の 1 テスト（fixture の wait 2、chat、wait_for_log、wait 1、撮影 2 枚）≈ 25–30 s。`fresh-server` は +1–3 分。

| スイート | コールド（1 バージョン） | ウォーム |
| --- | --- | --- |
| 1 テスト | ≈ 3.5 分（v1 と同じ） | ≈ 1.5 分 |
| 10 テスト（reset） | ≈ 3 + 10 × 0.55 = **8.5 分** | ≈ 6.5 分 |
| 10 + fresh-server 1 | ≈ 10.5–11.5 分 | |

素朴な「テスト × バージョンで 1 ジョブ」は 10 × 7 = 70 ジョブ × 3.5 分 ≈ 245 ジョブ分。v2 は 7 × 8.5 ≈ 60 ジョブ分、壁時計 ≈ 9–10 分。
利用者の `timeout-minutes` の目安: `10 + N_reset × 1 + N_fresh × 4`。

---

## 3. CLI

```
fukurou run --minecraft-version X --accept-eula
            [--suite PATH] [--scenarios GLOB]... [--scenario-file PATH]... [--scenario TEXT]
            [--test ID_OR_GLOB]... [--tag TAG]... [--isolation reset|fresh-server] [--fail-fast]
            [--plugins-dir DIR] [--plugins GLOBS] [--dependencies YAML] [--server-properties TEXT]
            [--server-files DIR] [--server-build N] [--java PATH] [--client-java PATH]
            [--work-dir DIR] [--out-dir DIR]
   exit 0 = 選択した全テストが passed/skipped、1 = failed/error または実行不能、2 = 入力の誤り（0 件、id 重複、不正なスイート）
fukurou list     (同じ選択引数) [--minecraft-version X]  -> 実行順のテスト id の JSON 配列（--minecraft-version があれば versions: による skipped も表示）
fukurou validate (同じ選択引数)                          -> 1 テスト 1 行。不正や重複があれば exit 2
fukurou schema {scenario|suite|result}                   -> scenario は v2、suite は v1、result は v2
fukurou versions / fukurou java                          -> 変更なし
```

`RunOptions`: `selection: Selection`（`suite: Path | None`, `scenario_files: list[Path]`, `scenario_globs: list[str]`, `scenario_text: str | None`, `test_filters: list[str]`, `tag_filters: list[str]`, `isolation: str | None`）, `fail_fast: bool`（`scenario_file` / `scenario_text` の単数フィールドは削除）。

`versions.py` に純粋関数 `spec_includes(spec: str, version: str, releases: list[str]) -> bool` と `parse_spec(spec)`（`latest` を拒否）を追加（`select_range` の判定部分を再利用）。

---

## 4. アクションのインターフェース

### 4.1 `morinoparty/fukurou@v2`（ランナー）

追加・変更する入力（他は v1 のまま）:

| input | default | 説明 |
| --- | --- | --- |
| `suite` | `""` | スイートファイルのパス（ワークスペース基準） |
| `scenarios` | `""` | シナリオファイルの glob（改行/カンマ区切り、ワークスペース基準） |
| `scenario-file` | `""` | v1 の入力。複数行可 |
| `scenario` | `""` | v1 の入力。インライン 1 件（id `inline`） |
| `tests` | `""` | 実行するテスト id / glob（カンマ/改行区切り）。空 = 全部 |
| `tags` | `""` | いずれかのタグを持つテストだけ |
| `isolation` | `""` | `reset` / `fresh-server` を全テストに強制。空 = 宣言どおり |
| `fail-fast` | `"false"` | 最初の失敗でスイートを止める |

ステップの順序: `Check inputs`（bash。「suite / scenarios / scenario-file / scenario のどれか 1 つ以上」を確認、artifact 名は `fukurou-paper-<version>` のまま）→ **setup-uv** → **`Validate the suite`（`fukurou list` を同じ選択引数で実行。exit 2 なら apt もサーバーのダウンロードもせずに終わる）** → apt → Java → cache → run → upload → outputs → fail。

出力:

| output | 説明 |
| --- | --- |
| `result` | `passed` / `failed` / `error`（result.json が無ければ `error`）— 不変 |
| `tests-summary` | `{"total":N,"passed":..,"failed":..,"error":..,"skipped":..}`（`result.summary`） |
| `failed-tests` | `failed` / `error` のテスト id をカンマ区切り |
| `result-file`, `artifact-name`, `out-dir` | 不変 |

`$GITHUB_STEP_SUMMARY` にテストごとの表（id, status, duration, failure）を追記する。最後のステップは `result != passed` でジョブを失敗させる。

### 4.2 `morinoparty/fukurou/versions@v2`: 変更なし。

### 4.3 `morinoparty/fukurou/ui@v2`

入力は不変（`artifact-pattern` の既定 `fukurou-paper-*` のまま）。出力:

| output | 説明 |
| --- | --- |
| `status` / `summary` | run（バージョン）単位。意味は不変 |
| `tests-summary` | テスト × バージョンの件数 `{"total","passed","failed","error","skipped"}` |
| `failed-tests` | `<test id>@<version>` をカンマ区切り |
| `url`, `uploaded`, `site-dir` | 不変 |

---

## 5. 契約

### 5.1 artifact の配置（1 バージョン 1 artifact、名前 `fukurou-paper-<version>` は不変）

```
result.json                                    # schemaVersion 2。発見直後・各テスト後・後片付けで書く
tests/<test id>/screenshots/<player>/<name>.png
tests/<test id>/screenshots/<player>/failure.png
logs/harness.log
logs/sessions/<n>/server.log                   # コンソールの記録（JVM のエラーを含む）。logRanges の対象
logs/sessions/<n>/clients/<player>.log         # そのセッションでの最初の起動の latest.log
logs/sessions/<n>/clients/<player>.<k>.log     # 再起動 k 回目（k >= 2）
crash-reports/<player>/*.txt
```

`build_manifest.py` の `CONTRACT_DIRS` は `("tests", "logs", "crash-reports")` に、`copy_artifact_files` は `tests/` をコピーするように変える（`ui-smoke` の平置き検出のため）。`sanitize_result_paths` / `normalize_list_fields` / `warn_missing_screenshots` は **`tests[].screenshots`, `tests[].steps[].screenshot`, `sessions[].logs`, `logRanges` のキー** を走査する（ルートの `screenshots` / `steps` は無くなるので、そのままでは経路の検査が空振りする）。`run/artifacts.py` の `OUTPUT_ENTRIES` に `tests` を加える（発見直後に書いたスタブが消されないように）。クラッシュレポートは `sessions[].logs` に `kind: "crash"` で載せる。

### 5.2 `result.json` schemaVersion 2（`fukurou.result.model.ResultV2`、`schema/result.v2.json`）

```json
{
  "schemaVersion": 2,
  "id": "paper-1.21.11",
  "status": "failed",
  "fukurou": { "version": "2.0.0", "portablemc": "5.0.4" },
  "minecraft": { "version": "1.21.11", "server": "paper", "build": 130, "channel": "STABLE" },
  "java": { "server": 25 },
  "plugins": [
    { "file": "MineStamp-abc1234-all.jar", "sha256": "...", "name": "MineStamp", "version": "1.4.0", "role": "under-test", "source": null, "classFileMajor": 69, "enabled": true }
  ],
  "suite": { "source": "file:game-test/fukurou.yml", "sha256": "...", "isolation": "reset", "settle": 3, "gamemode": "survival", "arena": { "size": 32, "height": 24 } },
  "selection": { "tests": [], "tags": [], "isolation": null, "failFast": false },
  "players": [ { "name": "Alice", "joined": true }, { "name": "Bob", "joined": true } ],
  "summary": { "total": 3, "passed": 1, "failed": 1, "error": 0, "skipped": 1 },
  "sessions": [
    {
      "index": 0, "kind": "initial",
      "startedAt": "2026-09-24T03:01:00Z", "finishedAt": "2026-09-24T03:05:12Z",
      "players": ["Alice", "Bob"], "tests": ["stamp-thinking-face", "stamp-sleeping-face"],
      "logs": [
        { "kind": "server", "path": "logs/sessions/0/server.log", "player": null },
        { "kind": "client", "path": "logs/sessions/0/clients/Alice.log", "player": "Alice" },
        { "kind": "client", "path": "logs/sessions/0/clients/Bob.log", "player": "Bob" }
      ],
      "failure": null
    }
  ],
  "tests": [
    {
      "id": "stamp-thinking-face", "name": "stamp-thinking-face", "order": 0,
      "source": "file:game-test/scenarios/stamp-thinking-face.json", "sha256": "...",
      "tags": ["stamps"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "passed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3400, "error": null },
      "steps": [
        { "index": 0, "phase": "fixture", "fixture": "arena", "on": "server", "action": "command", "label": "fill 0 -60 0 0 -50 0 minecraft:stone", "status": "passed", "durationMs": 20, "error": null, "screenshot": null },
        { "index": 5, "phase": "fixture", "fixture": "front-view", "on": "Alice", "action": "press_key", "label": "F5", "status": "passed", "durationMs": 60, "error": null, "screenshot": null },
        { "index": 10, "phase": "test", "fixture": null, "on": "Alice", "action": "screenshot", "label": "after-stamp", "status": "passed", "durationMs": 1900, "error": null, "screenshot": "tests/stamp-thinking-face/screenshots/Alice/after-stamp.png" }
      ],
      "failure": null,
      "screenshots": [
        { "player": "Alice", "name": "after-stamp", "path": "tests/stamp-thinking-face/screenshots/Alice/after-stamp.png", "width": 1280, "height": 720, "stepIndex": 10 }
      ],
      "logRanges": {
        "logs/sessions/0/server.log": { "from": 212, "to": 240 },
        "logs/sessions/0/clients/Alice.log": { "from": 88, "to": 103 },
        "logs/sessions/0/clients/Bob.log": { "from": 80, "to": 91 }
      },
      "startedAt": "2026-09-24T03:02:10Z", "durationMs": 31000
    },
    {
      "id": "stamp-sleeping-face", "name": "stamp-sleeping-face", "order": 1,
      "source": "file:game-test/scenarios/stamp-sleeping-face.json", "sha256": "...",
      "tags": ["stamps"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "failed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3300, "error": null },
      "steps": [
        { "index": 8, "phase": "test", "fixture": null, "on": "server", "action": "wait_for_log", "label": "Alice issued server command: /st :sleeping-face:", "status": "failed", "durationMs": 10004, "error": "server log did not match '...' within 10s", "screenshot": null },
        { "index": 9, "phase": "test", "fixture": null, "on": "Bob", "action": "screenshot", "label": "after-stamp", "status": "skipped", "durationMs": null, "error": null, "screenshot": null }
      ],
      "failure": { "phase": "scenario", "message": "server log did not match '...' within 10s", "stepIndex": 8 },
      "screenshots": [
        { "player": "Alice", "name": "failure", "path": "tests/stamp-sleeping-face/screenshots/Alice/failure.png", "width": 1280, "height": 720, "stepIndex": 8 }
      ],
      "logRanges": { "logs/sessions/0/server.log": { "from": 241, "to": 262 } },
      "startedAt": "2026-09-24T03:02:45Z", "durationMs": 16200
    },
    {
      "id": "stamp-legacy-format", "name": "stamp-legacy-format", "order": 2,
      "source": "file:game-test/scenarios/stamp-legacy-format.json", "sha256": "...",
      "tags": ["stamps"], "isolation": "reset", "timeout": 600, "versions": "1.21.6-1.21.9", "session": null,
      "status": "skipped", "skipReason": "versions: 1.21.6-1.21.9 does not include 1.21.11",
      "players": [ { "name": "Alice", "op": true } ],
      "reset": null, "steps": [], "failure": null, "screenshots": [], "logRanges": null,
      "startedAt": null, "durationMs": null
    }
  ],
  "failure": null,
  "logs": [ { "kind": "harness", "path": "logs/harness.log", "player": null } ],
  "startedAt": "2026-09-24T03:00:00Z", "finishedAt": "2026-09-24T03:05:30Z", "durationMs": 330000,
  "ci": { "repository": "morinoparty/MineStamp", "sha": "...", "ref": "refs/pull/1/merge", "runId": "123", "runAttempt": "1", "serverUrl": "https://github.com" }
}
```

意味:

- `id` は v1 と同じ `<server>-<minecraft version>`（artifact 名の末尾）。1 artifact に全テストが入るので衝突しない。
- **run の `status`**: `run.failure` があれば `error`（`setup` / `server-start` / `client-join` / `server` / `teardown` / `interrupted`。インフラがテストを妨げた。残りのテストは `skipped`）。無ければ、いずれかのテストが `failed` / `error` なら `failed`。それ以外は `passed`（`skipped` は成功扱い）。`RunRecorder.status` は `failure` と `tests` から導出する。
- **テストの `status`**: `passed` / `failed`（`failure.phase` が `beforeEach` / `fixture` / `scenario`、= ステップの失敗）/ `error`（`reset` / `client` / `timeout`）/ `skipped`（`skipReason` 必須: `versions: ...`, `fail-fast`, `not run`, `server died during <id>`, `client relaunch failed: <player>`）。
- `steps[].phase`: `beforeEach | fixture | test`。`fixture` は phase が `fixture` のときだけ名前。`index` はそのテストの `steps` 配列の添字で、`failure.stepIndex` もそれ。
- `logRanges`: セッションで回収したログファイルのパスをキーにした 1 始まり両端含みの行番号。テストが走らなければ `null`。
- `sessions[].kind`: `initial | fresh-server`。`sessions[].failure` はサーバーが死んだ時のメッセージ。
- `players[].op` は削除（テストごとに `tests[].players[].op`）。
- `steps` / `screenshots` / `scenario` がルートから `tests[]` へ移るので、docs/contract.md の規則どおり schemaVersion 2。以後のフィールド追加は後方互換。

### 5.3 `manifest.json` schemaVersion 2（`ui/scripts/build_manifest.py`）

```json
{
  "schemaVersion": 2,
  "generator": { "name": "fukurou-ui", "version": "2.0.0" },
  "generatedAt": "2026-09-24T03:10:00Z",
  "title": "MineStamp abc1234",
  "ci": { "repository": "morinoparty/MineStamp", "sha": "...", "runId": "123", "runUrl": "https://github.com/morinoparty/MineStamp/actions/runs/123" },
  "summary": {
    "runs": { "total": 6, "passed": 4, "failed": 2, "error": 0 },
    "tests": { "total": 18, "passed": 14, "failed": 2, "error": 0, "skipped": 2 }
  },
  "players": ["Alice", "Bob"],
  "tests": [
    {
      "id": "stamp-thinking-face", "name": "stamp-thinking-face", "tags": ["stamps"],
      "players": ["Alice", "Bob"], "shots": ["after-stamp"], "status": "passed",
      "cells": { "paper-1.21.6": "passed", "paper-1.21.11": "passed" }
    },
    {
      "id": "stamp-sleeping-face", "name": "stamp-sleeping-face", "tags": ["stamps"],
      "players": ["Alice", "Bob"], "shots": ["after-stamp"], "status": "failed",
      "cells": { "paper-1.21.6": "passed", "paper-1.21.11": "failed" }
    }
  ],
  "runs": [
    { "id": "paper-1.21.11", "artifact": "fukurou-paper-1.21.11", "base": "runs/paper-1.21.11/", "status": "failed", "result": { "...": "result.json v2 をそのまま埋め込む" } }
  ],
  "warnings": []
}
```

- `tests` は全 run の `tests[]` を最初に現れた run の実行順で和集合にする。`cells` に無い run は「not run」（`error` に数えない）。`tests[].status` は `error` > `failed` > `passed` > `skipped` の優先で決める（「失敗を上に」の並び替え用）。`players` / `shots` は **テストごと** の和集合（`failure` を除く）。
- run の `result.json` が schemaVersion 2 でなければ、その run は `status: "error"`, `result` は埋め込むが `cells` には現れず、警告 `unsupported result schemaVersion 1`（v1 アダプタは載せない）。
- `summary.runs` は v1 の `summary` と同じ意味（`ui-smoke` の `summary.runs.total == 1` へ書き換え）。`build_manifest.py` は `status`, `summary`（runs）, `tests-summary`, `failed-tests` を `GITHUB_OUTPUT` に書く。
- `single_artifact_name()` は `fukurou-<result.id>` のまま。`TRAILING_VERSION` / `TRAILING_RUN_ID` は artifact 名が変わらないのでそのまま。

### 5.4 schemaVersion の扱い

ランナーは 2 だけを書く。`fukurou schema result` は v2。`schema/result.v1.json` と `schema/scenario.v1.json` はリポジトリに残す（v1 タグの利用者の参照用。CI の再生成対象からは外す）。CI は `schema/result.v2.json`, `schema/suite.v1.json`, `schema/scenario.v2.json` を再生成して差分を検査する。`tests/test_cli.py` の schema テストは名前→ファイルの対応表（`{"scenario": "scenario.v2.json", "suite": "suite.v1.json", "result": "result.v2.json"}`）で比べる。

---

## 6. ビューアの IA

ハッシュルーティングは維持。URL の id は `SAFE_ID` で検証済み。Chlorophyll（light のみ、`mori`）と `file://` 用の classic-script manifest は不変。

| route | page |
| --- | --- |
| `#/` | **Overview**: `SummaryHeader`（"2 tests × 6 versions: 14 passed, 2 failed, 2 skipped"。副行に run の件数）、`Warnings`、**ステータスグリッド**: 行 = テスト（スイート順。名前 + タグ + "5/6"）、列 = バージョン（古い順。ヘッダに run の status チップ、`#/runs/<runId>` へリンク）、セル = `StatusBadge`（passed / failed / error / skipped / not run）で `#/runs/<runId>/tests/<testId>` へリンク。「failures first」トグル（localStorage）。行ラベルは `#/tests/<testId>` へ |
| `#/tests/$testId` | **Test page**: v1 の overview を 1 テストに限定したもの。行 = バージョン、列 = そのテストの `players`（`manifest.tests[].players`）、セル = 最後のスクリーンショットのサムネイル + チップ（`RunRow` / `PlayerCell` / `grid.ts` を `players` を差し替えて再利用）。`ShotLinks` はそのテストの `shots` |
| `#/tests/$testId/compare/$shot` | **Compare**: そのテストの同名スクリーンショットをバージョン × プレイヤーで並べる（`ComparePage` と `findScreenshot` は `(test, player, shot)` を取る） |
| `#/runs/$runId` | **Run page**（1 バージョン）: `<h1>Minecraft 1.21.11</h1>`、`BuildInfo` / `PluginsTable`、sessions の一覧、テストの表（order, name, status, duration, failure）→ test-run page、`run.failure` があれば `FailureBox` を最上部に。`RunNav` はバージョン間の prev/next |
| `#/runs/$runId/tests/$testId` | **Test-run page**: status、failure、`StepTimeline` に phase 列（beforeEach / fixture は折りたたみ既定）、`PlayerScreenshots`（failure を強調）、`logRanges` から "server log lines 212–240" のリンク、prev/next は **同じテストの隣のバージョン** |
| `#/runs/$runId/logs/$logIndex?from=&to=` | **Log viewer**: 既存コンポーネントに範囲の強調と `from` への自動スクロールを追加。`logIndex` は `[...result.logs, ...sessions.flatMap(s => s.logs)]` の添字 |

`contract.ts` は `ResultV2` / `TestResult` / `SessionInfo` / `LogRange` / `ManifestV2` に置き換える（`ResultV1` は削除。`AnyResult` の逃げ道は残す）。`lib/runs.ts`: `supportedResult` は `schemaVersion === 2`、`findTest(result, id)`, `testLabel(test)`, `flatLogs(result)` を追加。`runLabel` はバージョンのまま。`ui/viewer/dev/manifest.js` を v2 の形に作り直す。

---

## 7. MineStamp の移行

### 7.1 `.github/workflows/game_test.yml`

```yaml
name: Game test

on:
  pull_request:
  workflow_dispatch:
    inputs:
      minecraft-versions:
        description: 'Versions: "latest", "1.21.11", "1.21.6-" or "1.21.6-1.21.11"'
        default: "1.21.6-"
      tests:
        description: "Test ids or globs to run (comma separated). Empty runs the whole suite"
        default: ""
      tags:
        description: "Only tests with one of these tags (comma separated)"
        default: ""

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  # テストするバージョンの一覧を fukurou から受け取り、test ジョブの matrix にする
  versions:
    runs-on: ubuntu-24.04
    outputs:
      matrix: ${{ steps.versions.outputs.matrix }}
    steps:
      - id: versions
        uses: morinoparty/fukurou/versions@v2
        with:
          # MineStamp は Java 25 のバイトコードのため、Paper 1.21.4 以前では読み込めない
          minecraft-version: ${{ inputs.minecraft-versions || '1.21.6-' }}

  # テスト対象のプラグインを1回だけビルドし、各バージョンのジョブで使い回す
  build:
    runs-on: ubuntu-24.04
    outputs:
      sha: ${{ steps.sha.outputs.sha }}
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - id: sha
        run: echo "sha=$(git rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "25"
          cache: gradle
      - run: |
          chmod +x ./gradlew
          ./gradlew shadowJar --no-daemon --console=plain
      - uses: actions/upload-artifact@v7
        with:
          name: plugin
          path: build/libs/*-all.jar
          if-no-files-found: error
          retention-days: 3

  # 1バージョン = 1ジョブ。ジョブの中で全テストを1つのサーバーセッションで実行する
  test:
    name: Minecraft ${{ matrix.minecraft-version }}
    needs: [versions, build]
    runs-on: ubuntu-24.04
    # 10 + テスト数 × 1 分（fresh-server のテストは 1 つにつき +4 分）
    timeout-minutes: 20
    strategy:
      fail-fast: false
      max-parallel: 8
      matrix:
        minecraft-version: ${{ fromJSON(needs.versions.outputs.matrix) }}
    steps:
      # スイートとシナリオファイルを読むためにチェックアウトする
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/download-artifact@v8
        with:
          name: plugin
          path: ${{ runner.temp }}/plugins
      - id: fukurou
        uses: morinoparty/fukurou@v2
        with:
          accept-eula: true
          minecraft-version: ${{ matrix.minecraft-version }}
          plugins-dir: ${{ runner.temp }}/plugins
          plugins: "*-all.jar"
          # スタンプの描画に ProtocolLib を使う
          dependencies: |
            - github: dmulloy2/ProtocolLib
              tag: dev-build
              asset: ProtocolLib.jar
          # スイートファイルがシナリオの一覧・共通プレイヤー・fixture を持つ
          suite: game-test/fukurou.yml
          tests: ${{ inputs.tests }}
          tags: ${{ inputs.tags }}

  # 各バージョンの結果をまとめたビューアを作り、S3 に公開する
  deploy:
    needs: [build, test]
    # 一部のバージョンが失敗しても、失敗時のスクリーンショットを見られるようにする
    if: ${{ !cancelled() && needs.build.result == 'success' }}
    runs-on: ubuntu-24.04
    outputs:
      # URL はバケット名（secret）と同じ文字列を含むため、job output にすると GitHub に捨てられる。
      # アップロードできたかだけを渡し、URL は preview ジョブで組み立てる（preview.yml と同じ方法）
      uploaded: ${{ steps.ui.outputs.uploaded }}
      # {"total","passed","failed","error","skipped"}: テスト × バージョンの件数
      tests-summary: ${{ steps.ui.outputs.tests-summary }}
      # "stamp-sleeping-face@1.21.11" のような一覧
      failed-tests: ${{ steps.ui.outputs.failed-tests }}
    steps:
      # fork からの PR には secret が渡らないため取得しない（fukurou/ui はアップロードを飛ばす）
      - name: Get S3 credentials from Bitwarden
        if: ${{ github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository }}
        uses: bitwarden/sm-action@v2
        with:
          access_token: ${{ secrets.NIKOMARU_BITWARDEN_SECRET_MANAGER_ACCESS_TOKEN }}
          secrets: |
            37e4d53a-a898-4e9f-b067-b49900deaf4f > AWS_ACCESS_KEY_ID
            4d92b739-40fc-467f-97fa-b49900deb52c > AWS_SECRET_ACCESS_KEY
      - id: ui
        uses: morinoparty/fukurou/ui@v2
        with:
          title: MineStamp ${{ needs.build.outputs.sha }}
          upload: auto
          aws-access-key-id: ${{ env.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ env.AWS_SECRET_ACCESS_KEY }}
          s3-endpoint: ${{ secrets.S3_ENDPOINT }}
          s3-bucket: ${{ secrets.S3_UPLOAD_BUCKET }}
          # preview.yml と同じ minestamp/<sha>/ の下に置く
          s3-prefix: minestamp/${{ needs.build.outputs.sha }}/game-test
          public-base-url: https://moripa-ci.nikomaru.dev

  # preview.yml が投稿したプレビューコメントの「In-game screenshots」の節を、テストの件数とビューアへのリンクで置き換える
  preview:
    needs: [build, test, deploy]
    if: ${{ !cancelled() && github.event_name == 'pull_request' && needs.deploy.outputs.uploaded == 'true' }}
    runs-on: ubuntu-24.04
    permissions:
      pull-requests: write
    steps:
      - name: Update the preview comment
        env:
          GH_TOKEN: ${{ github.token }}
          PR: ${{ github.event.pull_request.number }}
          # preview.yml のコメントは JAR 名に短縮 SHA を含むので、同じコミットのコメントを見分けられる
          JAR_NAME: MineStamp-${{ needs.build.outputs.sha }}.jar
          FUKUROU_URL: https://moripa-ci.nikomaru.dev/minestamp/${{ needs.build.outputs.sha }}/game-test/index.html
          RESULT: ${{ needs.test.result }}
          TESTS_SUMMARY: ${{ needs.deploy.outputs.tests-summary }}
          FAILED_TESTS: ${{ needs.deploy.outputs.failed-tests }}
        run: |
          python3 - <<'PY'
          import json, os, re, subprocess, time

          repo, pr = os.environ["GITHUB_REPOSITORY"], os.environ["PR"]
          start, end = "<!-- fukurou:start -->", "<!-- fukurou:end -->"

          def gh(*args):
              return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout

          # 節の本文: 全体の結果、テスト × バージョンの件数、失敗したテスト、ビューアへのリンク
          icon = {"success": "✅", "failure": "❌"}.get(os.environ["RESULT"], "⚠️")
          counts = json.loads(os.environ.get("TESTS_SUMMARY") or "{}")
          detail = ", ".join(f"{counts[k]} {k}" for k in ("passed", "failed", "error", "skipped") if counts.get(k))
          section = f"{start}\n{icon} **{os.environ['RESULT']}**"
          if detail:
              section += f" ({detail} of {counts.get('total', 0)} test runs)"
          section += f" — [Open the viewer]({os.environ['FUKUROU_URL']})"
          failed = [t for t in os.environ.get("FAILED_TESTS", "").split(",") if t]
          if failed:
              section += "\n\nFailed: " + ", ".join(f"`{t}`" for t in failed[:20]) + (" …" if len(failed) > 20 else "")
          section += f"\n{end}"

          def find_preview_comment():
              comments = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/issues/{pr}/comments"))
              matches = [c for page in comments for c in page
                         if start in c["body"] and os.environ["JAR_NAME"] in c["body"]]
              return matches[-1] if matches else None

          # ゲームのテストの方が遅いので通常は既にあるが、プレビューがまだなら最大10分待つ
          for _ in range(20):
              comment = find_preview_comment()
              if comment:
                  body = re.sub(re.escape(start) + r".*?" + re.escape(end), lambda _: section, comment["body"], flags=re.S)
                  gh("api", "-X", "PATCH", f"repos/{repo}/issues/comments/{comment['id']}", "-f", f"body={body}")
                  print(f"Updated preview comment {comment['id']}")
                  break
              time.sleep(30)
          else:
              # プレビューコメントが見つからない場合は、単独のコメントとして投稿する
              gh("pr", "comment", pr, "--repo", repo, "--body", f"### 🦉 In-game screenshots\n{section}")
              print("Preview comment not found; posted a separate comment")
          PY
```

### 7.2 `game-test/fukurou.yml`

```yaml
$schema: https://raw.githubusercontent.com/morinoparty/fukurou/v2/schema/suite.v1.json
scenarios:
  - scenarios/*.json
players:
  - { name: Alice, op: true }
  - { name: Bob }
isolation: reset
# リセット後に 3 秒待つ: クライアントがブロック更新を受け取る時間と、MineStamp のスタンプのクールダウン（3 秒）を兼ねる
settle: 3
fixtures:
  # Alice と Bob が向かい合う石の柱。time/weather/インベントリ等のリセットは fukurou が行う
  arena:
    - { on: server, action: command, command: "fill 0 -60 0 0 -50 0 minecraft:stone" }
    - { on: server, action: command, command: "fill 0 -60 8 0 -50 8 minecraft:stone" }
    - { on: server, action: command, command: "tp Alice 0.5 -49 0.5 0 30" }
    - { on: server, action: command, command: "tp Bob 0.5 -49 8.5 180 -15" }
    - { action: wait, seconds: 2 }
  # Alice を正面視点（F5 × 2）にする。fukurou がテストの前に一人称へ戻す
  front-view:
    - { on: Alice, action: press_key, key: F5 }
    - { on: Alice, action: press_key, key: F5 }
```

### 7.3 `game-test/scenarios/stamp-thinking-face.json`（Alice → Bob の順に撮る）

```json
{
  "$schema": "https://raw.githubusercontent.com/morinoparty/fukurou/v2/schema/scenario.v2.json",
  "tags": ["stamps"],
  "use": ["arena", "front-view"],
  "steps": [
    { "on": "Alice", "action": "chat", "text": "/st :thinking-face:" },
    { "on": "server", "action": "wait_for_log", "pattern": "Alice issued server command: /st :thinking-face:", "timeout": 10 },
    { "action": "wait", "seconds": 1 },
    { "on": "Alice", "action": "screenshot", "name": "after-stamp" },
    { "on": "Bob", "action": "screenshot", "name": "after-stamp" },
    { "on": "Alice", "action": "assert_no_log", "pattern": "\\[CHAT\\].*(Unknown( or incomplete)? command|Stamp not found|do not have that stamp|cannot summon)" }
  ]
}
```

### 7.4 `game-test/scenarios/stamp-sleeping-face.json`（Bob → Alice の順に撮る。クールダウンはリセットの settle 3 s + fixture の wait 2 s で明ける）

```json
{
  "$schema": "https://raw.githubusercontent.com/morinoparty/fukurou/v2/schema/scenario.v2.json",
  "tags": ["stamps"],
  "use": ["arena", "front-view"],
  "steps": [
    { "on": "Alice", "action": "chat", "text": "/st :sleeping-face:" },
    { "on": "server", "action": "wait_for_log", "pattern": "Alice issued server command: /st :sleeping-face:", "timeout": 10 },
    { "action": "wait", "seconds": 1 },
    { "on": "Bob", "action": "screenshot", "name": "after-stamp" },
    { "on": "Alice", "action": "screenshot", "name": "after-stamp" },
    { "on": "Alice", "action": "assert_no_log", "pattern": "\\[CHAT\\].*(Unknown( or incomplete)? command|Stamp not found|do not have that stamp|cannot summon)" }
  ]
}
```

`game-test/README.md` はスイートファイルと各テストを 1 行ずつ列挙し、ローカルの検証コマンドを `fukurou validate --suite game-test/fukurou.yml` に変える。

---

## 8. 互換性とバージョニングの決定

**v2 メジャー。** 理由:

1. `result.json` の形が変わる（`steps` / `screenshots` / `scenario` が `tests[]` へ、`logs` が `sessions[]` へ、`players[].op` の削除）。docs/contract.md の規則で schemaVersion を上げる変更であり、タグもそれに従う。
2. ランナーと ui は同じタグで動かす前提なので、v1 の moving tag に schemaVersion 2 を載せると、`ui` を別 SHA に固定している利用者や v1 の result.json を読む道具が黙って壊れる。
3. 利用者は MineStamp だけで、ユーザー自身が「古いのは対応しなくてよい」と言っている。

保つもの（利用者は `uses:` 3 行の `@v1` → `@v2` だけ）: `scenario` / `scenario-file` 入力（1 テスト。id は stem / `inline`）、`artifact-name` の既定と `id`、`versions` アクション、`ui` の入力と `artifact-pattern`、`status` / `summary` / `url` 出力の意味。

保たないもの: schemaVersion 1 の **書き手**（v2 は 1 を書かない）、ui の **v1 読み取りアダプタ**（v1 の artifact は "unsupported" カードと警告。混在させたければ ui も v1 のままにする）、トップレベルの `scenario` / `steps` / `screenshots`、`players[].op`。

タグ運用: `v2.0.0` を切り、moving tag `v2` を作る。`v1` は 1.x のバグ修正だけで動かす。`schema/result.v1.json` / `schema/scenario.v1.json` はリポジトリに残す。

---

## 9. リスク

- **プラグイン側の状態は汎用的にはリセットできない**（MineStamp のクールダウン、プレイヤーデータ、サーバー側の永続化）。逃げ道は `settle`、`beforeEach` / fixture でプラグインのリセットコマンドを呼ぶ、`isolation: fresh-server`。usage.md に目立つように書く。
- **クライアント側の残留**: F5 の正規化はベストエフォート（画面を開いた状態で F5 を押すと数え損なう）。`press_key` / `type_text` を使ったテストが失敗した後はクライアントを再起動する（20–60 s）。作者には `chat` を優先させる。
- **ログウィンドウの意味の変更**: `assert_no_log` がログ全体を見なくなる（起動時のプラグインのエラーはテストを落とさない）。後続で `scope: session` のオプションを検討。
- **サーバーログの回収元の変更**（`server-console.log`）: 形式が少し違い、ローテーションが無いので 10 テストで長くなる。ログビューアは長いファイルでも動く必要がある（既存の `LogLines` の性能を確認）。
- **アリーナのリセットはフラットワールド前提**（y = -64..-61）。`server-files` でワールドを持ち込む利用者は `arena: false`。`validate` は `server-files` に `world/` があれば警告する。
- **長寿命サーバーに溜まるもの**（スコアボード、駐機したプレイヤーの周りのチャンク）: `fresh-server` と run 単位の `isolation` 入力が逃げ道。`-Xmx2G` はスイートが 20 件を超えたら見直す。
- **回復経路**（クライアント再起動 / fresh-server）は 1 回だけ試し、失敗は必ず `run.failure` に落として止まる。サーバー死亡は再起動しない。
- **fukurou 自身の CI**: schema 3 種の再生成、`ui/dist` の再ビルド、`tests/test_cli.py` の run 系テストの移動、`ui/fixtures` の v2 化、`ui-smoke` のアサーション変更（`summary.runs.total == 1`）を同じ変更で出す。
- **フィルタで一部のバージョンだけテストが無い**と格子が疎になる。許容し、manifest はそれを `error` に数えない。
- **シャーディングは見送り**。追加するときは artifact 名を `fukurou-<shard>-paper-<version>`（バージョンを末尾に）にして `build_manifest` で同一バージョンをマージする。
- 駐機場所（z = 200.5）への tp は参加者が入れ替わるたびに新しいチャンクを読む（1 人テストと 2 人テストを混ぜるスイートで数秒）。MineStamp では問題にならない。
- dirty の規則により、MineStamp の `front-view` fixture は `press_key` を使うので **失敗したテストの後は必ず Alice を再起動する**（20–60 s）。失敗は例外なので受け入れる。
- `F3+d`（xdotool の `key F3+d`）は実機のクライアントで 1 度確かめる。

---

## 10. モジュール間のインターフェース（並列実装のために固定する名前）

| モジュール（所有タスク） | 公開する名前 |
| --- | --- |
| `fukurou.result.model`（contract-manifest） | `SCHEMA_VERSION = 2`, `ResultV2`, `TestResult`, `SessionInfo`, `StepResult`（`phase: Literal["beforeEach","fixture","test"]`, `fixture: str | None`）, `ScreenshotInfo`, `LogInfo`, `PluginInfo`, `PlayerInfo`（`name`, `joined`）, `TestPlayer`（`name`, `op`）, `RunFailure`（`phase: RunFailurePhase`, `message`）, `TestFailure`（`phase: TestFailurePhase`, `message`, `step_index`）, `ResetInfo`, `LogRange`, `SuiteInfo`, `SelectionInfo`, `Summary`, `RunStatus`, `TestStatus`, `RunFailurePhase = Literal["setup","server-start","client-join","server","teardown","interrupted"]`, `TestFailurePhase = Literal["reset","beforeEach","fixture","scenario","client","timeout"]` |
| `fukurou.result.steps`（contract-manifest） | `step_label(step)`, `step_target(step)`（不変） |
| `fukurou.scenario.model`（suite-cli） | `Scenario`（`name`, `tags`, `isolation`, `timeout`, `versions`, `use`, 任意の `players`）, `PlayerSpec`, `Step`, `FAILURE_SCREENSHOT`, `parse_scenario` |
| `fukurou.scenario.suite`（suite-cli） | `Suite`, `ArenaSpec`, `Isolation = Literal["reset","fresh-server"]`, `ResetSpec`（`arena`, `gamemode`, `spawn`, `settle`） |
| `fukurou.scenario.discovery`（suite-cli） | `TestSpec`（`id`, `name`, `order`, `source`, `sha256`, `tags`, `isolation`, `timeout`, `versions`, `players: list[PlayerSpec]`, `steps: list[PlannedStep]`）, `PlannedStep`（`phase`, `fixture`, `step`）, `Selection`（`suite: Path | None`, `scenario_files`, `scenario_globs`, `scenario_text`, `test_filters`, `tag_filters`, `isolation`）, `discover_tests(selection) -> tuple[Suite | None, list[TestSpec]]`, `SuiteError(InvalidInputError)` |
| `fukurou.scenario.loader`（suite-cli） | `ScenarioSource`（`ScenarioInfo` への依存を **やめる**。`source` と `sha256` は `TestSpec` が持つ）, `parse_document` |
| `fukurou.versions`（suite-cli） | `spec_includes(spec, version, releases) -> bool`, `parse_spec(spec)`（`latest` を拒否） |
| `fukurou.run.options`（suite-cli） | `RunOptions`（`selection: Selection`, `fail_fast`, 他は v1 のまま） |
| `fukurou.run.suite_run`（runner-core） | `SuiteRun(options).execute() -> int`（`cli._run` が呼ぶ） |
| `fukurou.run.session`（runner-core） | `GameSession`: `start()`, `join_all()`, `relaunch(name)`, `reset(test)`, `restart_fresh()`, `stop()`, `collect_logs(index)` |
| `fukurou.run.isolation`（runner-core） | `ResetCommand(command, ignore: tuple[str, ...])`, `reset_commands(test, joined, reset_spec)`, `default_slot(i)`, `PARKING_SPOT`, `ERROR_RESPONSE`（正規表現） |
| `fukurou.runner.log_window`（runner-core） | `LogWindow(path)`: `mark()`, `read() -> str`, `line_range() -> LogRange | None` |
| `fukurou.result.recorder`（runner-core） | `RunRecorder`（`ResultRecorder` を改名。`register_tests(specs)`, `test(id) -> TestRecorder`, `add_session(...)`, `fail(phase, message)`, `status`, `build() -> ResultV2`, `write(path)`）, `TestRecorder`（`start()`, `set_reset(...)`, `step_passed/failed/skipped`, `add_screenshot`, `set_log_ranges`, `skip(reason)`, `fail(phase, message, step_index)`） |

`ui/scripts/build_manifest.py` と `ui/viewer/src/contract.ts` は §5 の JSON だけを契約とし、Python の名前には依存しない。

---

## 11. 実装の分割（ファイル所有は互いに素）

| key | 難易度 | 所有 |
| --- | --- | --- |
| `suite-cli` | moderate | `src/fukurou/scenario/**`, `src/fukurou/cli.py`, `src/fukurou/run/options.py`, `src/fukurou/schema.py`, `src/fukurou/versions.py`, `tests/test_scenario.py`, `tests/test_suite.py`, `tests/test_cli.py`, `tests/test_versions.py` |
| `contract-manifest` | moderate | `src/fukurou/result/model.py`, `src/fukurou/result/steps.py`, `docs/contract.md`, `ui/scripts/**`, `ui/fixtures/**`, `tests/test_contract.py` |
| `runner-core` | hard | `src/fukurou/run/{suite_run,session,isolation,artifacts,orchestrator}.py`, `src/fukurou/runner/{log_window,scenario_runner,player_session,client}.py`, `src/fukurou/server/process.py`, `src/fukurou/result/recorder.py`, `tests/test_run.py`, `tests/test_isolation.py`, `tests/test_log_window.py`, `tests/test_result.py` |
| `viewer` | hard | `ui/viewer/**`, `ui/dist/**` |
| `actions-ci-docs` | simple | `action.yml`, `ui/action.yml`, `versions/action.yml`, `.github/workflows/**`, `README.md`, `docs/usage.md`, `examples/**`, `schema/**`, `pyproject.toml`, `uv.lock`, `src/fukurou/__init__.py` |
| `minestamp-migration` | simple | MineStamp: `.github/workflows/game_test.yml`, `game-test/**` |

`runner-core` は `suite-cli`（`TestSpec` / `Suite`）と `contract-manifest`（`ResultV2`）の後。`actions-ci-docs` は schema 再生成と CI の緑化を担うので最後。`viewer` は本設計の manifest 例だけで着手できる。