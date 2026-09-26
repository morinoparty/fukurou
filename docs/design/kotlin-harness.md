# fukurou Kotlin ハーネス設計: JUnit 拡張と型付き API でゲームテストを書く

`kotlin/` にある Kotlin 版ハーネス（`com.github.morinoparty:fukurou`、JitPack）の設計。Python 版の run / session / result.v2 の振る舞いを移植し、テストを YAML のシナリオではなく Kotlin のコード（JUnit 6 の `suspend fun` テスト）で書けるようにする。

この文書は実装に合わせて整理した設計の要約であり、ファイル単位の構成や作業の分担は含まない。細部はコードの KDoc を正とする。

---

## 0. 結論

| # | 決定 | 理由 |
| --- | --- | --- |
| D1 | 公開 API は `suspend`。JUnit 6 が `suspend fun` のテストを自分の `runBlocking` で呼ぶ。 | 待ちの構造化と取り消し。利用側（MineStamp）はテストのクラスパスに kotlin-reflect と coroutines を持っている。 |
| D2 | `PlayerProfile`（データだけ）は `GameServer.join` を通して初めて `Player` になる。`ServerSpec`（ビルダー）は `ServerDefinition.start()` を通して初めて `GameServer` になる。 | 参加前の `teleport` や起動後の `plugins {}` がコンパイルできない。 |
| D3 | **サーバーの種類は戦略。** `ServerType` は公開の値（`Paper("26.3")`）で、`@FukurouSpi` の `ServerPlatform` を作る。ダウンロード・ディレクトリの準備・起動コマンド・起動完了の判定・参加の判定・コマンド経路・停止・**能力**（capability）は種類が持つ。エンジン（プロセス・ログ・クライアント・記録）は Paper のコマンド文字列を持たない。 | 後から Velocity と Minestom を足す（§6）。`EngineHasNoPaperStringsTest` が守る。 |
| D4 | 能力は `List<CommandCall>` を返す **純粋なコマンドの計画** にする。エンジンが経路で実行し、種類の `ResponseCheck` で応答を判定する。`CommandCall.route` にはプロキシ用の `PlayerBackend` を最初から置く。 | サーバー無しで単体テストできる。プロキシの経路を後から非破壊で足せる。 |
| D5 | **Adventure。** `Player : Audience, Identified`、`GameServer : ForwardingAudience`。ワールド・アイテム・音は `Key`。チャットの検査は `Component` も受け取る。種類が対応しない Audience の操作は何もしないのではなく `UnsupportedCapabilityException` を投げる。 | 利用者の要望。黙って何もしないと、タイトルの写っていないスクリーンショットで緑になる。 |
| D6 | `GameServerExtension` のサブクラス 1 つ = 独立したサーバー 1 台 = `result.json` 1 つ。リースは JUnit の **ルート** ストアに拡張のクラスをキーにして置き、その拡張を使う全テストクラスで共有する。メモリ予算を超えるなら起動前に止め、使われていないリースは LRU で退避する。 | 拡張ごとに独立したサーバー。16 GB の runner を OOM にしない。 |
| D7 | 引数の解決は **自分の拡張の型** と、fukurou の拡張がちょうど 1 つのときの `GameServer` だけ。`Player` は注入しない。 | `@ExtendWith` が 2 つあっても引数を取り合わない。 |
| D8 | **期限。** テストごとのソフトな期限（既定 600 秒、Python と同じ）をステップの前に確かめ、すべての待ちをその残り時間で打ち切る。JUnit の timeout は保険（15 分、**SAME_THREAD**）。SEPARATE_THREAD は xdotool を操作し続ける幽霊スレッドを残すので使わない。 | |
| D9 | 並行のレーンは Python に従う。失敗したレーンは兄弟を止めない。`ServerUnavailableException` は止める。期限はすべてを止め、30 秒の猶予の後も生きているレーンは見捨ててプレイヤーを stranded にする。ブロックのステップはレーン 0 から順にまとめて書く。 | parallel.py:1-14、contract.md:161。 |
| D10 | プロセスは `setsid` で起動し、`kill -TERM/-KILL -- -<pgid>` で止める。 | `start_new_session` + `killpg` と同じ。 |
| D11 | キーシムは `xmodmap -pke` で解決する。Xvfb の起動完了は `xdotool getdisplaygeometry`（本物の X のハンドシェイク）。 | |
| D12 | result id = `<type.id>-<version>-<label>`（例: `paper-26.3-stamp-arena`）。label は `^[a-z0-9]+(?:-[a-z0-9]+)*$`、40 文字まで。新しい任意項目は `label` と `fukurou.runner`。`schemaVersion` は 2 のまま。 | ビューアと PR コメントがそのまま動く。 |
| D13 | Java 21 のバイトコード（`options.release=21`、`-Xjdk-release=21`、toolchain 無し）。JUnit は `compileOnly` 6.0.0。`api`: coroutines 1.10.2、adventure-api / adventure-key 5.2.0。`implementation`: serialization-json 1.11.0、Adventure の gson / plain シリアライザ 5.2.0。SLF4J・YAML・JNA は使わない。 | JitPack の openjdk21 でビルドできる。依存を小さく保つ。 |
| D14 | エンジン（`engine.*`）は JUnit を使わない（`EngineDoesNotUseJUnitTest` がバイトコードで確かめる）。JUnit を使わない利用（`Fukurou` + `GameServer.test {}`）も同じ記録を行う。 | JUnit 以外の入口を残す。 |

---

## 1. 公開 API

ルートパッケージは `party.morino.fukurou`。すべての公開宣言は `explicitApi()` で可視性を明示する。以下は KDoc と `public` を省いた概要。

### 1.1 入口と設定

- `Fukurou(config)`: JVM 内で共有するキャッシュ・作業ディレクトリ・出力先・予算を持つルート。生成したサーバーはすべてこれが所有する。`Fukurou.shared` は JUnit 拡張が使う JVM 共有インスタンス。`player(name, op)` で参加前のプレイヤー、`server(type) { … }` で起動前の定義を作る。`close()` は生きているサーバーをすべて止めて `result.json` を確定する（冪等）。
- `FukurouConfig.fromSources(properties, env)`（純粋）: `fukurou.*` システムプロパティと `FUKUROU_*` 環境変数から作る。`acceptEula`、`workDir`、`outDir`、`serverJava`、`memoryBudgetMb`、`plugins`（`fukurou.plugin.<key>`）、`selectionTests` / `selectionTags`（記録のみ）、`missingHost`（`fail` / `skip`）、`keepWork`、`githubToken`（`toString` で伏せる）、`properties`（種類のファクトリ用の全 `fukurou.*`）。Paper 固有の項目は持たない。

### 1.2 サーバーの種類

```kotlin
interface ServerType {
    val id: String                          // result id の接頭辞と result.minecraft.server。^[a-z][a-z0-9]*$
    val minecraftVersion: MinecraftVersion  // 起動するクライアントのバージョン
    @FukurouSpi fun createPlatform(services: PlatformServices): ServerPlatform  // 1 サーバーにつき 1 回
}

data class Paper(
    val version: MinecraftVersion,
    val channel: PaperChannel = PaperChannel.Stable,   // 受け入れる最も不安定なチャンネル
    val build: Int? = null,                            // 固定するビルド
    val properties: Map<String, String> = emptyMap(),  // server.properties の上書き（fukurou が管理するキーは不可）
) : ServerType {
    companion object {
        /** fukurou.minecraftVersion / fukurou.paperChannel / fukurou.paperBuild を読む。 */
        fun fromProperties(config: FukurouConfig, defaultVersion: String? = null, defaultChannel: PaperChannel = PaperChannel.Stable): Paper
    }
}
```

`ServerType` は sealed にしない（別のモジュールで種類を足せるように）。`MinecraftVersion` は versions.py の順序づけの移植で、pre-release と snapshot を拒む。そのため `<type>-<version>-<label>` は一意に分解できる。`VersionSpec` は `1.21.9-` 形式の範囲で、`@MinecraftVersions` が使う。

### 1.3 サーバーの宣言と起動済みのサーバー

- `ServerSpec`（起動前だけ触れる、種類に共通の項目）: `label`、`isolation`（既定 `Isolation.Reset()`）、`startTimeout` 600 秒、`installTimeout` 900 秒、`joinTimeout` 900 秒、`testTimeout` 600 秒、`serverHeap` `2G`、`clientHeap` `1536M`、`plugins { … }`、`serverFiles(dir)`。
- `ServerDefinition`: 凍結した `ServerSpec`。`start()` で起動し、種類の起動完了の判定とプラグインの有効化の確認まで待つ。label の形式は作成時に検査する。**能力と設定の整合**（`plugins` があるのに `PluginSupport` が無い、`Isolation.Reset` なのに `ResetPlanner` が無い）は、セッションの起動時に種類が能力を結び付けた直後に検査し、違反は `SetupException`。
- `Isolation`: `Reset(arena = Arena(32, 24), gamemode, settle = 2s, spawns, normalizeView = true)`（既定、各テストの前にリセット）/ `FreshServer`（テストごとにサーバーを作り直す）/ `None`。`Arena` は `size * size * height <= 32768`（`gamerule` を使わずに `fill` の上限に収める）。

```kotlin
interface GameServer : ForwardingAudience, AutoCloseable {
    val type: ServerType
    val label: String
    val resultId: String                   // "<type.id>-<version>-<label>"
    val joinAddress: InetSocketAddress     // クライアントの接続先（127.0.0.1:<port>）
    val players: List<Player>              // 参加順
    val log: LogView                       // テストごとの窓を持つサーバーログ
    val backends: List<GameServer> get() = emptyList()   // プロキシの後ろのサーバー（§6）。Paper は常に空

    suspend fun join(profile: PlayerProfile): Player
    fun player(name: String): Player
    suspend fun command(command: String, check: CommandCheck = CommandCheck.FailOnError): CommandResponse
    suspend fun fill(from: BlockPos, to: BlockPos, block: String, world: Key = Worlds.OVERWORLD)
    suspend fun setBlock(at: BlockPos, block: String, world: Key = Worlds.OVERWORLD)
    suspend fun time(ticks: Long)
    suspend fun weatherClear()
    fun <C : Capability> capability(kind: KClass<C>): C?
    fun mark(): LogMark
    suspend fun awaitLog(pattern: Regex, timeout: Duration = 60.seconds, after: LogMark? = null): LogMatch
    fun assertNoLog(pattern: Regex, after: LogMark? = null)
    suspend fun <T> fixture(name: String, block: suspend GameServer.() -> T): T   // phase=fixture で記録
    suspend fun <T> test(id: String, name: String = id, tags: Set<String> = emptySet(), block: suspend GameServer.() -> T): T  // JUnit 無しの記録
    suspend fun stop()
}
```

- `command` の応答が種類の `ResponseCheck` でエラーなら `CommandFailedError`（`AssertionError`）。予期した応答なら `CommandCheck.ReturnRaw`。応答を返さない経路では `CommandResponse.text` は null。
- `Player`: `pressKey` / `pressChord` / `typeText` / `chat` / `sendCommand`（チャット欄から送り、サーバーログの `CommandEcho` を待つ）/ `perspective` / `screenshot` / `teleport` / `gamemode` / `op` / `deop` / `give` / `mark` / `awaitChat` / `assertNoChat`（`Regex` と `Component`）。
- 流れの補助（トップレベル関数）: `pause(d)`（記録される待ち）、`parallel { lane { … } }`、`screenshot(vararg players, name)`（1 人 1 レーンで同時に撮り、引数の順で返す）。

### 1.4 例外

`FukurouException` の下に `SetupException`（EULA・ホストのツール・設定・ダウンロード）、`ServerUnavailableException`（サーバーの死亡・RCON に届かない）、`ClientDiedException`、`InputException`（xdotool・キーマップ・フォーカス）、`HarnessTimeoutException`（ソフトな期限・ハーネスの待ち）。`UnsupportedCapabilityException` は `UnsupportedOperationException`。`LogAssertionError` と `CommandFailedError` は `AssertionError`。ログの待ちの失敗は、テスト開始以降の末尾の行と、1 文字違いの行（`NearMiss`）をメッセージに含める。

---

## 2. サーバーの種類の SPI と能力

`@FukurouSpi`（`RequiresOptIn`、ERROR）を付けた型は種類の実装者向けで、マイナーリリースで変わりうる。

```kotlin
@FukurouSpi interface PlatformServices {        // エンジンが種類に渡すサービス
    val config: FukurouConfig
    val cacheDir: Path
    suspend fun download(url: String, destination: Path, sha256: String? = null, headers: Map<String, String> = emptyMap()): Path
    suspend fun <T> withCacheLock(path: Path, block: suspend () -> T): T
    fun freePort(): Int                         // ポートの割り当てはエンジンが持つ
    fun log(message: String); fun warn(message: String)
}

@FukurouSpi interface ServerPlatform {          // 1 サーバーにつき 1 つ。セッションごとに provision → launch → ready → bind → stop
    val type: ServerType
    suspend fun provision(request: ProvisionRequest): Provisioned   // ダウンロード・ディレクトリ・設定。起動方法を返す（setsid はエンジンが足す）
    val readyPattern: Regex                                           // Paper: "Done ("
    suspend fun confirmReady(channel: CommandChannel?): Boolean       // Paper: RCON の list が通るまで
    val portConflictPattern: Regex? get() = null                      // Paper: "FAILED TO BIND TO PORT"（別のポートで再試行）
    fun joinedPattern(player: String): Regex                          // Paper: \b<name> joined the game
    fun openChannel(provisioned: Provisioned): CommandChannel?        // Paper: RCON
    val responseCheck: ResponseCheck
    fun bind(session: PlatformSession): Capabilities
    suspend fun requestStop(channel: CommandChannel?)                 // この後エンジンが待ち、プロセスグループを止める
    fun describe(provisioned: Provisioned): PlatformInfo              // result.minecraft / java に書く
}
```

- `ProvisionRequest`: `serverDir`（エンジンが事前に消す）、`sessionIndex`、`plugins`（解決・検査済みの `ResolvedPlugin`）、`serverFiles`、`maxPlayers`、`serverHeap`、`serverJava`。
- `Provisioned`: `command`、`workingDir`、`environment`、`joinAddress`、`channelEndpoint`（例: `Rcon(port, password)`。エンジンには不透明）、`bundlerLock`（Paper の bundler 展開のロック。起動完了まで持つ）。
- `CommandChannel`: `send(command)` と `repliesToCommands`。スレッドセーフであること。
- `CommandCall(command, ignore, player, route)`: `ignore` は応答にこの断片があればエラーにしない。`player` は `No player was found` のときに汚れたプレイヤーとして印を付ける相手。`route` は `Self` か `PlayerBackend(player)`（Paper は使わない）。
- `Capabilities.of(...)`: 各実装が実装する `Capability` のサブインターフェースをすべて登録し、型で引ける。`GameServer.require<C>()` は無ければ `UnsupportedCapabilityException`。

能力（`PluginSupport` 以外は純粋な計画）:

| 能力 | 内容 | Paper の実装 |
| --- | --- | --- |
| `PlayerCommands` | `teleport` / `gamemode` / `op` / `deop` / `give` | `execute in <world> run tp …`、`give <name> <item> <count>` など |
| `WorldCommands` | `fill` / `setBlock` / `time` / `weatherClear` | バニラのコマンド |
| `ResetPlanner` | テスト前のリセット（isolation.py:70 の移植） | 参加者全員。駐車なし |
| `AudienceCommands` | Adventure の操作（§3） | `tellraw` / `title` / `playsound` / `stopsound` / `bossbar` |
| `CommandEcho` | チャット欄から送ったコマンドのサーバーログ | `<name> issued server command: /<cmd>` |
| `PluginSupport` | プラグインの導入と有効化の確認（15 秒） | `plugins/` にコピー、plugin_checks.py の移植 |

共通と種類固有の分担:

| 関心 | 共通（エンジン） | 種類固有（`ServerPlatform` / 能力） |
| --- | --- | --- |
| プロセス | `setsid` での起動、コンソールログの保存、生存確認、グループの停止、シャットダウンフック | `Provisioned.command` / `environment`、`requestStop` |
| ポートとディレクトリ | `FreePort`、run ごとのディレクトリ、共有キャッシュとロック | 必要なポートと書き込み先（server.properties など） |
| ダウンロード | `Downloader`、`CacheLock`、プラグインの解決（url / github / file） | Paper の fill API など |
| 起動完了 | ポーリング、期限、起動前のプロセス終了の検出 | `readyPattern`、`confirmReady` |
| 参加 | Xvfb、PortableMC、参加の直列化、クライアントの生存と起動失敗の検出 | `joinedPattern`、`joinAddress` |
| コマンド | 経路での実行、ステップの記録、`CommandFailedError` | `openChannel`、`responseCheck`、各能力 |
| リセット | 計画の実行、汚れたプレイヤーの印、視点の正規化、settle | `ResetPlanner.plan` |
| 結果 | すべて | `describe()`（`minecraft` / `java`） |

---

## 3. Adventure との統合

| クラス | Adventure のインターフェース | 補足 |
| --- | --- | --- |
| `Player` | `Audience`, `Identified` | `identity()` = `Identity.identity(uuid)`。`pointers()` は `NAME` = 名前、`UUID` = オフライン UUID、`DISPLAY_NAME` = `Component.text(name)`、`LOCALE` = `Locale.US`（options.txt が `lang:en_us`）。 |
| `GameServer` | `ForwardingAudience` | `audiences()` = 参加中のプレイヤー。すべての操作を各プレイヤーへ転送する。0 人なら何もしない。 |
| `Location.world`、`Worlds.*`、`give` | `Key` | `execute in ${world.asString()} run …` |
| `awaitChat` / `assertNoChat(Component)` | `PlainTextComponentSerializer` | パターンは `\[CHAT\].*` + `Regex.escape(plain)` |

`Player` の Audience の操作は、(1) 種類の `AudienceCommands` を引き（無ければ `UnsupportedCapabilityException`）、(2) `CommandCall` を計画し、(3) すべての応答が返るまで呼び出し元のスレッドを **ブロック** し、(4) コマンドごとに `action = "command"`、`on = "server"` のステップを記録する。`AudienceDispatcher` は `StepScope`（ThreadLocal）を呼び出し元のスレッドで読んでから `runBlocking(Dispatchers.IO + scope)` に載せ直すので、phase・fixture・レーンが保たれる。

Paper の対応表:

| 操作 | Paper | 送るコマンド |
| --- | --- | --- |
| `sendMessage(Component)` | 可 | `tellraw <name> <json>` |
| `sendMessage(…, ChatType.Bound)` / `SignedMessage` / `deleteMessage` | **投げる** | 本物のチャットセッションが要る |
| `sendActionBar` | 可 | `title <name> actionbar <json>` |
| `showTitle` / `sendTitlePart` | 可 | `times`（あれば）→ `subtitle` → `title`（表示の契機なので最後） |
| `clearTitle` / `resetTitle` | 可 | `title <name> clear` / `reset` |
| `playSound(Sound)` / `(Sound, Emitter.self())` | 可 | `execute as <name> at @s run playsound …`。`seed` は無視 |
| `playSound(Sound, x, y, z)` | 可 | `playsound <key> <source> <name> <x> <y> <z> …` |
| `playSound(Sound, Emitter)`（self 以外） | **投げる** | 任意の発音体を指すコマンドが無い |
| `stopSound` | 可 | `stopsound <name> [<source>\|*] [<sound>]` |
| `showBossBar` / `hideBossBar` | 可 | バーのインスタンスごとに `fukurou:bar-<n>` を作り、色・形・値・見る人を同期。リスナーで変更を追う。`flags` は警告して無視。見る人がいなくなれば削除 |
| プレイヤーリストのヘッダー / フッター、`openBook`、リソースパック、ダイアログ | **投げる** | 対応するバニラのコマンドが無い |

- **黙って無視しない理由**: Adventure の約束は「非対応 = 何もしない」だが、それではタイトルの抜けたスクリーンショットでテストが通ってしまう。この逸脱は `Player` の KDoc に書く。
- **RCON の大きさの上限**: バニラの RCON は 1 パケットを 1460 バイトのバッファで読むので、`RconCodec` は 1446 UTF-8 バイトを超えるコマンドを送る前に `IllegalArgumentException` で拒む（切り詰めない）。
- **Adventure を使わないもの**: クライアントの入力（`pressKey`、`typeText`、`chat`、`sendCommand`）、正規表現のログ検査、`command(String)`。

---

## 4. JUnit 拡張

### 4.1 基底クラス

```kotlin
abstract class GameServerExtension :
    BeforeAllCallback, AfterAllCallback, BeforeEachCallback, BeforeTestExecutionCallback, AfterEachCallback,
    LifecycleMethodExecutionExceptionHandler, TestWatcher, ParameterResolver {
    protected open fun type(config: FukurouConfig): ServerType = Paper.fromProperties(config)
    protected abstract fun ServerSpec.configure()                 // label の既定はクラス名の kebab case（StampArena → stamp-arena）
    protected open suspend fun GameServer.onStarted() {}          // 起動・全員参加の直後（セッションごと）。harness.log にだけ残る
    protected open suspend fun GameServer.setUp() {}              // 各テストのリセットの後。phase=beforeEach
    protected open suspend fun GameServer.tearDown() {}           // 各テストの後。harness.log にだけ残る
    val server: GameServer
    protected fun player(name: String, op: Boolean = false): PlayerDelegate   // 宣言順 = 参加順
}
```

- `player(...)` の委譲は構築時にプロフィールを宣言し、参照すると現在のセッションの `Player` を返す（作り直しの後に古いハンドルを掴まない）。最初に `beforeAll` に来たインスタンスの宣言をリースに固定し、違う宣言のインスタンスは `IllegalStateException`。
- 注釈（`junit.annotation`）: `@GameTestId`（id の上書き）、`@GameTimeout(seconds)`（ソフトな期限）、`@FreshServer`（このテストの前に作り直す）、`@MinecraftVersions("1.21.9-")`（`MinecraftVersionCondition`。メソッド単位で評価し、何も起動せずに `type(config).minecraftVersion` と比べる。理由 `versions: <spec> does not include <version>` の skipped）。

### 4.2 リース・順序・メモリ予算

- **リース**: ルートストアの `computeIfAbsent(extensionClass, …, ServerLease::class.java)`。1 つの拡張のクラスにつき JVM で 1 つ = サーバー 1 台・`RunRecorder` 1 つ・`result.json` 1 つ。`ServerLease` は `AutoCloseable` で、JUnit がエンジンの実行の終わりに閉じる。後片付けの値を先にルートストアへ置くので、全リースの停止の後に `Fukurou` を閉じる。
- **作成時**: 定義を凍結し、`FukurouPlanListener` の計画（無ければ現在のクラスのテストを反射で）から、**ダウンロードより前に** stub の `result.json`（全テスト not run）を書く。この JVM の最初のリースを作る前に、計画に無い印付きの古い run ディレクトリを消す（前の実行の結果を artifact に混ぜない）。
- **クラスの順序**: `FukurouClassOrderer` は fukurou の拡張のクラス名を整列して連結した鍵、次にクラス名で並べる。同じサーバーを使うクラスが続けて走り、退避と作り直しが減る。
- **予算**（`MemoryBudget` は純粋、`LeaseRegistry` が適用）:
  - 見積もり = `serverHeap + 750 MB` + プレイヤー数 × (`clientHeap + 900 MB`)。既定で 1 人なら 5234 MB、2 人なら 7670 MB。
  - 予算 = `fukurou.memoryBudgetMb`、無ければ最初の取得時の `MemAvailable` の 9 割。
  - 起動前に「このリース + 現在のクラスが使用中のリース」が予算を超えるなら、内訳と `-Pfukurou.memoryBudgetMb` の案内付きの `ServerBudgetException`。何も起動せず、run の失敗（`setup`）として記録し、以後の取得にも同じ失敗を返す。
  - 収まるなら、使われていない（参照数 0）リースを最後に使った時刻の古い順に止める。退避したリースは次の取得で `fresh-server` のセッションとして起動し直し、`onStarted` を呼び直す。
  - 16 GB の runner では、既定のヒープで 2 人のサーバー 2 台は収まらない。1 つのクラスに 2 つの拡張を登録するなら、プレイヤーを減らすか、ヒープを下げる（fukurou 自身の e2e の `LobbyArena` / `DuelArena` は 1 人・`serverHeap = "1G"` で 2 台 8420 MB）。

### 4.3 コールバック

| コールバック | 振る舞い |
| --- | --- |
| `beforeAll` | `missingHost=skip` でホストのツールが無ければクラスごと中断。リースを取得（無ければ作成）し、プレイヤーの宣言の一致を確かめ、この拡張をクラスのストアの一覧に足す（`GameServer` 引数の規則用）。`LeaseRegistry.acquire` が参照数を増やし、予算を適用し、未起動なら起動・全員のインストール・順に参加・`onStarted`。失敗は `setup` / `server-start` / `client-join` の run の失敗として記録し、再試行しない。 |
| `beforeEach` | JVM の終了中なら記録せずに中断。リースが止められていれば（サーバーの死亡・再起動の失敗）理由付きの skipped。`prepareTest`（`@FreshServer` / `Isolation.FreshServer` の作り直し、汚れた・死んだクライアントの再起動。失敗なら残りのテストを理由付きの skipped）。セッションが変わったら `onStarted` を呼び直す（失敗は `setup` の run の失敗）。`beginTest(owner = JUnit の unique id)`、phase を `beforeEach` にして、リセットと `setUp`。ここで起きた例外は保持して投げ直し、`afterEach` が記録する（`BeforeEachCallback` の例外は `handleBeforeEachMethodExecutionException` に届かないため）。 |
| `beforeTestExecution` | 以後のステップを phase `test` で記録する。 |
| `handleBeforeEachMethodExecutionException` | 利用者の `@BeforeEach` の失敗をそのまま投げ直す。phase は `beforeEach` のままなので `afterEach` がその層で記録する。 |
| テスト本体 | JUnit の `runBlocking` が実行する。ステップは `StepScope`、無ければ `ActiveTests` から記録先を探す。 |
| `afterEach` | `tearDown`（失敗は harness.log だけ）。その後、必ず `finishTest(run, error)`（`NonCancellable`）: 結末の対応づけ（§5.4）、failed / error なら参加中の生きているプレイヤー全員の `failure.png`、クライアントログの回収と `logRanges`、サーバーの死亡の検出、`result.json` の書き直し。テストを閉じないと次の `beginTest` が失敗するので、ここは必ず閉じる。 |
| `testDisabled` | `@Disabled` / `@MinecraftVersions` を理由付きの skipped として残す。 |
| `afterAll` | 参照数を減らす。サーバーは退避かルートストアの close まで動かしたまま。 |
| ルートストアの close | サーバーを止め（クライアント → Xvfb → サーバー、ログを回収）、`finishedAt` / `summary` / `status` 付きの最終版を書く。 |

### 4.4 引数

- 自分の拡張の型は常に解決する（`arena: StampArena`）。
- `GameServer` は fukurou の拡張がちょうど 1 つのときだけ解決する。2 つ以上なら `"2 fukurou servers are registered on StampTest (Arena1, Arena2); declare the parameter as Arena1 or Arena2"`。`beforeAll` の前（`PER_CLASS` のコンストラクタ）は注釈から一覧を探し、「メソッドの引数を使う」案内を付ける。
- `Player` は注入しない（`arena.alice` のように型付きのプロパティで使う）。
- `@RegisterExtension` は static なフィールド（`companion object { @JvmField @RegisterExtension val arena = StampArena() }`）で使える。インスタンスのフィールドでは `beforeAll` が呼ばれないので、そのことを示す `ExtensionConfigurationException`。

### 4.5 計画とテストの識別

- `FukurouPlanListener`（`META-INF/services` の `TestExecutionListener`）が、フィルタ後の計画から拡張のクラス → テストの一覧を集める。拡張は `@ExtendWith`（繰り返し・メタ注釈・外側のクラスを含む）と `GameServerExtension` 型の `@RegisterExtension` フィールドから探す。stub は選択したテストだけを含む。
- id: `@GameTestId`、無ければメソッド名を `[^A-Za-z0-9_.-]` → `-` で整えたもの。テンプレートは `-<n>`。1 つの result の中で重なれば `<ClassSimpleName>.<id>`（計画順なので再現できる）。
- name は `@DisplayName`、無ければメソッド名。source は `junit:<fqcn>#<method>`、sha256 はテストクラスの `.class` のバイト列。tags はメソッドとクラスの JUnit タグ。

### 4.6 並行性

- `junit.jupiter.execution.parallel.enabled=false`、Gradle は 1 フォーク。レジストリ・予算・参加の直列化は JVM ごと。
- `ActiveTests.begin` は **別の持ち主** のテストが実行中なら拒む。1 つのテストクラスに 2 つの拡張があると、同じ JUnit のテスト（同じ unique id）が 2 台のサーバーで同時に始まるが、これは許す。スコープの無い `pause()` は実行中のすべてのテストに記録する。2 台目のサーバーのレーンのステップは、そのサーバーのテスト自身の並行ブロックに入る。
- JUnit の timeout（`junit.jupiter.execution.timeout.testable.method.default = 15 m`、SAME_THREAD）はソフトな期限の外側の保険。割り込みで `runBlocking` が取り消され、`timeout` の error として記録する。

### 4.7 中断（Gradle の取り消し・SIGTERM）

`ProcessRegistry` のシャットダウンフックは次の順に動く。

1. **止める前**: 各 `ServerInstance.interrupt()` が、実行中のテストを `skipped`（`interrupted`、途中の記録は残さない）にし、`ActiveTests` から外し、run の失敗 `{phase: interrupted, message: "interrupted during <test id | session n>"}` を記録する。
2. **プロセスの停止**: クライアント → Xvfb → サーバーの順に、1 つあたり 5 秒の猶予で止める。
3. **止めた後**: 各 `finishInterrupt()` がログを回収してセッションを閉じ、`result.json` を確定する。

先に中断を記録するのは、フックの間も JUnit のスレッドが動き続けるため。先にプロセスを止めると、実行中のステップがサーバーやクライアントの死亡を見て「サーバーが死んだ」と記録してしまう。

### 4.8 JUnit を使わない利用

```kotlin
suspend fun main() = Fukurou.fromSystemProperties().use { fukurou ->
    val server = fukurou.server(Paper("26.3", PaperChannel.Alpha)) { label = "scratch" }.start()
    val alice = server.join(fukurou.player("Alice", op = true))
    server.test("scratch-1") {
        alice.showTitle(Title.title(Component.text("hello"), Component.empty()))
        alice.screenshot("hello")
    }
}
```

`GameServer.test {}` は拡張と同じ開始・再起動・リセット・終了・書き直しを行う。

---

## 5. result.v2 への対応づけ

### 5.1 記録の境界

エンジンは `RunObserver` のイベント（`runPlanned`、`sessionStarted`、`serverReady`、`playerJoined`、`testStarted`、`resetFinished`、`stepStarted` / `stepFinished`、`blockFinished`、`screenshotTaken`、`testFinished`、`testSkipped`、`runFailed`、`sessionFinished`、`runFinished`）を出し、`RunRecorder` が唯一の実装として `ResultV2` を組み立てる。

### 5.2 ステップ

- 並行ブロックの外のステップは開始時に最終の index を得る。ブロックの中のステップは `BlockRecorder` がレーンごとに溜め、ブロックの終わりにレーン 0 から順に並べる（`startedAt` / `finishedAt` は実際の重なりのまま）。仮の id を参照する `failure.stepIndex` と `screenshots[].stepIndex` はそこで付け替える。
- `repeat` は常に null。失敗の後のステップは（命令的な API では分からないので）書かない。
- `phase` は `beforeEach` / `fixture`（`fixture` の名前付き）/ `test`。テストの外のステップは harness.log だけに残す。

| API | `on` | `action` | `label` |
| --- | --- | --- | --- |
| `command(c)`、`fill` などの糖衣、`teleport` などの能力、Audience の操作 | `server` | `command` | 実際のコマンド（先頭の `/` 無し） |
| `awaitLog` / `assertNoLog` | `server` | `wait_for_log` / `assert_no_log` | パターン |
| `pause(d)` | null | `wait` | `"1.5s"` |
| `pressKey` / `pressChord` | プレイヤー | `press_key` | `F5` / `F3+d` |
| `typeText` / `chat` | プレイヤー | `type_text` / `chat` | テキスト |
| `sendCommand(c)` | プレイヤー、次に `server` | `chat`、次に `wait_for_log` | `/c`、次に echo の正規表現 |
| `perspective(p)` | プレイヤー | `press_key` × n | `F5` |
| `screenshot(n)` | プレイヤー | `screenshot` | `n`（`screenshot` = artifact のパス） |
| `awaitChat` / `assertNoChat` | プレイヤー | `wait_for_log` / `assert_no_log` | `\[CHAT\].*` を含むパターン |

Python の action 文字列だけを使うので、ビューアと契約は変わらない。

### 5.3 ルートの項目

- `id` = `<type>-<version>-<label>`（JVM 内の重複は `-2`、`-3`…）。新しい任意項目 `label`。
- `fukurou` = `{version, portablemc, runner: "kotlin"}`（`runner` は新しい任意項目）。
- `minecraft` = `{version, server, build, channel}`。Kotlin のモデルは `server: String`（既定 `"paper"`）。Python のモデルは `Literal["paper"]` のまま（§6.4）。`java` = `{server: javaMajor}`。`plugins[]` = `{file, sha256, name, version, role, source, classFileMajor, enabled}`。
- `suite` = `{source: "junit:<拡張の FQCN>", sha256: <拡張の .class>, isolation, settle, gamemode, arena}`。`Isolation.None` は契約の enum に無いので `isolation: "reset"`、`settle: 0`、`arena: false` で表す。
- `selection` = `{tests, tags, isolation: null, failFast: false}`。`players[]` は宣言したプレイヤー（参加で `joined`）。`sessions[]` は `initial` の後に作り直しごとの `fresh-server`（`@FreshServer`・退避後の再取得）。
- `summary` と `status` は Python の `summarize_tests` / `derive_run_status` と同じ（`failure` があれば `error`、failed / error のテストがあれば `failed`、それ以外は `passed`）。`ci` は `GITHUB_ACTIONS=true` のときの `GITHUB_*`。
- 時刻は run とテストが秒精度、ステップがミリ秒精度。`Json { encodeDefaults = true; explicitNulls = true; prettyPrint = true }`。
- run ディレクトリ `<outDir>/<runId>/` の構成は contract.md と同じ（`result.json`、`tests/<id>/screenshots/<player>/<name>.png`、`logs/harness.log`、`logs/sessions/<n>/server.log`、`logs/sessions/<n>/clients/<player>[.<k>].log`、`crash-reports/`）。`.fukurou-out` の印の無いディレクトリは消さずに `SetupException`。

### 5.4 結末の対応づけ（`StatusMapper`、純粋）

| 例外 | `status` | `failure.phase` / `skipReason` | run の失敗 |
| --- | --- | --- | --- |
| 無し | `passed` | — | — |
| リセットの失敗 | `error` | `reset` | — |
| `TestAbortedException`（仮定・死んだサーバー・再起動の失敗・中断） | `skipped` | 理由 = メッセージ | — |
| `ClientDiedException`、またはクライアントが死んでいるときの `InputException` | `error` | `client` | — |
| `HarnessTimeoutException`、JUnit の `TimeoutException` | `error` | `timeout` | — |
| `ServerUnavailableException` | `failed` | `scenario` | `{phase: server, message: "server died during <id>: …"}`。以後のテストは `server died during <id>` の skipped |
| `AssertionError`（`LogAssertionError`、`CommandFailedError`、opentest4j） | `failed` | 失敗したステップの層、無ければテストが居た層（`beforeEach` / `fixture` / `scenario`） | — |
| **それ以外の例外**（テストのコードの例外など） | `failed` | 同じく **起きた層**（`beforeEach` / `fixture` / `scenario`） | — |

- 予期しない例外も `failed` にするのは Python（step_executor.py の `except Exception`）と同じで、contract.md §2 では `scenario` / `beforeEach` / `fixture` の段階は `failed` だけを取るため。ハーネス側の失敗（`error`）は `reset` / `client` / `timeout` に限る。
- メッセージは fukurou の例外と assert はそのまま、それ以外は型名を付ける。`InvocationTargetException` / `ExecutionException` の包みは外す。
- `failure.stepIndex` は最初に失敗したステップの最終の index（ステップに結び付かなければ null）。最初の失敗が勝つ。
- run の失敗の段階: `setup` / `server-start` / `client-join` / `server` / `teardown` / `interrupted`。

### 5.5 書き直しの時点

`ResultWriter` は `result.json.tmp` に書いて `force(true)` し、`ATOMIC_MOVE` で置き換える。書くのは、stub（最初の取得、ダウンロードより前）、テストごと、run の失敗ごと、セッションの終わり（停止・退避・作り直し）、リースの close、シャットダウンフック。

---

## 6. サーバーの種類の拡張（Velocity / Minestom）

エンジンは種類に依存する判断をすべて `ServerType` → `ServerPlatform` → 能力に通している。Velocity と Minestom は新しい `ServerType` の値と専用の platform として足し、別の Gradle モジュールや artifact にしてよい。`GameServer`・`Player`・JUnit 拡張・result の書き出しは §6.4 を除き変えない。以下は構想であり、まだ実装しない。

### 6.1 Velocity（バックエンドを持つプロキシ）

```kotlin
data class Velocity(
    val version: String,                               // Velocity のビルドの系列
    override val minecraftVersion: MinecraftVersion,   // 起動するクライアントのバージョン
    val backends: List<ServerDefinition>,              // 先に起動し、joinAddress を velocity.toml に書く
    val forwarding: Forwarding = Forwarding.Modern,
) : ServerType { override val id: String get() = "velocity" }
```

- **起動順**: バックエンドを先に起動する。バックエンドはそれぞれ自分の run id と `result.json` を持ち、label は `<proxyLabel>-<backendLabel>`。次にプロキシを `[servers]` がバックエンドの `joinAddress` を指す設定で起動する。`GameServer.backends` がそれらを返す。
- **参加**: クライアントはプロキシの `joinAddress` に接続する。`joinedPattern` はプロキシのログの接続行。バックエンドの参加も待つため、`ServerPlatform` に既定の実装付きの `suspend fun awaitRouted(player): GameServer` を足す（追加のみ）。
- **コマンド**: プロキシの経路は標準入力のコンソールで `repliesToCommands = false`（`Provisioned` に既定値付きの `stdin` を足す）。`PlayerCommands` / `WorldCommands` / `AudienceCommands` は `route = PlayerBackend(player)` の呼び出しを計画し、エンジンがプレイヤーの今いるバックエンドの経路で、Paper の `responseCheck` で実行する。ワールドのリセットは全バックエンドに広げる。
- **結果**: プロキシの result は `minecraft.server = "velocity"`、`minecraft.version` はクライアントのバージョン。バックエンドで実行したステップはテストの result（プロキシ）に `<backend>: ` を付けて記録する。

### 6.2 Minestom（利用者がビルドしたサーバー）

```kotlin
data class Minestom(
    val jar: Path,                                     // 利用者の shadowJar など
    val mainClass: String? = null,
    override val minecraftVersion: MinecraftVersion,
    val readyLog: Regex = Regex("Server started"),
    val joinedLog: (String) -> Regex = { Regex("${Regex.escape(it)} joined") },
    val console: Boolean = false,                      // 標準入力からコマンドを読むか
    val args: List<String> = emptyList(),              // "--port={port}" でポートを受け取る
) : ServerType { override val id: String get() = "minestom" }
```

- `provision` は何もコピーせず、`{port}` でポートを渡して `java -Xmx… -cp jar main args` を返す。
- `bind` は `PluginSupport` / `ResetPlanner` / `PlayerCommands` / `WorldCommands` / `AudienceCommands` を **返さない**（利用者がパターンを渡せば `CommandEcho` だけ）。その結果、`plugins {}` と `Isolation.Reset` は起動時の検査で `SetupException`（`Isolation.None` か `FreshServer` の案内付き）、`teleport` や `sendMessage` は `UnsupportedCapabilityException`。クライアントの入力・スクリーンショット・ログの検査はエンジン共通なので動く。
- 利用者のサーバーがコマンドを持つなら、将来 `Minestom(capabilities = { channel -> Capabilities.of(MyCommands(channel)) })` で独自の能力を渡せるようにする。

### 6.3 すでに入っている、非破壊にするための仕組み

- `ServerType` は sealed でないインターフェースで、`ServerSpec` に Paper の項目が無い。
- `CommandCall.route`、`CommandResponse.text: String?`、`CommandChannel.repliesToCommands`、`GameServer.backends`（既定は空）。
- 能力は型で引き、`require<C>()` がエラーメッセージを作る。能力と設定の整合は起動時に検査する。
- run id は `<type.id>-…`、Kotlin の `MinecraftInfo.server` は `String`、build_manifest の正規表現は `[a-z][a-z0-9]*` の接頭辞を受け付ける。

### 6.4 2 つ目の種類を出すときに要る契約の変更（今はしない）

- Python の `MinecraftInfo.server` を `Literal["paper"]` から `str` に広げ、スキーマと `contract.ts` を作り直す。既存の値の意味は変わらないので追加の変更。ビューアはそのまま表示する。
- プロキシの `minecraft.version` はクライアントのバージョン。`build` / `channel` はすでに null を許す。
- バックエンドを持つプロキシはサーバーごとに result を 1 つ作る。ビューアでまとめたくなれば `result.backends: [runId]` のような項目を追加で足す。
