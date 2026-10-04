# fukurou

fukurou is an in-game test harness for Minecraft [Paper](https://papermc.io/) plugins. You write the tests in Kotlin with JUnit 6. For one Minecraft version, fukurou starts a real Paper server with your plugin and joins it with one or more real vanilla Minecraft clients. The tests then drive the clients (keyboard, mouse, chat), run console commands, read the server's state through an in-server agent, wait for Bukkit events, and run code of your own on the server's main thread. fukurou saves screenshots, logs and a machine-readable `result.json`, and a static viewer turns the results of every tested version into one page.

It runs on GitHub Actions (or any Linux x86_64 machine) without a GPU or a Microsoft account:

- **Server:** Paper in offline mode on a flat world. Console commands go over RCON. A small agent plugin, `FukurouAgent`, is installed automatically and answers state queries, streams events and runs tasks over a loopback socket. Its Java version is picked automatically, and a matching JDK is downloaded if the test JVM is not the right one.
- **Clients:** vanilla clients installed with [PortableMC](https://github.com/theorzr/portablemc) (a pinned version, checked against its SHA-256). Each player gets its own Xvfb display, and input is sent with `xdotool`. Players join one at a time with Quick Play. Tests without players need no display at all.
- **Versions:** a version spec such as `1.21.6-` is resolved against Mojang's version manifest and the Paper API, so each version can run as its own matrix job.

> [!IMPORTANT]
> **v3 is Kotlin only.** The Python runner and the JSON/YAML scenario and suite files are gone. If you are coming from `@v2`, see [Migrating from v2](#migrating-from-v2).

fukurou has four GitHub Actions in one repository, released together under one tag, plus the JVM library:

| Piece | What it does |
| --- | --- |
| `com.github.morinoparty:fukurou` (JitPack) | The Kotlin library: the JUnit 6 extension `GameServerExtension`, the player, server, agent, event, flow and image APIs. |
| `morinoparty/fukurou/versions@v3` | Resolves a version spec to a JSON array for a job matrix. |
| `morinoparty/fukurou@v3` | Runs your Gradle test task against one Minecraft version and uploads the results as an artifact. |
| `morinoparty/fukurou/ui@v3` | Builds the viewer site from the artifacts of every version and uploads it to S3-compatible storage. |
| `morinoparty/fukurou/setup@v3` | Installs the system packages and restores the cache, for workflows that call Gradle themselves. |

See [docs/usage.md](docs/usage.md) for every input, output, configuration key and API, and [docs/contract.md](docs/contract.md) for the format of `result.json` and `manifest.json`.

## Quick start

### 1. Gradle

Add a `gameTest` test suite to your plugin's build. It needs the fukurou library from JitPack, JUnit 6, and the path of your plugin jar:

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

testing.suites.register<JvmTestSuite>("gameTest") {
    useJUnitJupiter("6.0.0") // fukurou needs JUnit 6
    dependencies {
        implementation("com.github.morinoparty:fukurou:v3.0.0") // an exact tag; JitPack caches the first build of a tag forever
    }
    targets.configureEach {
        testTask.configure {
            // The plugin under test. PluginSource.systemProperty("myplugin") reads -Dfukurou.plugin.myplugin.
            // Use your shadow/shaded jar task here if you have one.
            val pluginJar = tasks.jar.flatMap { it.archiveFile }
            inputs.file(pluginJar).withPropertyName("pluginUnderTest") // also makes this task depend on the jar task
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Dfukurou.plugin.myplugin=${pluginJar.get().asFile.absolutePath}")
            })
            // Forward -Pfukurou.* from the Gradle command line to the test JVM (handy locally)
            val forwarded = providers.gradlePropertiesPrefixedBy("fukurou.")
            jvmArgumentProviders.add(CommandLineArgumentProvider { forwarded.get().map { (k, v) -> "-D$k=$v" } })
            // One JVM runs every server; the results must be written on every run
            // (never UP-TO-DATE, never restored from the build cache)
            maxParallelForks = 1
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}
```

The library is Java 21 bytecode, so the `gameTest` suite must compile for and run on Java 21 or newer, with Kotlin 2.2 or newer. Its API is `suspend`; JUnit 6 runs `suspend` test methods itself (fukurou brings in `kotlin-reflect` for that). Add the `kotlin("plugin.serialization")` compiler plugin if you pass `@Serializable` classes to `server.execute` (see [Running code on the server](#running-code-on-the-server)).

Add `src/gameTest/resources/junit-platform.properties`, so that test classes sharing a server run back to back, tests never run in parallel, and a test that hangs outside fukurou's own waits is still stopped:

```properties
junit.jupiter.testclass.order.default=party.morino.fukurou.junit.platform.FukurouClassOrderer
junit.jupiter.execution.parallel.enabled=false
junit.jupiter.execution.timeout.testable.method.default=15 m
```

### 2. A server and a test

One `GameServerExtension` subclass is one server. Declare its players and plugins, then use it from any number of test classes:

```kotlin
// src/gameTest/kotlin/StampArena.kt
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.server.*
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.world.BlockPos
import kotlin.time.Duration.Companion.seconds

class StampArena : GameServerExtension() {
    val alice by player("Alice", op = true)
    val bob by player("Bob")

    // The action sets FUKUROU_MINECRAFT_VERSION / FUKUROU_PAPER_CHANNEL, which override these defaults in CI
    override fun type(config: FukurouConfig): ServerType = Paper.fromProperties(config, defaultVersion = "1.21.11")

    override fun ServerSpec.configure() {
        label = "stamp-arena" // the result id becomes paper-1.21.11-stamp-arena
        plugins { underTest(PluginSource.systemProperty("myplugin")) }
        isolation = Isolation.Reset(settle = 3.seconds)
    }

    // Runs after the reset before every test, recorded as phase "beforeEach"
    override suspend fun GameServer.setUp() {
        fill(BlockPos(-3, -61, -3), BlockPos(3, -61, 3), "minecraft:stone")
    }
}
```

```kotlin
// src/gameTest/kotlin/StampTest.kt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.event.BukkitEvents
import party.morino.fukurou.screenshot
import party.morino.fukurou.server.GameServer
import org.junit.jupiter.api.Assertions.assertEquals

@ExtendWith(StampArena::class)
class StampTest {
    @Test
    suspend fun `stamp-thinking-face`(arena: StampArena, server: GameServer) {
        val alice = arena.alice
        val bob = arena.bob

        server.command("time set noon")                        // RCON; fails the test if the reply is an error
        server.events.record(BukkitEvents.PLAYER_COMMAND_PREPROCESS).use { commands ->
            alice.sendCommand("st :thinking-face:")             // types it in chat and waits for "issued server command"
            commands.await { it.player == "Alice" }             // the Bukkit event, as seen by the agent
        }
        bob.awaitChat(Regex("Alice stamped"))                   // a [CHAT] line in Bob's client log
        assertEquals("survival", alice.state().gameMode)        // live server state through the agent
        screenshot(alice, bob, name = "after-stamp")            // both players at once
    }
}
```

Register the extension with `@ExtendWith(StampArena::class)` (as above) or a static field (`companion object { @JvmField @RegisterExtension val arena = StampArena() }`). A `@RegisterExtension` property in the class body is an instance field, and JUnit never calls `beforeAll` for it, so its server never starts. A test method can take the extension and, when exactly one fukurou extension is registered, the `GameServer` as parameters. Players are reached through the extension.

Run it locally (Linux with `xvfb`, `xdotool` and `x11-xserver-utils` installed; see [Running locally](docs/usage.md#running-locally)):

```sh
./gradlew gameTest -Pfukurou.acceptEula=true -Pfukurou.minecraftVersion=1.21.11
```

### 3. GitHub Actions

This workflow tests every version from 1.21.6 onward and publishes one page with the results:

```yaml
name: In-game test

on:
  pull_request:

permissions:
  contents: read

jobs:
  versions:
    runs-on: ubuntu-24.04
    outputs:
      matrix: ${{ steps.versions.outputs.matrix }}
    steps:
      - uses: morinoparty/fukurou/versions@v3
        id: versions
        with:
          minecraft-version: 1.21.6-

  game-test:
    name: Minecraft ${{ matrix.minecraft-version }}
    needs: versions
    runs-on: ubuntu-24.04
    # see "Estimating timeout-minutes" in docs/usage.md
    timeout-minutes: 30
    strategy:
      fail-fast: false
      matrix:
        minecraft-version: ${{ fromJSON(needs.versions.outputs.matrix) }}
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      # Installs the system packages and Java 21, then runs ./gradlew gameTest (which builds the plugin jar)
      - uses: morinoparty/fukurou@v3
        with:
          accept-eula: "true" # You accept the Minecraft EULA: https://aka.ms/MinecraftEULA
          minecraft-version: ${{ matrix.minecraft-version }}
          gradle-task: gameTest

  deploy:
    needs: game-test
    if: always()
    runs-on: ubuntu-24.04
    outputs:
      fukurou_url: ${{ steps.site.outputs.url }}
    steps:
      - uses: morinoparty/fukurou/ui@v3
        id: site
        with:
          s3-endpoint: ${{ vars.FUKUROU_S3_ENDPOINT }}
          s3-bucket: ${{ vars.FUKUROU_S3_BUCKET }}
          aws-access-key-id: ${{ secrets.FUKUROU_S3_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.FUKUROU_S3_SECRET_ACCESS_KEY }}
          public-base-url: ${{ vars.FUKUROU_PUBLIC_BASE_URL }}
```

The `fukurou` action passes its settings to the tests as `FUKUROU_*` environment variables, so your build script does not have to forward anything for CI. It uploads one artifact per version, `fukurou-paper-<version>`, holding one run directory per server (`fukurou-paper-1.21.11/paper-1.21.11-stamp-arena/result.json`).

The `ui` action downloads the `fukurou-paper-*` artifacts, builds one viewer page (a status grid of every test x run), uploads it as the `fukurou-site` artifact, and publishes it to S3-compatible storage such as Cloudflare R2 when credentials are set. Its `url` output (exposed here as the job output `fukurou_url`) points at the published `index.html`, so later jobs can, for example, post it to the pull request. On pull requests from forks the secrets are empty, and the site is only kept as an artifact.

> [!WARNING]
> GitHub drops a job output that contains the value of any secret. If a secret (for example the bucket name) also appears in `public-base-url`, `fukurou_url` arrives empty with the warning `Skip output 'fukurou_url' since it may contain secret`. In that case, pass the non-secret `uploaded` output between jobs instead, and build the URL in the job that uses it.

The `versions` action and the `fukurou` action both run `actions/setup-java` (Java 21 by default), which leaves `JAVA_HOME` and `PATH` pointing at that JDK for the rest of the job. The Minecraft server's own Java is chosen by fukurou (see [Server Java](#server-java)); the clients use Mojang's Java runtime.

## Concepts

- **One extension = one server = one `result.json`.** Each `GameServerExtension` subclass is an independent server. Every test class that registers it shares that server, which keeps running until the end of the JUnit run (or until the [memory budget](docs/usage.md#memory-budget) stops an idle one). A test class may register several extensions to drive several servers at once; each records the test in its own `result.json`. The run id is `<type>-<version>-<label>`, where `label` defaults to the class name in kebab case (`StampArena` → `stamp-arena`).
- **A test is one JUnit test method.** Its id is the method name with unsafe characters replaced by `-` (`` `stamp thinking face` `` → `stamp-thinking-face`), or `@GameTestId("...")`. Its name is `@DisplayName` or the method name, and its tags are the JUnit `@Tag`s.
- **Isolation** controls what happens between tests. `Isolation.Reset()` (the default) keeps the server and has fukurou reset the arena (an air-filled box around the origin, with non-player entities removed), the world time and weather, and each player's inventory, effects, health, XP, title, game mode, position, spawn point and op status, then waits `settle` for the clients to catch up. `Isolation.FreshServer` restarts the server for every test; `@FreshServer` does that for one test. `Isolation.None` does nothing. Your plugin's own state is never reset for you.
- **Log windows.** `awaitLog`, `assertNoLog`, `awaitChat` and `assertNoChat` only see the lines written since the current test's reset, so a startup error or an earlier test's chat cannot make an unrelated test fail. Use `mark()` and `after =` to narrow a wait to what happens after a given point.
- **Everything is recorded.** Every API call (a command, a key press, a query, an event wait, a screenshot) becomes a step in the test's `steps[]` in `result.json` with its duration and error, and the viewer shows them. `setUp` runs as phase `beforeEach`, `server.fixture("name") { }` as phase `fixture`; `onStarted` and `tearDown` only go to `logs/harness.log`. When a test fails, fukurou takes a `failure` screenshot of every player.
- **Errors.** A failed assertion, a failed command or a log or event that never came marks the test `failed`. A harness problem (a client that died, the test's deadline) marks it `error`. A server that dies skips the remaining tests of that server. See [Errors and test status](docs/usage.md#errors-and-test-status).

## Feature tour

### Server state

The agent reads the live server state on the main thread. All of these need the agent (on by default for Paper):

```kotlin
val me = alice.state()                         // PlayerSnapshot: location, gameMode, health, food, inventory (41 slots), openInventory, effects, tags…
assertEquals(3, me.count(Key.key("minecraft:diamond")))
assertEquals(Key.key("minecraft:stone"), server.block(BlockPos(0, -61, 0)).type)
val zombies = server.entities(EntityQuery(type = Key.key("minecraft:zombie"), near = Location(0.0, -60.0, 0.0), radius = 8.0))
val world = server.worldState()                // time, storm, difficulty, players…
val tick = server.currentTick()
server.awaitTicks(20)                          // wait one second of server time
```

### Events

Subscribe to any Bukkit, Paper or plugin event by class name (`"BlockBreakEvent"`, a fully qualified name, or a constant in `BukkitEvents`). Each event arrives as a `ServerEvent` with its `type`, `tick`, `cancelled` and its getters as JSON `fields` (`getPlayer()` → `player`, the player's name).

```kotlin
// record: subscribe first, act, then look at what arrived (await also sees events that already arrived)
server.events.record("BlockBreakEvent").use { breaks ->
    alice.holdMouse(MouseButton.LEFT, 2.seconds)
    val event = breaks.await { it.player == "Alice" }
    breaks.assertNoneWithin(1.seconds) { it.player == "Bob" }
}

// expect: subscribe, run the action, wait for the event (nothing can slip through)
val spawn = server.events.expect(BukkitEvents.PLAYER_COMMAND_PREPROCESS, predicate = { it.string("message") == "/spawn" }) {
    alice.chat("/spawn")
}
```

`server.events.await(type)` only sees events that happen **after** it is called, so an event caused by the line before it can be missed. Use `record` before the action, or `expect`.

### Running code on the server

When state queries are not enough, put test hooks in a small **companion plugin**. It registers named tasks with `party.morino.fukurou.agent.api.FukurouTasks`; a test calls them with `server.execute`. A task runs on the server's main thread, and its return value comes back as JSON.

```java
// companion plugin: src/main/java/com/example/stamp/test/StampTestHooks.java
public final class StampTestHooks extends JavaPlugin {
    record GiveArgs(String player, String id) {}

    @Override public void onEnable() {
        FukurouTasks.register("stamp:count", ctx -> StampPlugin.instance().stamps().size());
        FukurouTasks.register("stamp:give", ctx -> {
            GiveArgs args = ctx.args(GiveArgs.class); // Gson
            Bukkit.getPlayerExact(args.player()).getInventory().addItem(StampItems.create(args.id()));
            return null;
        });
    }

    @Override public void onDisable() {
        FukurouTasks.unregister("stamp:count");
        FukurouTasks.unregister("stamp:give");
    }
}
```

```yaml
# companion plugin.yml
name: StampTestHooks
main: com.example.stamp.test.StampTestHooks
version: "1.0"
api-version: "1.20"
depend: [FukurouAgent, Stamp]
```

```kotlin
// companion plugin build.gradle.kts
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("com.github.morinoparty:fukurou:v3.0.0") // the API classes (Java 17 bytecode); never shade them
}
java { disableAutoTargetJvm() } // only if this project compiles for Java 17: fukurou's Gradle metadata says JVM 21
```

```kotlin
// the extension: install the companion next to the plugin under test
plugins {
    underTest(PluginSource.systemProperty("myplugin"))
    dependency(PluginSource.systemProperty("companion"))
}

// the test
@Serializable data class GiveArgs(val player: String, val id: String)

val count = server.execute<Int>("stamp:count")
server.execute<GiveArgs, Unit>("stamp:give", GiveArgs("Alice", "smile")) // a task that returns null can be read as Unit
val raw: JsonElement = server.execute("stamp:count") // no type argument: the raw JSON
```

- `FukurouTasks` is one registry inside the `FukurouAgent` plugin. The companion sees it through `depend: [FukurouAgent]`; shading the API into the companion would create a second, empty registry. Task names match `^[A-Za-z0-9][A-Za-z0-9_.:-]*$`, and registering a name twice throws.
- A task may return a `CompletionStage`; fukurou waits for it (up to the `timeout`, 30 seconds by default).
- An exception thrown by the task fails the test with `ServerTaskException` (its `remoteType` and `remoteStackTrace` tell you what happened on the server).

> [!IMPORTANT]
> Without arguments, give the result type explicitly: `server.execute<Int>("stamp:count")`. Writing `val count: Int = server.execute("stamp:count")` does not compile, because it resolves to the member function that returns `JsonElement`. With arguments the declared type is enough (`val back: GiveArgs = server.execute("stamp:echo", args)`).

### Input

```kotlin
alice.pressKey(KeySym.E)                               // one key; any X11 keysym, e.g. KeySym("F5"), KeySym.of("Enter")
alice.pressChord(KeySym.F3 + KeySym.D)
alice.holdKey(KeySym.W, 2.seconds)                     // walk forward for two seconds
alice.holding(KeySym.W) { alice.pressKey(KeySym.SPACE) }               // hold while doing something else
alice.holding(listOf(KeySym.W, KeySym.CONTROL)) { pause(1.seconds) }   // several keys: a list, not vararg
alice.keyDown(KeySym.SHIFT); alice.keyUp(KeySym.SHIFT)
alice.selectHotbar(0)                                  // slots 0..8
alice.look(yaw = 90f, pitch = 45f)                     // server-side rotation
alice.attack(); alice.useItem()                        // left / right click
alice.holdMouse(MouseButton.LEFT, 1.5.seconds)         // break a block
alice.click(640, 360)                                  // a GUI slot, in 1280x720 window coordinates
alice.scroll(-1)
```

`holding` takes a single `KeySym` or a `List<KeySym>` (`KeySym` is a value class, so there is no vararg overload). Keys and buttons still held when a test ends are always released, even after a failure or timeout; a client that could not be released is relaunched before the next test.

### Waiting and structure

```kotlin
step("open the shop") {                         // one named step with its own steps inside
    alice.useItem()
    eventually(timeout = 5.seconds) {           // retries while the block throws AssertionError
        assertEquals("CHEST", alice.state().openInventory?.type)
    }
}
awaitUntil("Bob is flying", timeout = 10.seconds) { bob.state().flying }
repeat(3) { i -> alice.screenshot("shot-${i + 1}") }   // fukurou's repeat: see the note on imports below
parallel {                                       // lanes run at the same time
    lane { alice.holdKey(KeySym.W, 2.seconds) }
    lane { bob.holdKey(KeySym.S, 2.seconds) }
}
pause(1.seconds)
```

These are top-level functions in `party.morino.fukurou`. `repeat` shares its name with Kotlin's `kotlin.repeat`:

- With `import party.morino.fukurou.repeat` or `import party.morino.fukurou.*`, an unqualified `repeat` is fukurou's. A star import takes priority over Kotlin's default imports, so it replaces **every** unqualified `repeat` in the file, and fukurou's `repeat` cannot be nested and only runs in suspend code. Write `kotlin.repeat(n) { … }` for a plain loop in such a file.
- Without either import, Kotlin's own `repeat` is used. It also runs the block, but records no `repeat` information.

### Screenshots and image comparison

```kotlin
val shot = alice.screenshot("shop")                 // tests/<id>/screenshots/Alice/shop.png
assertEquals(Rgb(255, 0, 0), shot.pixel(10, 10))
assertTrue(shot.averageColor(Region(0, 0, 64, 64)).distanceTo(Rgb(40, 40, 40)) < 20)
shot.assertMatches(Path.of("src/gameTest/baselines/shop.png"), maxDifferentRatio = 0.02, ignore = listOf(Region(0, 0, 200, 20)))
```

When the baseline does not exist (or `fukurou.updateBaselines=true`), `assertMatches` saves the screenshot as the baseline and passes; commit the file. When the difference is larger than allowed it writes `<name>.diff.png` next to the screenshot and fails. Rendering is in software and not pixel-exact, so keep a tolerance and mask out animated parts.

### Server Java

`fukurou.serverJava` defaults to `auto`: the server needs the newer of the Java version Mojang requires for that Minecraft release and the class file version of your plugin jars. fukurou uses the test JVM if it is that version, otherwise the runner's `JAVA_HOME_<N>_X64` (set on GitHub-hosted runners), otherwise a Temurin JDK downloaded from Adoptium into `<workDir>/cache/jdks/temurin-<N>/` (checked against its SHA-256 and cached by the actions). `current` always uses the test JVM, and a path uses that `java`. One Java 21 test JVM can therefore run every version from 1.20 to the newest.

## Migrating from v2

v3 replaces scenario files with Kotlin. Bump every action to `@v3`, write a `gameTest` suite as in the [quick start](#quick-start), and translate each scenario into a test method:

| v2 scenario / suite | v3 Kotlin |
| --- | --- |
| a scenario file (test id = file name) | a `@Test suspend fun` (id = method name, or `@GameTestId`) |
| `{ action: wait, seconds: 2 }` | `pause(2.seconds)` |
| `{ on: server, action: command, command: "..." }` | `server.command("...")` |
| `{ on: server, action: wait_for_log, pattern: "..." }` / `assert_no_log` | `server.awaitLog(Regex("..."))` / `server.assertNoLog(Regex("..."))` |
| `{ on: Alice, action: wait_for_log, pattern: "\\[CHAT\\] ..." }` | `alice.awaitChat(Regex("..."))` (or `alice.log.await(...)` for any client log line) |
| `{ on: Alice, action: press_key, key: F5 }` | `alice.pressKey(KeySym.F5)` |
| `{ on: Alice, action: type_text, text: "..." }` | `alice.typeText("...")` |
| `{ on: Alice, action: chat, text: "/hello" }` | `alice.chat("/hello")`, or `alice.sendCommand("hello")` to also wait for the server to see it |
| `{ on: Alice, action: screenshot, name: hello }` | `alice.screenshot("hello")` |
| `on: [Alice, Bob]` | `screenshot(alice, bob, name = "...")`, or `parallel { lane { … }; lane { … } }` |
| `{ action: parallel, steps: [...] }` | `parallel { lane { … } }` |
| `{ action: repeat, times: 3, as: i, steps: [...] }` with `${i}` | `repeat(3) { i -> … }` (`i` is 0-based; use `i + 1`) |
| suite `fixtures` + `use: [arena]` | a `suspend fun` you call, or `server.fixture("arena") { … }` to record it as phase `fixture` |
| suite `beforeEach` | `override suspend fun GameServer.setUp()` |
| suite / test `players` | `val alice by player("Alice", op = true)` in the extension |
| `isolation: reset` / `fresh-server`, suite `arena` / `gamemode` / `spawn` / `settle` | `isolation = Isolation.Reset(arena, gamemode, settle, spawns)` / `Isolation.FreshServer`, or `@FreshServer` on one test |
| test `timeout` | `@GameTimeout(seconds)` or `ServerSpec.testTimeout` |
| test `versions: 1.21.6-` | `@MinecraftVersions("1.21.6-")` |
| test `tags` | JUnit `@Tag` |

Inputs of the `fukurou` action that are gone: `suite`, `scenarios`, `scenario`, `scenario-file`, `tests`, `tags`, `isolation`, `fail-fast`, `plugins-dir`, `plugins`, `dependencies`, `server-properties`, `server-files` and `server-build` (now `paper-build`). Declare plugins in code with `ServerSpec.plugins { underTest(…); dependency(PluginSource.githubRelease(…) / url(…) / file(…)) }`, server properties with `Paper(...).withProperties(...)`, files with `serverFiles(dir)`, and filter tests with Gradle (`gradle-args: --tests *Stamp*`; quoting is not supported, and globs are passed to Gradle as is). `java-version` now selects the JDK that runs Gradle (default `21`); `java-version: auto` is rejected because the server's Java is chosen by fukurou. New inputs: `working-directory`, `gradle-task`, `gradle-args`. `failed-tests` is now `<run label or id>/<test id>`.

`result.json` keeps `schemaVersion: 2`, so the viewer and anything reading results keep working; `fukurou.runner` is always `"kotlin"`. The `fukurou` command line (`fukurou run`, `validate`, `list`, `schema`, `java`) is gone; `fukurou versions` lives on behind the `versions` action.

## Requirements

- **Linux x86_64.** The clients need Xvfb, `xdotool` and `xmodmap` and the Linux build of PortableMC. On GitHub Actions, `ubuntu-24.04` works; the actions install the system packages with `apt-get`. Tests without players need none of these.
- **Java 21 and Kotlin 2.2 or newer** for the test code, and **JUnit 6**.
- **Minecraft 1.20 or later.** Clients join with Quick Play and the flat world assumes the 1.18+ world height. By default only releases with a `STABLE` Paper build are picked and the run uses the newest `STABLE` build. Set `paper-channel: beta` or `alpha` on both the `versions` action and the run action to accept less stable builds (see [Paper channels](docs/usage.md#paper-channels)).
- **The Minecraft EULA.** fukurou downloads and runs the Minecraft server and client, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) with `accept-eula: "true"` (or `fukurou.acceptEula=true`). Without it, nothing is started.
- **Offline mode.** The server runs with `online-mode=false`, so no Microsoft account is needed. Your plugin has to work with offline-mode UUIDs.

## Artifacts and the results contract

Each version uploads one artifact, `fukurou-paper-<version>` by default, with one run directory per server. Each run directory contains `result.json`, every test's screenshots under `tests/<id>/screenshots/<player>/<name>.png`, the harness log, per-session server and client logs, and client crash reports. It never contains server jars, client jars, assets, Java runtimes or worlds, so nothing from Minecraft is redistributed. The artifact is uploaded even when the tests fail, and `result.json` is written progressively (a stub when the server is planned, then again after every test), so a job killed by `timeout-minutes` still leaves the results of the tests that finished.

The format of `result.json` and of the viewer's `manifest.json` is described in [docs/contract.md](docs/contract.md). The JSON Schema is [`schema/result.v2.json`](schema/result.v2.json).

## Limitations

- **Plugin state is not reset for you.** fukurou resets the world, inventories and clients between tests, but not your plugin's own state (cooldowns, player data, anything it persists). Reset it in `setUp` (a command or a companion task), or use `@FreshServer` for a test that truly needs a clean slate.
- Rendering is done in software by Mesa, so it is slow, and particles, lighting and animations are not pixel-deterministic. Compare screenshots with a tolerance and ignore regions.
- Only Paper servers are supported.
- The arena reset assumes a flat world (`y = -64..-61`). If you bring your own world with `serverFiles`, use `Isolation.Reset(arena = null)` or `Isolation.None`.

## License

[MIT](LICENSE)

## Acknowledgements

fukurou is based on the in-game test workflow of [sya-ri/ktAdvancements](https://github.com/sya-ri/ktAdvancements/blob/master/.github/workflows/game-test.yml): a real server and a vanilla client launched with PortableMC, run under Xvfb and driven with `xdotool`. Thank you!
