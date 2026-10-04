# fukurou v3 設計: シナリオを捨てて Kotlin だけでテストを書く

v3 では JSON / YAML のシナリオと Python の実行系をすべて削除し、テストは Kotlin（JUnit 6）だけで書く。
同時に、ログの照合しかできなかった v2 の制約を外し、サーバーの状態の取得・イベントの待ち受け・サーバー上での任意コードの実行・
マウスと押しっぱなしの入力・条件待ちと画像比較を足す。

この文書は v3 で凍結した公開 API とエージェントのプロトコルの仕様である。v2.2 までの設計は `kotlin-harness.md` を参照する
（そこに書いた JUnit 拡張・リース・記録・result.v2 の対応づけは v3 でも変わらない）。

---

## 0. 結論

| # | 決定 | 理由 |
| --- | --- | --- |
| V1 | `src/fukurou`（Python）・`pyproject.toml`・`uv.lock`・`tests/`・`examples/`・`schema/scenario.*`・`schema/suite.*` を削除する。 | テストを書く手段を Kotlin の 1 つにする。 |
| V2 | `schema/result.v2.json` は手で保守する契約にする（pydantic からの生成をやめる）。`ResultSchemaTest` が Kotlin の出力と `ui/fixtures` をこの schema で検証する。`schemaVersion` は 2 のまま。 | ビューアと PR コメントを変えずに済む。`steps[].action` は enum ではないので新しいアクション名を足せる。 |
| V3 | **サーバー内エージェント**: Paper のプラグイン `FukurouAgent`（Java 17 のバイトコード、`api-version: 1.20`）を fukurou の jar に埋め込み、Paper の種類が毎セッション自動で入れる。テストの JVM とはループバックの TCP（1 行 1 JSON）で話す。RCON は使わない。 | RCON は 1446 バイトの上限と 1 パケットの応答しかない。状態の取得とイベントの配送に専用の経路が要る。 |
| V4 | エージェントで **状態の取得**（プレイヤー・ブロック・エンティティ・ワールド・tick）と **イベントの待ち受け**（任意の Bukkit / プラグインのイベントのクラス名）を行う。 | ログの照合に頼らない検査。 |
| V5 | **任意コードの実行**: 利用者の「コンパニオンプラグイン」が `party.morino.fukurou.agent.api.FukurouTasks.register(name, task)` で `FukurouTask` を登録し、テストは `server.execute(name, args)` で呼ぶ。タスクはメインスレッドで動き、戻り値は JSON で返る。API のクラス（Java 17）は fukurou の jar とエージェントの jar の両方に入れる。 | 別 JVM にラムダは送れない。名前で登録するのがクラスローダーに依存せず確実。 |
| V6 | **入力の拡張**: `keyDown` / `keyUp` / `holdKey` / `holding {}`、`mouseMove` / `click` / `holdMouse` / `scroll`、`selectHotbar`、`look`（サーバー側の回転）。 | 移動・ブロックの破壊と設置・GUI の操作。 |
| V7 | **流れの補助**: `step(label) {}`（任意のステップを記録）、`repeat(n) {}`（`RepeatInfo` を記録）、`eventually {}` / `awaitUntil {}`（期限内の再試行）、スクリーンショットの画素の取得と基準画像との比較。 | 条件待ちと独自の検査。 |
| V8 | **サーバーの Java の自動選択**: `fukurou.serverJava` の既定を `auto` にし、`max(Mojang の要求, プラグインの class major - 44)` と一致する JDK を使う。テストの JVM が一致すればそれを、違えば GitHub のランナーの `JAVA_HOME_<major>_X64`（arm は `_ARM64`）を、それも無ければ Temurin を Adoptium から `<workDir>/cache/jdks/temurin-<major>/` にダウンロードする。`current` で常にテストの JVM、パスで固定。 | 1 つのテスト JVM で 1.20〜26.x の行列を回す。アクションで JDK を選ばなくてよい。 |
| V9 | **versions の解決を Kotlin に移す**: `party.morino.fukurou.cli.FukurouCli`（`versions`）。`versions` アクションは `kotlin/gradlew fukurouCli` を実行する。並びは Mojang のマニフェストの順（数値の順ではない）。 | Python を消す。 |
| V10 | **ルートのアクション**は薄い Gradle 実行器にする: システムの依存とキャッシュ → JDK → `./gradlew <task>`（設定は `FUKUROU_*` 環境変数で渡す）→ artifact のアップロード → `jq` で `*/result.json` を集計した outputs とステップの要約。 | 利用者のビルドスクリプトに `-P` の転送を書かせない。 |
| V11 | 公開する Maven の成果物は `com.github.morinoparty:fukurou` の 1 つだけ。エージェント（`kotlin/agent`）と API（`kotlin/agent-api`）は公開しないサブプロジェクトで、成果物はコアの jar に入れる。 | JitPack は複数モジュールだと座標が変わる。 |
| V12 | `FukurouConfig` は既知のキーをすべて環境変数からも読む（`fukurou.minecraftVersion` ↔ `FUKUROU_MINECRAFT_VERSION`）。優先順位は v2 と同じ（プロパティ > 環境変数 > `.default`）で、環境変数と `.default` は既知のキーすべてに効く。解決した値は `FukurouConfig.properties` にも書き戻す（`Paper.fromProperties` などの種類のファクトリが同じ値を読めるように）。`fukurou.plugin.<key>` は既知のキーではないので、システムプロパティでしか渡せない。 | V10。 |

---

## 1. エージェント

### 1.1 配置と起動

- `kotlin/agent-api`（Java、`options.release = 17`、公開しない）: `party.morino.fukurou.agent.api` の `FukurouTask` / `TaskContext` / `FukurouTasks`。依存なし。
- `kotlin/agent`（Java、`options.release = 17`、公開しない）: Paper のプラグイン。`compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")`（`repo.papermc.io` はこのサブプロジェクトだけで使う）。jar に `agent-api` のクラスを含める。`plugin.yml` は `name: FukurouAgent`、`main: party.morino.fukurou.agent.FukurouAgentPlugin`、`api-version: "1.20"`、`load: STARTUP`。
- コアの jar はエージェントの jar を `party/morino/fukurou/engine/agent/fukurou-agent.jar` のリソースとして持ち、`agent-api` のクラスもそのまま持つ（利用者のコンパニオンプラグインが `compileOnly` でコンパイルできるように）。
- `Paper(..., agent = true)`。`PaperPlatform.provision` は `agent` が true なら
  1. リソースの jar を `plugins/fukurou-agent.jar` へコピーし、
  2. `plugins/FukurouAgent/agent.properties` に `port=<freePort>`、`token=<32 桁の 16 進>`、`bind=127.0.0.1` を書き、
  3. `Provisioned.agentEndpoint = AgentEndpoint(port, token)` を返す。
- エンジン（`ServerInstance.start`）はプラグインの確認の後、`agentEndpoint` があれば `AgentClient` で接続し `hello` が通るまで 1 秒ごとに再試行する（`startTimeout` の残り時間まで）。通らなければ `server-start` の run の失敗。セッションの停止で閉じる。
- エージェントが無い（`agent = false`、または種類が `agentEndpoint` を返さない）サーバーで状態・イベント・execute を使うと `UnsupportedCapabilityException(type.id, "agent")`。

### 1.2 プロトコル（version 1）

- 経路: `127.0.0.1:<port>` の TCP。エージェントが待ち受け、複数の接続を受け付ける。エンジンはセッションごとに 1 本の接続を張り続ける。
- 枠: UTF-8 の JSON を 1 行 1 メッセージ（`\n` 区切り。JSON の中に生の改行は出ない）。1 行は 16 MiB まで。
- 最初のメッセージは必ず `hello`。トークンが違えば `error.type = "auth"` を返して閉じる。

```text
→ {"id":0,"op":"hello","token":"<token>","protocol":1}
← {"id":0,"ok":true,"result":{"protocol":1,"agentVersion":"3.0.0","serverName":"Paper","serverVersion":"<Bukkit.getVersion()>","minecraftVersion":"<Bukkit.getMinecraftVersion()>"}}

→ {"id":N,"op":"<op>", ...引数}
← {"id":N,"ok":true,"result":<JSON>}
← {"id":N,"ok":false,"error":{"type":"<種類>","message":"…","exception":"<例外のクラス名|null>","stackTrace":"<文字列|null>"}}

← {"event":{"subscription":S,"type":"<起きたイベントのクラスの FQCN>","tick":T,"cancelled":<bool|null>,"fields":{…}}}
```

- `error.type`: `auth` / `bad_request`（引数の誤り・未知の op）/ `not_found`（プレイヤー・ワールド・タスク・イベントのクラスが無い）/ `task_failed`（タスクが投げた）/ `timeout`（`timeoutMs` 以内にメインスレッドで実行できなかった、または `CompletionStage` が完了しなかった）/ `internal`（`execute` の戻り値を JSON に変換できなかったときを含む）。
- イベントの `type` は実際に起きたイベントのクラス（購読した型のサブクラスのこともある）。テスト側での振り分けは `type` ではなく `subscription` の番号で行う。
- テスト側の例外への読み替え: `auth` / `bad_request` / `not_found` は `AgentRequestException(type)`、`task_failed` は `ServerTaskException`、`timeout` は `HarnessTimeoutException`、それ以外（`internal`）は `FukurouException`。エージェントとの接続が切れて張り直した後、送り直さない要求（`execute`）は `AgentRequestException("connection_lost")` になる（エージェントが返す型ではなく、テスト側で作る）。
- サーバーの状態に触れる op はすべてメインスレッドで実行する（`Bukkit.getScheduler().callSyncMethod` → `get(timeoutMs)`）。`timeoutMs` は各リクエストに付けられ、無ければ 30000。

| op | 引数 | result |
| --- | --- | --- |
| `ping` | — | `{"tick": <Bukkit.getCurrentTick()>}` |
| `awaitTicks` | `ticks`（1〜72000） | 指定の tick 数の後に `{"tick": T}`。待つのは `ticks × 50 ms + timeoutMs`（`timeoutMs` はサーバーの遅れの余裕で、tick の時間は含めない） |
| `player` | `name` | PlayerSnapshot（オンラインでなければ `not_found`） |
| `block` | `world`, `x`, `y`, `z` | BlockSnapshot |
| `world` | `world` | WorldSnapshot |
| `entities` | `world?`, `type?`（`minecraft:zombie`）, `near?`（`{"x","y","z","radius"}`）, `tag?`, `limit?`（既定 256、最大 4096） | `[EntitySnapshot]`（プレイヤーを含む） |
| `plugins` | — | `[{"name","version","enabled"}]` |
| `tasks` | — | `["name", …]`（登録済みのタスク名、整列済み） |
| `execute` | `task`, `args`（任意の JSON か null） | `{"value": <JSON|null>}` |
| `subscribe` | `subscription`（クライアントが決める整数）, `types`（FQCN か単純名の配列） | `{"types": [解決した FQCN]}`。1 つでも見つからなければ `not_found` で何も登録しない |
| `unsubscribe` | `subscription` | `{}` |

- `world` はキー（`minecraft:overworld`、`minecraft:the_nether`、`minecraft:the_end`、プラグインのワールドは `<namespace>:<key>`）。`Bukkit.getWorld(NamespacedKey)` で引く。
- イベントのクラスの解決: FQCN ならそのまま、単純名なら `org.bukkit.event.{block,player,entity,inventory,server,world,weather,vehicle,hanging,enchantment,raid}`・`io.papermc.paper.event.{player,block,entity,server,world}`・`com.destroystokyo.paper.event.{player,block,entity,server}` の順に探す。見つからなければ全プラグインのクラスローダーで FQCN を探す。`HandlerList` を持たない抽象クラス（`PlayerEvent` など）は `bad_request`。
- イベントは `EventPriority.MONITOR`、`ignoreCancelled = false` で登録し、イベントが起きたスレッドで直ちに直列化して、接続ごとの送信キューに積む。

### 1.3 スナップショットの JSON

`Component` は Adventure の GSON 形式の JSON（Paper の `GsonComponentSerializer.gson()`）。キーは `minecraft:diamond` のような名前空間付きの文字列。

```text
Location        {"world":"minecraft:overworld","x":0.5,"y":-60.0,"z":0.5,"yaw":0.0,"pitch":0.0}
Item            {"type":"minecraft:diamond","amount":1,"name":<Component|null>,"lore":[<Component>],"customModelData":<int|null>,
                 "enchantments":{"minecraft:sharpness":5},"damage":<int|null>,"unbreakable":false}
PlayerSnapshot  {"name","uuid","location":Location,"gameMode":"survival","health":20.0,"maxHealth":20.0,"food":20,"saturation":5.0,
                 "level":0,"exp":0.0,"flying":false,"sneaking":false,"sprinting":false,"op":false,"selectedSlot":0,
                 "inventory":[Item|null ×41],            // getContents(): 0-35 本体（0-8 ホットバー）、36-39 防具（足→頭）、40 オフハンド
                 "openInventory":{"type":"CHEST","title":<Component>,"size":27,"contents":[Item|null]}|null,  // 自分のインベントリ（CRAFTING / CREATIVE）なら null
                 "effects":[{"type":"minecraft:speed","amplifier":0,"duration":200}],"tags":["…"]}
BlockSnapshot   {"world","x","y","z","type":"minecraft:oak_stairs","data":"minecraft:oak_stairs[facing=east,…]"}
EntitySnapshot  {"uuid","type":"minecraft:zombie","location":Location,"name":<Component|null>,"customName":<Component|null>,
                 "tags":["…"],"health":<double|null>,"dead":false}
WorldSnapshot   {"key","name","time":6000,"fullTime":6000,"storm":false,"thundering":false,"difficulty":"peaceful","players":["Alice"]}
```

### 1.4 イベントと戻り値の直列化

イベントの `fields` と `execute` の戻り値は同じ変換器で JSON にする。

- `null` / 文字列 / 数値 / 真偽値はそのまま（`NaN` と無限大は JSON に書けないので文字列）。`Enum` は `name()`（ただし `Keyed` を先に見る。下を参照）。`UUID` は文字列。`Component` は Component の JSON。
- `Player` は名前の文字列、その他の `Entity` は EntitySnapshot、`Location` は Location、`Block` は BlockSnapshot、`ItemStack` は Item、`World` はキーの文字列、`Keyed` はキーの文字列。`Keyed` は `Enum` より先に見る（`Material` などが enum からインターフェースに変わっても同じ出力になるように）。
- `Collection` / 配列は最大 64 要素のリスト、`Map` は文字列キーの最大 64 要素のオブジェクト。深さは 4 まで（超えたら `toString()`）。
- `execute` の戻り値は、Gson の `JsonElement` ならそのまま、それ以外で上の規則に当たらないものは `Gson.toJsonTree`（失敗すれば `toString()`）。
- イベントのプロパティは、引数の無い public なメソッドのうち `getX` / `isX`（`getHandlers` / `getHandlerList` / `getEventName` / `isAsynchronous` / `getClass` を除く）。名前は接頭辞を外して先頭を小文字にする（`getPlayer` → `player`、`isCancelled` → `cancelled`）。値の取得が投げたプロパティは省く。

### 1.5 任意コードの実行（コンパニオンプラグイン）

```java
// 利用者のコンパニオンプラグイン（plugin.yml に depend: [FukurouAgent, <テスト対象>]）
public final class MyTestHooks extends JavaPlugin {
    @Override public void onEnable() {
        FukurouTasks.register("stamp-count", ctx -> StampPlugin.instance().stamps().size());
        FukurouTasks.register("give-stamp", ctx -> {
            GiveArgs args = ctx.args(GiveArgs.class);   // Gson で引数を読む
            Bukkit.getPlayerExact(args.player).getInventory().addItem(StampItems.create(args.id));
            return null;
        });
    }
}
```

```kotlin
val count = server.execute<Int>("stamp-count")   // 引数なしでは型引数が必須（val count: Int = server.execute("stamp-count") はメンバーの JsonElement 版に解決されてコンパイルできない）
server.execute<GiveArgs, Unit>("give-stamp", GiveArgs("Alice", "smile"))    // null を返すタスクは Unit で受けられる（値を読まない）
```

- `FukurouTask.run(TaskContext)` はメインスレッドで動く。戻り値が `CompletionStage` なら完了まで待つ（`timeoutMs` まで）。
- `FukurouTasks` は static な登録簿（同じクラスがエージェントの jar から読まれる。コンパニオンプラグインは `depend: [FukurouAgent]` でエージェントのクラスを見る。API のクラスを shade してはいけない）。同じ名前の二重登録は `IllegalStateException`。プラグインの無効化で消すには `unregister`。
- タスクの例外は `task_failed` になり、テスト側では `ServerTaskException`（`AssertionError` のサブクラスではない。テストの failed として記録される）。タスクの中で投げた `AssertionError` は `ServerTaskException` の `remoteType` に `java.lang.AssertionError` と残る。

---

## 2. 公開 API の追加

### 2.1 サーバーの状態とイベント（`party.morino.fukurou.state` / `party.morino.fukurou.event`）

```kotlin
interface GameServer {
    // … v2 のまま …
    suspend fun block(at: BlockPos, world: Key = Worlds.OVERWORLD): BlockSnapshot     // step: server / query / "block x y z"
    suspend fun entities(query: EntityQuery = EntityQuery()): List<EntitySnapshot>     // step: server / query / "entities …"
    suspend fun worldState(world: Key = Worlds.OVERWORLD): WorldSnapshot               // step: server / query / "world <key>"
    suspend fun currentTick(): Long                                                    // step: server / query / "tick"
    suspend fun awaitTicks(ticks: Int)                                                 // step: server / wait_ticks / "20"
    val events: ServerEvents
    suspend fun execute(task: String, args: JsonElement? = null, timeout: Duration = 30.seconds): JsonElement  // step: server / execute / task
}
suspend inline fun <reified R> GameServer.execute(task: String, timeout: Duration = 30.seconds): R
suspend inline fun <reified A, reified R> GameServer.execute(task: String, args: A, timeout: Duration = 30.seconds): R

interface Player { suspend fun state(): PlayerSnapshot }                                // step: server / query / "player <name>"

interface ServerEvents {
    suspend fun record(vararg types: String): EventRecorder                            // step: server / subscribe / types
    suspend fun await(type: String, timeout: Duration = 60.seconds, predicate: (ServerEvent) -> Boolean = { true }): ServerEvent
    suspend fun expect(type: String, timeout: Duration = 60.seconds, predicate: (ServerEvent) -> Boolean = { true }, action: suspend () -> Unit): ServerEvent
}
interface EventRecorder : AutoCloseable {
    val types: List<String>
    fun events(): List<ServerEvent>                                                    // これまでに届いたもの（届いた順）
    suspend fun await(timeout: Duration = 60.seconds, predicate: (ServerEvent) -> Boolean = { true }): ServerEvent   // step: server / await_event
    suspend fun awaitCount(count: Int, timeout: Duration = 60.seconds, predicate: (ServerEvent) -> Boolean = { true }): List<ServerEvent>
    fun assertNone(predicate: (ServerEvent) -> Boolean = { true })                    // step: server / assert_no_event
    suspend fun assertNoneWithin(duration: Duration, predicate: (ServerEvent) -> Boolean = { true })
    override fun close()
}
```

- `await`（`EventRecorder`）は既に届いたイベントも対象にする。`ServerEvents.await` は呼んだ後に起きたイベントだけを見る（購読してから待つ）。取りこぼさないためには `record` してから操作するか `expect` を使う。
- 記録器はテストの終わり（`finishTest`）とセッションの停止で自動的に閉じる。
- 待ちはすべてテストの期限とサーバーの生存を確かめる（`LogWaiter` と同じ）。待ちが期限より先に切れたら `EventAssertionError`（`AssertionError`）、テストの期限なら `HarnessTimeoutException`。
- エージェントとの接続が切れたら、プロセスが死んでいれば `ServerUnavailableException`、生きていれば 1 回だけ張り直し、それも失敗すれば `ServerUnavailableException`。張り直した後、既に実行されたかもしれない要求（`execute`）は送り直さず `AgentRequestException("connection_lost")` にする（イベントの購読は張り直した接続で購読し直し、その間のイベントは欠けうると harness.log に警告する）。
- `execute<R>(task)`（引数なし）は型引数を明示して呼ぶ（`server.execute<Int>("t")`）。`val x: Int = server.execute("t")` は既定の引数を持つメンバーの `execute(task, args, timeout): JsonElement` に解決され、コンパイルできない。引数ありの `execute<A, R>` は宣言した型から推論できる。

### 2.2 入力（`Player`）

```kotlin
suspend fun keyDown(key: KeySym); suspend fun keyUp(key: KeySym)                   // press_key / "w down" / "w up"
suspend fun holdKey(key: KeySym, duration: Duration)                               // hold_key / "w 2s"
suspend fun <T> holding(keys: List<KeySym>, block: suspend () -> T): T            // keyDown… → block → keyUp（finally、NonCancellable）
suspend fun <T> holding(key: KeySym, block: suspend () -> T): T                  // KeySym は value class なので vararg にできない
suspend fun mouseMove(x: Int, y: Int)                                              // mouse_move / "640,360"（1280x720 のウィンドウ座標）
suspend fun click(button: MouseButton = MouseButton.LEFT)                          // click / "left"
suspend fun click(x: Int, y: Int, button: MouseButton = MouseButton.LEFT)          // mouse_move + click
suspend fun holdMouse(button: MouseButton, duration: Duration)                     // hold_mouse / "left 1.5s"
suspend fun scroll(steps: Int)                                                     // scroll / "3"（正で下、負で上）
suspend fun selectHotbar(slot: Int)                                                // press_key / "1"〜"9"（slot は 0〜8）
suspend fun look(yaw: Float, pitch: Float)                                         // サーバー側の回転（PlayerCommands.rotate）
suspend fun attack() = click(MouseButton.LEFT); suspend fun useItem() = click(MouseButton.RIGHT)
```

- 押したままのキーとボタンは、テストの終わりに必ず離す（例外・取り消し・期限でも）。離せなかったプレイヤーは汚れた（dirty）として次のテストの前に起動し直す。
- `KeySym` に `W` `A` `S` `D` `E` `Q` `F` `SHIFT` `CONTROL` `BACKSPACE` `UP` `DOWN` `LEFT` `RIGHT` `DIGIT_1`〜`DIGIT_9` を足す。

### 2.3 流れの補助（ルートパッケージ）

```kotlin
suspend fun <T> step(label: String, block: suspend () -> T): T                     // on null / step / label。中のステップも記録する
suspend fun repeat(times: Int, block: suspend (index: Int) -> Unit)                // 中のステップに RepeatInfo(block, iteration, of) を付ける
suspend fun <T> eventually(timeout: Duration = 10.seconds, interval: Duration = 500.milliseconds, block: suspend () -> T): T  // eventually
suspend fun awaitUntil(description: String, timeout: Duration = 30.seconds, interval: Duration = 500.milliseconds, condition: suspend () -> Boolean)  // await_until
```

- `eventually` は `block` が `AssertionError`（`LogAssertionError` などを含む）を投げる間、`interval` ごとに再試行し、`timeout` で最後の `AssertionError` を投げ直す。`awaitUntil` は `condition` が true になるまで待ち、だめなら `AssertionError("timed out after … waiting until <description>")`。
- どちらも 1 つのステップとして記録し、中の試行のステップは記録しない（`StepScope.quiet`）。テストの期限が先に来れば `HarnessTimeoutException`。fukurou の例外（サーバー・クライアントの死亡など）は再試行せずにそのまま投げる。
- `repeat` は 0〜1000 回（0 回は kotlin.repeat と同じく何もしない）。入れ子にできない（入れ子なら `IllegalStateException`）。`parallel` の中でも使える。`import party.morino.fukurou.*` ではスター import が既定の import より優先されるので、そのファイルの修飾しない `repeat` はすべて fukurou の `repeat` になる（ただの繰り返しは `kotlin.repeat` と書く）。

### 2.4 画像（`party.morino.fukurou.image`）

```kotlin
data class Region(val x: Int, val y: Int, val width: Int, val height: Int)
data class Rgb(val red: Int, val green: Int, val blue: Int) { fun distanceTo(other: Rgb): Int }   // 各チャンネルの差の最大
fun Screenshot.image(): BufferedImage
fun Screenshot.pixel(x: Int, y: Int): Rgb
fun Screenshot.averageColor(region: Region): Rgb
data class ImageDiff(val differentPixels: Int, val totalPixels: Int, val diffImage: BufferedImage) { val ratio: Double }
object ImageComparison { fun compare(actual: BufferedImage, expected: BufferedImage, channelTolerance: Int = 16, ignore: List<Region> = emptyList()): ImageDiff }
suspend fun Screenshot.assertMatches(baseline: Path, maxDifferentRatio: Double = 0.02, channelTolerance: Int = 16, ignore: List<Region> = emptyList())   // step: <player> / compare_screenshot / baseline のファイル名
```

- `assertMatches` は差が `maxDifferentRatio` を超えたら `<name>.diff.png` をスクリーンショットの隣に書いて `AssertionError`。大きさが違えば差は 100%。
- 基準画像が無いとき、または `fukurou.updateBaselines=true` のときは、スクリーンショットを基準画像として書いて成功する（無いときは harness.log に警告）。

---

## 3. サーバーの Java（`fukurou.serverJava`）

| 値 | 振る舞い |
| --- | --- |
| `auto`（既定） | 必要な major = `max(Mojang の javaVersion.majorVersion, プラグインの class major - 44)`。テストの JVM の major と一致すればそれ、一致しなければ GitHub のランナーの `JAVA_HOME_<major>_X64`（arm は `_ARM64`）に `bin/java` があればそれ、それも無ければ `<workDir>/cache/jdks/temurin-<major>/` の Temurin（無ければ Adoptium の API からダウンロードし SHA-256 を確かめて展開。キャッシュロックを取る）。 |
| `current` | テストの JVM の java。v2 の既定と同じ（足りなければ `JavaCheck` が `SetupException`）。 |
| パス | その java。 |

`FukurouConfig.serverJava` は `ServerJava`（`Auto` / `Current` / `Path(path)`）になる。

---

## 4. アクション

- `morinoparty/fukurou@v3`: 入力は `accept-eula`、`minecraft-version`、`paper-channel`、`paper-build`、`working-directory`、`gradle-task`（既定 `gameTest`）、`gradle-args`、`java-version`（Gradle を動かす JDK、既定 21）、`work-dir`、`out-dir`、`upload-artifact`、`artifact-name`、`retention-days`、`github-token`、`skip-system-deps`。出力は `result` / `tests-summary` / `failed-tests` / `result-file`（最初の result.json）/ `artifact-name` / `out-dir`。`failed-tests` は v2 の素の id ではなく `<run の label か id>/<test id>`（1 つの artifact に複数の run があるため）。
- `morinoparty/fukurou/versions@v3`: 入出力は v2 と同じ。`actions/setup-java`（21）と `kotlin/gradlew -q fukurouCli --args=…` で動く。
- `morinoparty/fukurou/setup@v3`: v2 と同じ（自分で Gradle を呼ぶ利用者向け）。
- `morinoparty/fukurou/ui@v3`: v2 と同じ。
