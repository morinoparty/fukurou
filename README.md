# fukurou

fukurou is an in-game test runner for Minecraft [Paper](https://papermc.io/) plugins. For one Minecraft version it starts a real Paper server with your plugin, joins it with one or more real vanilla Minecraft clients, and runs a **suite of tests** against them, each one a JSON or YAML scenario that drives the clients and checks the server and client logs. It saves screenshots, logs and a machine-readable `result.json`, and a static viewer turns the results of every tested version into one page.

It runs on GitHub Actions (or any Linux x86_64 machine) without a GPU or a Microsoft account:

- **Server:** Paper in offline mode on a flat world, with RCON enabled so tests can run console commands. One server session runs every test for a version; fukurou resets the world and the clients between tests.
- **Clients:** vanilla clients installed with [PortableMC](https://github.com/theorzr/portablemc) (a pinned version, checked against its SHA-256). Each player gets its own Xvfb display, and input is sent with `xdotool`. Players join one at a time with Quick Play, once per session.
- **Versions:** a version spec such as `1.21.6-` is resolved against Mojang's version manifest and the Paper API, so each version can run as its own matrix job, and every test of that version runs inside it.

fukurou has three GitHub Actions in one repository, released together under one tag:

| Action | What it does |
| --- | --- |
| `morinoparty/fukurou/versions@v2` | Resolves a version spec to a JSON array for a job matrix. |
| `morinoparty/fukurou@v2` | Runs a suite of tests against one Minecraft version in one server session, and uploads the results as an artifact. |
| `morinoparty/fukurou/ui@v2` | Builds the viewer site from the artifacts of every version and uploads it to S3-compatible storage. |

See [docs/usage.md](docs/usage.md) for every input and output, and [docs/contract.md](docs/contract.md) for the format of `result.json` and `manifest.json`.

> [!NOTE]
> Coming from `@v1`? See [Migrating from v1](docs/usage.md#migrating-from-v1) in docs/usage.md — the shape of `result.json` changed, so the runner and the viewer must be updated together.

## Quick start

Build your plugin, pass the jar to fukurou, and write a suite of tests. This workflow tests every version from 1.21.6 onward and publishes one page with the results:

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
      - uses: morinoparty/fukurou/versions@v2
        id: versions
        with:
          minecraft-version: 1.21.6-

  game-test:
    name: Minecraft ${{ matrix.minecraft-version }}
    needs: versions
    runs-on: ubuntu-24.04
    # 10 + N_reset x 1 + N_fresh-server x 4 minutes; see "Estimating timeout-minutes" in docs/usage.md
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        minecraft-version: ${{ fromJSON(needs.versions.outputs.matrix) }}
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "21"
      - run: ./gradlew shadowJar

      - uses: morinoparty/fukurou@v2
        with:
          accept-eula: "true" # You accept the Minecraft EULA: https://aka.ms/MinecraftEULA
          minecraft-version: ${{ matrix.minecraft-version }}
          suite: game-test/fukurou.yml
          plugins-dir: build/libs
          plugins: "*-all.jar"

  deploy:
    needs: game-test
    if: always()
    runs-on: ubuntu-24.04
    outputs:
      fukurou_url: ${{ steps.site.outputs.url }}
    steps:
      - uses: morinoparty/fukurou/ui@v2
        id: site
        with:
          s3-endpoint: ${{ vars.FUKUROU_S3_ENDPOINT }}
          s3-bucket: ${{ vars.FUKUROU_S3_BUCKET }}
          aws-access-key-id: ${{ secrets.FUKUROU_S3_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.FUKUROU_S3_SECRET_ACCESS_KEY }}
          public-base-url: ${{ vars.FUKUROU_PUBLIC_BASE_URL }}
```

`game-test/fukurou.yml` is a suite file: the scenarios to run as tests, the players they share, and any fixtures (reusable step sequences, such as building an arena) tests can opt into with `use:`.

```yaml
# game-test/fukurou.yml
scenarios:
  - scenarios/*.json # each file is one test; the test id is its file name without the extension
players:
  - { name: Alice, op: true }
  - { name: Bob }
fixtures:
  arena:
    - { on: server, action: command, command: "fill -8 -60 -8 8 -50 8 minecraft:stone" }
    - { on: server, action: command, command: "tp Alice 0.5 -49 0.5 0 30" }
    - { on: server, action: command, command: "tp Bob 0.5 -49 8.5 180 -15" }
```

```json
// game-test/scenarios/hello.json — one test, id "hello"
{
  "use": ["arena"],
  "steps": [
    { "on": "Alice", "action": "chat", "text": "/hello" },
    { "on": "Alice", "action": "wait_for_log", "pattern": "\\[CHAT\\] Hello, Alice", "timeout": 10 },
    { "on": "Alice", "action": "screenshot", "name": "hello" },
    { "on": "Bob", "action": "screenshot", "name": "hello" }
  ]
}
```

The `ui` action downloads the `fukurou-paper-*` artifacts, builds one viewer page (a status grid of every test x version), uploads it as the `fukurou-site` artifact, and publishes it to S3-compatible storage such as Cloudflare R2 when credentials are set. Its `url` output (exposed here as the job output `fukurou_url`) points at the published `index.html`, so later jobs can, for example, post it to the pull request. On pull requests from forks the secrets are empty, and the site is only kept as an artifact.

> [!WARNING]
> GitHub drops a job output that contains the value of any secret. If a secret (for example the bucket name) also appears in `public-base-url`, `fukurou_url` arrives empty with the warning `Skip output 'fukurou_url' since it may contain secret`. In that case, pass the non-secret `uploaded` output between jobs instead, and build the URL in the job that uses it.

The server's Java version is picked automatically: the newer of the version Mojang requires for that Minecraft release and the version your plugin jars are compiled for. The clients use Mojang's own Java runtime. The action installs the server's JDK with `actions/setup-java`, which leaves `JAVA_HOME` and `PATH` pointing at it for the rest of the job; build your plugin before the fukurou step, as above, or set up Java again afterwards.

## Concepts

- **A test is one scenario file** (or the inline `scenario` input): its `players` and `steps`, plus metadata such as `tags`, `isolation` and `versions:`. The test's **id** is the file's name without its extension (or `inline`); it must match `^[A-Za-z0-9][A-Za-z0-9_.-]*$`, since it becomes a path under `tests/<id>/...` and a viewer route.
- **A suite** (`fukurou.yml`, optional) is what a group of tests shares: which scenario files to run (`scenarios:` globs), the default `players`, named `fixtures` a test can pull in with `use:`, steps that run before every test (`beforeEach:`), and the reset settings (`arena`, `gamemode`, `spawn`, `settle`). See [docs/usage.md](docs/usage.md#suite-file) for every field.
- **Fixtures** are reusable step sequences (for example, building an arena and positioning players) that a test opts into with `use: [name, ...]`. They expand in front of the test's own `steps`, after `beforeEach`. A fixture step aimed at a player the test does not declare is skipped for that test, so one fixture can serve tests with different players.
- **Isolation** controls what happens between tests: `reset` (the default) reuses the same server session and has the harness reset the world, inventories and clients; `fresh-server` stops and restarts the server (and reinstalls nothing — clients are just relaunched) for tests that need a clean world from block zero. Set it per test, per suite, or force it for a whole run with the `isolation` input / `--isolation`.
- **One Minecraft version = one job = one server session.** Paper starts once, the union of every selected test's players joins once, and the tests then run in declared order (`reset` tests first, then `fresh-server` tests). Between tests fukurou resets the arena, world time/weather, and each participating player's inventory, health, XP, title and spawnpoint, then re-ops or de-ops them to match that test's `players`.
- **Log windows.** Server and client log matching (`wait_for_log`, `assert_no_log`) only sees the lines written since the current test's reset, not the whole session's log. A plugin error at server startup, or a previous test's chat, will not make an unrelated test fail — but a `wait_for_log`/`assert_no_log` pattern also cannot see anything from before the test.

## Scenario format

A scenario (test) lists its `players` and its `steps`, plus optional metadata. It can be written in JSON or YAML. The `on` field of a step selects its target:

- `on: server` runs a server action.
- `on: <player name>` runs a player action on that player's client. The player must be listed in `players` (the test's own, or the suite's if the test does not declare any). `on` can also be a list of names — see [below](#parallel-repeat-and-multiple-players).
- No `on` runs a common action. A `parallel` or `repeat` block also has no `on` — see [below](#parallel-repeat-and-multiple-players).

fukurou reads YAML with YAML 1.2 booleans (only `true` and `false`), so a bare `on:` key works. Quoting it as `"on":` also works and keeps the file readable by YAML 1.1 tools, which treat a bare `on` as `true`.

Unknown fields, actions sent to the wrong target, undeclared players and invalid regular expressions are rejected before anything starts. Check tests with `fukurou validate`, list them with `fukurou list`, or get a JSON Schema with `fukurou schema {scenario|suite|result}`.

```yaml
name: hello       # optional; defaults to the test id
tags: [chat]      # optional; filter with --tag / the "tags" input
players:
  - name: Alice
    op: true
  - name: Bob
steps:
  - { on: server, action: command, command: "tp Alice 0.5 -60 0.5 0 30" }
  - { on: server, action: command, command: "tp Bob 0.5 -60 8.5 180 -15" }
  - { action: wait, seconds: 10 }
  - { on: Alice, action: chat, text: "/hello" }
  - { on: Alice, action: wait_for_log, pattern: "\\[CHAT\\] Hello, Alice", timeout: 10 }
  - { on: Alice, action: screenshot, name: hello }
  - { on: Bob, action: screenshot, name: hello }
```

Top-level test fields (all optional; see [docs/usage.md](docs/usage.md#test-fields) for the full table):

| Field | Default | Description |
| --- | --- | --- |
| `name` | the test id | Display name in the viewer. |
| `tags` | `[]` | Strings to filter on with `--tag`. |
| `isolation` | the suite's (default `reset`) | `reset` or `fresh-server`. |
| `timeout` | `600` | Seconds between steps before the test errors out. |
| `versions` | none | A version spec (`fukurou versions` syntax, `latest` excluded); the test is `skipped` on versions it does not include. |
| `use` | `[]` | Names of the suite's `fixtures` to expand before this test's own steps. |
| `players` | the suite's | Fully replaces the suite's players for this test when set. |

`players` entries:

| Field | Description |
| --- | --- |
| `name` | Offline player name (3–16 letters, digits or `_`). `server` is reserved. |
| `op` | Optional. If `true`, the player is made an operator for this test (otherwise de-opped). |

Server actions (`on: server`):

| Action | Fields | Description |
| --- | --- | --- |
| `command` | `command` | Run a console command through RCON. A leading `/` is ignored. The step fails when the reply starts with an error (`Unknown or incomplete command`, `Incorrect argument`, `Too many blocks`, `Cannot`, `That position is not loaded` or `No player was found`), so a broken fixture does not go unnoticed. |
| `wait_for_log` | `pattern`, `timeout`? | Wait until a regex matches the server log written since this test started. The default timeout is 60 seconds. |
| `assert_no_log` | `pattern` | Fail if a regex matches the server log written since this test started. |

Player actions (`on: <player name>`):

| Action | Fields | Description |
| --- | --- | --- |
| `press_key` | `key` | Press and release one key. Use an X11 keysym name, such as `F5`, `t` or `Return`. `Enter`, `Esc` and `Space` also work. |
| `type_text` | `text` | Type text. |
| `chat` | `text` | Open chat with T, type the text, and press Enter. Works for commands too. |
| `wait_for_log` | `pattern`, `timeout`? | Wait until a regex matches the client log written since this test started. Chat lines appear there with `[CHAT]`. The default timeout is 60 seconds. |
| `assert_no_log` | `pattern` | Fail if a regex matches the client log written since this test started. |
| `screenshot` | `name` | Press F2 and save the new screenshot as `tests/<id>/screenshots/<player>/<name>.png`. The name starts with a letter or digit and may contain letters, digits, `_`, `.` and `-`. `failure` is reserved for the screenshot taken automatically when a test fails. |

Common actions (no `on`):

| Action | Fields | Description |
| --- | --- | --- |
| `wait` | `seconds` | Sleep. |

Screenshots are not compared with baseline images. They are saved so that you can look at them in the viewer.

> [!IMPORTANT]
> `press_key` and `type_text` can leave a client mid-action (a menu open, a partial chat message) if the test fails or times out. fukurou detects this and relaunches the client before the next test, which costs 20–60 seconds. Prefer `chat` when it does the same job — it is atomic and never leaves the client dirty.

### Parallel, repeat and multiple players

Any player action's `on` can also be a list of player names (`"on": ["Alice", "Bob"]`) to run it on several players at once. A step can also be a block instead of a single action:

- `{ "action": "parallel", "steps": [...] }` runs its children at the same time and waits for all of them; an `on` list is the same as a `parallel` with one copy per player.
- `{ "action": "repeat", "times": N, "as": "i", "steps": [...] }` runs its steps N times, replacing `${i}` (1-based) and `${i0}` (0-based) in each child's `text` / `command` / `pattern` / `name` / `key`.

```json
{
  "action": "parallel",
  "steps": [
    { "on": "server", "action": "command", "command": "weather clear" },
    { "on": "Alice", "action": "screenshot", "name": "midday" }
  ]
}
```

```json
{ "action": "repeat", "times": 3, "as": "i", "steps": [
  { "on": "Alice", "action": "screenshot", "name": "shot-${i}" }
] }
```

See [Parallel and repeat steps](docs/usage.md#parallel-and-repeat-steps) in docs/usage.md for the full rules (nesting, limits, which actions may run at the same time) and [contract.md](docs/contract.md#parallel-and-repeat-blocks-fukurou-21) for the `parallel` / `repeat` / `startedAt` / `finishedAt` fields this adds to `result.json`. `examples/parallel-repeat.json` is a runnable example.

## Running it locally

fukurou is a Python package. With [uv](https://docs.astral.sh/uv/) you can run it straight from this repository:

```sh
# Which versions does a spec resolve to?
uvx --from git+https://github.com/morinoparty/fukurou fukurou versions 1.21.6-

# Check a suite and its tests
uvx --from git+https://github.com/morinoparty/fukurou fukurou validate --suite game-test/fukurou.yml

# List the tests a selection resolves to, in run order
uvx --from git+https://github.com/morinoparty/fukurou fukurou list --suite game-test/fukurou.yml --tag chat

# Run it (needs xvfb and xdotool; fukurou starts its own Xvfb displays)
uvx --from git+https://github.com/morinoparty/fukurou fukurou run \
  --accept-eula \
  --minecraft-version 1.21.11 \
  --suite game-test/fukurou.yml \
  --plugins-dir build/libs --plugins '*-all.jar'
```

`fukurou run` writes `fukurou-out/result.json`, screenshots and logs, and keeps the server, clients and caches in `.fukurou-work/`. It exits with 0 when every selected test passed or was skipped, 1 when any test failed, errored, or the run could not proceed, and 2 for invalid input (including an empty selection). Run `fukurou --help` for every command and option.

## Kotlin / JUnit (JVM)

fukurou also ships a Kotlin library that drives the same servers, clients and results from JUnit tests instead of YAML/JSON scenarios. It lives in [`kotlin/`](kotlin) and is published through JitPack:

```kotlin
// build.gradle.kts
repositories { maven("https://jitpack.io") }
dependencies { testImplementation("com.github.morinoparty:fukurou:v2.2.0") }
```

Use an exact tag (JitPack caches the first build of a tag forever). The library targets Java 21 bytecode, needs JUnit 6 on the test classpath, and its API is `suspend` (JUnit 6 runs `suspend` test methods itself through `kotlin-reflect`, which fukurou brings in as a runtime dependency).

Register an extension with `@ExtendWith(StampArena::class)` or a static field (`companion object { @JvmField @RegisterExtension val arena = StampArena() }`). A `@RegisterExtension` property in the class body is an instance field, and JUnit never calls `beforeAll` for it, so its server never starts.

Add `src/<suite>/resources/junit-platform.properties` so a test that hangs outside fukurou's own waits is still stopped:

```properties
junit.jupiter.testclass.order.default=party.morino.fukurou.junit.platform.FukurouClassOrderer
junit.jupiter.execution.parallel.enabled=false
junit.jupiter.execution.timeout.testable.method.default=15 m
```

One `GameServerExtension` subclass is one independent server and one `result.json`. Every test class that registers it with `@ExtendWith` shares that server:

```kotlin
class StampArena : GameServerExtension() {
    val alice by player("Alice", op = true)
    val bob by player("Bob")

    // CI passes -Pfukurou.minecraftVersion; the default is used locally
    override fun type(config: FukurouConfig): ServerType =
        Paper.fromProperties(config, defaultVersion = "26.3", defaultChannel = PaperChannel.Alpha)

    override fun ServerSpec.configure() {
        label = "stamp-arena" // the result id becomes paper-26.3-stamp-arena
        plugins { underTest(PluginSource.systemProperty("minestamp")) } // -Pfukurou.plugin.minestamp=<jar>
        isolation = Isolation.Reset(settle = 5.seconds)
    }
}

@ExtendWith(StampArena::class)
class StampTest {
    @Test
    suspend fun `stamp-thinking-face`(arena: StampArena) {
        arena.alice.sendCommand("st :thinking-face:")
        arena.bob.screenshot("after-stamp")
    }
}
```

Players and servers implement Kyori Adventure's `Audience`, so `player.showTitle(...)` or `server.sendMessage(...)` work as in a plugin. The library can also be used without JUnit (`Fukurou.fromSystemProperties().use { ... }`).

**Configuration.** Everything is read from `fukurou.*` system properties (forward them from Gradle `-Pfukurou.*` to the test JVM as `-D`):

| Property | Default | Meaning |
| --- | --- | --- |
| `fukurou.acceptEula` (`FUKUROU_ACCEPT_EULA`) | `false` | Must be `true`. Running means accepting the Minecraft EULA. |
| `fukurou.minecraftVersion` | the extension's default | Exact Minecraft version (read by `Paper.fromProperties`). |
| `fukurou.paperChannel` | the extension's default | Least stable accepted Paper channel: `stable` / `beta` / `alpha`. |
| `fukurou.paperBuild` | newest | Pin a Paper build. |
| `fukurou.plugin.<key>` | — | Path of a plugin jar, read by `PluginSource.systemProperty("<key>")`. |
| `fukurou.outDir` (`FUKUROU_OUT_DIR`) | `fukurou-out` | One `<run id>/` directory (the results contract layout) per server. |
| `fukurou.workDir` (`FUKUROU_WORK_DIR`) | `.fukurou-work` | Tools, caches, server and client directories. |
| `fukurou.memoryBudgetMb` | 0.9 × available memory | Budget for servers that are alive at the same time. |
| `fukurou.selection.tests` / `fukurou.selection.tags` | — | Recorded in `result.selection` (filter with Gradle `--tests` / JUnit tags). |
| `fukurou.missingHost` | `fail` | `skip` aborts the tests instead of failing when Xvfb, xdotool or xmodmap is missing. |
| `fukurou.keepWork` | `false` | Keep the server directories after the run for debugging. |

**CI.** The [`morinoparty/fukurou/setup`](setup/action.yml) action installs the system packages (the same list as the Python action, plus `x11-xserver-utils` for `xmodmap`), sets `FUKUROU_WORK_DIR` and restores the tool and asset cache:

```yaml
- uses: morinoparty/fukurou/setup@v2
  with:
    minecraft-version: ${{ matrix.minecraft-version }}
- run: ./gradlew gameTest -Pfukurou.acceptEula=true -Pfukurou.minecraftVersion=${{ matrix.minecraft-version }} -Pfukurou.outDir=${{ runner.temp }}/fukurou-out
- uses: actions/upload-artifact@v7
  if: always()
  with:
    name: fukurou-paper-${{ matrix.minecraft-version }}
    path: ${{ runner.temp }}/fukurou-out
```

The uploaded artifact is a nested bundle with one run directory per server (`fukurou-paper-26.3/paper-26.3-stamp-arena/result.json`). `morinoparty/fukurou/ui` reads it like the Python runner's artifacts and shows each server as its own run, labelled `26.3 · stamp-arena` (see [contract.md](docs/contract.md)).

## Requirements

- **Linux x86_64.** The runner uses Xvfb, `xdotool` and the Linux build of PortableMC. On GitHub Actions, `ubuntu-24.04` works; the action installs the system packages with `apt-get`.
- **Minecraft 1.20 or later.** Clients join with Quick Play and the flat world assumes the 1.18+ world height. By default only releases with a `STABLE` Paper build are picked and the run uses the newest `STABLE` build. Set `paper-channel: beta` or `alpha` (`--paper-channel`) on both the `versions` action and the run action to accept less stable builds, such as a release that Paper has only as `ALPHA` builds (see [Paper channels](docs/usage.md#paper-channels)).
- **The Minecraft EULA.** fukurou downloads and runs the Minecraft server and client, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) with `accept-eula: "true"` (or `--accept-eula`). Without it, nothing is started.
- **Offline mode.** The server runs with `online-mode=false`, so no Microsoft account is needed. Your plugin has to work with offline-mode UUIDs.

## Artifacts and the results contract

Each version uploads one artifact, `fukurou-paper-<version>` by default. It contains `result.json`, every test's screenshots under `tests/<id>/screenshots/<player>/<name>.png`, the harness, per-session server and client logs, and client crash reports. It never contains server jars, client jars, assets, Java runtimes or worlds, so nothing from Minecraft is redistributed. The artifact is uploaded even when the run fails, and `result.json` is written progressively (a stub right after the tests are discovered, then again after every test), so a job killed by `timeout-minutes` still leaves the results of the tests that finished.

The format of `result.json` and of the viewer's `manifest.json` is described in [docs/contract.md](docs/contract.md). Their JSON Schemas are printed by `fukurou schema {scenario|suite|result}`.

## Limitations

- **Plugin state is not reset for you.** fukurou resets the world, inventories and clients between tests, but not your plugin's own state (cooldowns, player data, anything it persists). Reach for `settle` (wait a little after the reset), a `beforeEach`/fixture step that calls a reset command your plugin provides, or `isolation: fresh-server` for a test that truly needs a clean slate.
- Screenshots are saved, not compared. Particles, lighting and animations are not pixel-deterministic.
- Input is keyboard only (key presses and typed text). There is no mouse input, and no way to read the screen other than logs and screenshots.
- Clients are rendered in software by Mesa. They are slow, so a suite with several players and tests needs a few minutes per version.
- Only Paper servers are supported (both runners).
- The arena reset assumes a flat world (`y = -64..-61`). If you bring your own world with `server-files`, set `arena: false` in the suite.

## License

[MIT](LICENSE)

## Acknowledgements

fukurou is based on the in-game test workflow of [sya-ri/ktAdvancements](https://github.com/sya-ri/ktAdvancements/blob/master/.github/workflows/game-test.yml): a real server and a vanilla client launched with PortableMC, run under Xvfb and driven with `xdotool`. Thank you!
