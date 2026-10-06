# Results contract

This is the only interface between the test harness (the Kotlin library `com.github.morinoparty:fukurou` and the `morinoparty/fukurou` action) and the viewer (`fukurou/ui`). Both live in this repository and are released together under one tag.

A `result.json` describes one server run: one `GameServerExtension` (one server) against one Minecraft version. Every test that used that server is a test of that run, in the order the tests were planned. Between tests the harness resets the world and the clients according to the server's isolation; a test that needs a fresh server gets a new server session.

## 1. Output directory (one artifact per Minecraft version)

The library writes one run directory per server under `fukurou.outDir`. The `morinoparty/fukurou` action uploads that directory as the artifact `fukurou-paper-<minecraft version>` (configurable), even when the tests fail. The artifact is therefore a **nested bundle**:

```
<artifact>/<run id>/result.json                                     # for example fukurou-paper-1.21.11/paper-1.21.11-stamp-arena/result.json
<artifact>/<run id>/tests/<test id>/screenshots/<player>/<name>.png
<artifact>/<run id>/tests/<test id>/screenshots/<player>/failure.png
<artifact>/<run id>/tests/<test id>/screenshots/<player>/<name>.diff.png   # only when Screenshot.assertMatches failed; not listed in result.json
<artifact>/<run id>/logs/harness.log                                # fukurou's own output
<artifact>/<run id>/logs/sessions/<n>/server.log                    # the server console record of session n (includes JVM errors); target of logRanges
<artifact>/<run id>/logs/sessions/<n>/clients/<player>.log          # the client's latest.log from its first launch in session n
<artifact>/<run id>/logs/sessions/<n>/clients/<player>.<k>.log      # the latest.log after the client was relaunched for the k-th time (k >= 2)
<artifact>/<run id>/crash-reports/<player>/*.txt                    # only when a client crashed
```

Never included: server jars, client jars, assets, Java runtimes, worlds.

Each run directory is a separate run for the viewer. The viewer also reads the **flat layout** (a `result.json` directly at the artifact root, as written by the v2 Python runner, and as left by `actions/download-artifact` when it extracts a single artifact), and treats an artifact as nested when it has no `result.json` of its own but one or more `<child>/result.json` (see §3).

`result.json` is written three times: a stub when the server's tests are planned (every test `skipped` with `skipReason: "not run"`), again after every test, and a final version at teardown (also when interrupted). A job that is killed by `timeout-minutes` or loses its runner therefore still has the results of the tests that finished.

## 2. `result.json` (schemaVersion 2)

The JSON Schema is [`schema/result.v2.json`](../schema/result.v2.json). It is maintained by hand as the contract; `ResultSchemaTest` (in `kotlin/src/test`) validates both the Kotlin writer's output and every `ui/fixtures` result against it. The Kotlin model is `party.morino.fukurou.result.model.run.ResultV2`. Keys are camelCase.

```json
{
  "schemaVersion": 2,
  "id": "paper-1.21.11-stamp-arena",
  "label": "stamp-arena",
  "status": "failed",
  "fukurou": { "version": "3.0.0", "portablemc": "5.0.4", "runner": "kotlin" },
  "minecraft": { "version": "1.21.11", "server": "paper", "build": 130, "channel": "STABLE" },
  "java": { "server": 21 },
  "plugins": [
    { "file": "Stamp-1.0.jar", "sha256": "...", "name": "Stamp", "version": "1.0", "role": "under-test", "source": null, "classFileMajor": 65, "enabled": true },
    { "file": "ProtocolLib.jar", "sha256": "...", "name": "ProtocolLib", "version": "5.4.0", "role": "dependency", "source": "github:dmulloy2/ProtocolLib@dev-build/ProtocolLib.jar", "classFileMajor": 65, "enabled": true }
  ],
  "suite": { "source": "junit:com.example.StampArena", "sha256": "...", "isolation": "reset", "settle": 3, "gamemode": "survival", "arena": { "size": 32, "height": 24 } },
  "selection": { "tests": [], "tags": [], "isolation": null, "failFast": false },
  "players": [ { "name": "Alice", "joined": true }, { "name": "Bob", "joined": true } ],
  "summary": { "total": 3, "passed": 1, "failed": 1, "error": 0, "skipped": 1 },
  "sessions": [
    {
      "index": 0, "kind": "initial",
      "startedAt": "2026-09-24T03:01:00Z", "finishedAt": "2026-09-24T03:05:12Z",
      "players": ["Alice", "Bob"], "tests": ["greeting", "farewell"],
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
      "id": "greeting", "name": "greets the player", "order": 0,
      "source": "junit:com.example.StampTest#greeting", "sha256": "...",
      "tags": ["chat"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "passed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3400, "error": null },
      "steps": [
        { "index": 0, "phase": "beforeEach", "fixture": null, "on": "server", "action": "command", "label": "fill -3 -61 -3 3 -61 3 minecraft:stone", "status": "passed", "durationMs": 20, "error": null, "screenshot": null },
        { "index": 1, "phase": "test", "fixture": null, "on": "Alice", "action": "chat", "label": "/hello", "status": "passed", "durationMs": 900, "error": null, "screenshot": null },
        { "index": 2, "phase": "test", "fixture": null, "on": "server", "action": "query", "label": "player Alice", "status": "passed", "durationMs": 12, "error": null, "screenshot": null },
        { "index": 3, "phase": "test", "fixture": null, "on": "Alice", "action": "screenshot", "label": "hello", "status": "passed", "durationMs": 1900, "error": null, "screenshot": "tests/greeting/screenshots/Alice/hello.png" }
      ],
      "failure": null,
      "screenshots": [
        { "player": "Alice", "name": "hello", "path": "tests/greeting/screenshots/Alice/hello.png", "width": 1280, "height": 720, "stepIndex": 3 }
      ],
      "logRanges": {
        "logs/sessions/0/server.log": { "from": 212, "to": 240 },
        "logs/sessions/0/clients/Alice.log": { "from": 88, "to": 103 }
      },
      "startedAt": "2026-09-24T03:02:10Z", "durationMs": 31000
    },
    {
      "id": "farewell", "name": "farewell", "order": 1,
      "source": "junit:com.example.StampTest#farewell", "sha256": "...",
      "tags": ["chat"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "failed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3300, "error": null },
      "steps": [
        { "index": 0, "phase": "test", "fixture": null, "on": "server", "action": "subscribe", "label": "org.bukkit.event.player.PlayerQuitEvent", "status": "passed", "durationMs": 15, "error": null, "screenshot": null },
        { "index": 1, "phase": "test", "fixture": null, "on": "server", "action": "await_event", "label": "org.bukkit.event.player.PlayerQuitEvent", "status": "failed", "durationMs": 10004, "error": "...", "screenshot": null }
      ],
      "failure": { "phase": "scenario", "message": "...", "stepIndex": 1 },
      "screenshots": [
        { "player": "Alice", "name": "failure", "path": "tests/farewell/screenshots/Alice/failure.png", "width": 1280, "height": 720, "stepIndex": 1 }
      ],
      "logRanges": { "logs/sessions/0/server.log": { "from": 241, "to": 262 } },
      "startedAt": "2026-09-24T03:02:45Z", "durationMs": 16200
    },
    {
      "id": "legacy-format", "name": "legacy-format", "order": 2,
      "source": "junit:com.example.StampTest#legacy-format", "sha256": "...",
      "tags": [], "isolation": "reset", "timeout": 600, "versions": "1.21.6-1.21.9", "session": null,
      "status": "skipped", "skipReason": "versions: 1.21.6-1.21.9 does not include 1.21.11",
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": null, "steps": [], "failure": null, "screenshots": [], "logRanges": null,
      "startedAt": null, "durationMs": null
    }
  ],
  "failure": null,
  "logs": [ { "kind": "harness", "path": "logs/harness.log", "player": null } ],
  "startedAt": "2026-09-24T03:00:00Z", "finishedAt": "2026-09-24T03:05:30Z", "durationMs": 330000,
  "ci": { "repository": "example/plugin", "sha": "...", "ref": "refs/pull/1/merge", "runId": "123", "runAttempt": "1", "serverUrl": "https://github.com" }
}
```

### Run (one server, one Minecraft version)

- `id` is `<server>-<minecraft version>-<label>`, the same as the run directory name. When two servers of one JVM would get the same id, the second gets `-2`, the third `-3`, and so on. Minecraft versions never contain `-` (pre-releases and snapshots are not supported), so the id splits unambiguously.
- `label` names the server: the extension's `ServerSpec.label`, by default its class name in kebab case (`StampArena` → `stamp-arena`). It matches `^[a-z0-9]+(?:-[a-z0-9]+)*$` and has at most 40 characters. Results written by fukurou before v3 may have `label: null` and an id without it.
- `fukurou` is `{ "version", "portablemc", "runner" }`. `runner` is always `"kotlin"` for v3 (it was `null` for the removed Python runner).
- `status`:
  - `error` when `failure` is set. `failure` is `{ "phase": "setup" | "server-start" | "client-join" | "server" | "teardown" | "interrupted", "message": "..." }`: the infrastructure kept tests from running, and the remaining tests are `skipped`. A server that dies during a session is not restarted (`phase: "server"`, message `server died during <test id>: ...`).
  - otherwise `failed` when any test is `failed` or `error`.
  - otherwise `passed` (`skipped` tests count as success).
- `summary` counts `tests[].status`.
- `minecraft` is `{ "version", "server", "build", "channel" }`. `server` is `"paper"`. `build` is the Paper build number and `channel` is the channel of that build as Paper reports it: `STABLE`, `BETA` or `ALPHA`. A run uses the newest build whose channel is at least as stable as `fukurou.paperChannel` (default `stable`), so `channel` is `BETA` or `ALPHA` only when the run allowed it, or when `fukurou.paperBuild` named such a build (fukurou logs a warning then). `build` and `channel` are `null` when the run failed before a build was picked. The viewer shows a small `alpha` / `beta` badge next to the version of a non-`STABLE` run.
- `java` is `{ "server": <major> }`, the Java version the server ran on (see `fukurou.serverJava`).
- `plugins[]` are the declared plugins (`role` `under-test` or `dependency`) in declaration order, with the name and version from their descriptor, the newest class file major in the jar, `source` (`null` for a local jar, otherwise the URL or `github:<owner/repo>@<tag>/<asset>`), and whether the server `enabled` them. The automatically installed `FukurouAgent` is not listed.
- `suite` describes the extension and the isolation that applied. `source` is `junit:<fully qualified class name of the extension>` and `sha256` the hash of its `.class` file. `isolation`, `settle` (seconds), `gamemode` and `arena` (`{ "size", "height" }`, or `false` when the arena reset is disabled) come from `ServerSpec.isolation`; `Isolation.None` is written as `isolation: "reset"`, `settle: 0`, `arena: false`. `suite` is `null` only when the run failed before the tests were planned.
- `selection` records `fukurou.selection.tests` (`tests`) and `fukurou.selection.tags` (`tags`), which the build passes for information only (tests are filtered by Gradle and JUnit). `isolation` is always `null` and `failFast` always `false`.
- `players` are the players declared by the extension, with `joined` once they joined. `op` is per test (`tests[].players[].op`).
- `sessions[]`: `kind` is `initial` (index 0) or `fresh-server` (one per server restart: `Isolation.FreshServer`, `@FreshServer`, or a restart after the server was stopped to fit the memory budget). `players` joined that session, `tests` ran in it (in order), `logs` are its server and client logs (crash reports use `kind: "crash"`). `failure` is the message when the server died during that session, else `null`.
- `logs` at the root holds logs that belong to no session (`logs/harness.log`).
- `ci` is filled from the `GITHUB_*` environment variables when `GITHUB_ACTIONS=true`, else `null`.

### Tests

- A test is one JUnit test method (or one invocation of a parameterized test) that used this server. `id` is `@GameTestId`, or the method name with characters outside `[A-Za-z0-9_.-]` replaced by `-`; it matches `^[A-Za-z0-9][A-Za-z0-9_.-]*$`. An id used twice in one result becomes `<ClassSimpleName>.<id>`, and invocations of a parameterized test get `-<n>`. The id is used in `tests/<id>/...` and in viewer routes. `name` is `@DisplayName` or the method name. `order` is the planned order.
- `source` is `junit:<fully qualified class name>#<method name>`, and `sha256` the hash of the test class's `.class` file. `tags` are the JUnit tags of the method and its class. `isolation` is `fresh-server` for a `@FreshServer` test or an `Isolation.FreshServer` server, else `reset`. `timeout` is the test's soft deadline in seconds (`@GameTimeout` or `ServerSpec.testTimeout`). `versions` is the `@MinecraftVersions` spec, or `null`.
- `status`:
  - `passed`: the test method returned normally.
  - `failed`: the test threw an `AssertionError` or any exception from the test code, including `CommandFailedError`, `LogAssertionError`, `EventAssertionError`, `ServerTaskException` and `AgentRequestException`. `failure.phase` is the phase of the failing step, or of the code that was running: `beforeEach` (the extension's `setUp` and JUnit `@BeforeEach`), `fixture` (inside `server.fixture(name) { }`) or `scenario` (the test method itself; the name is kept from the scenario era). A test that saw the server die is `failed` with phase `scenario`, and the run gets `failure.phase: "server"`.
  - `error`: the harness failed around the test. `failure.phase` is `reset` (a reset command's reply was an error, see `reset.error`), `client` (a client process died) or `timeout` (the test's deadline passed, a harness wait or an agent request timed out, or the JUnit timeout fired).
  - `skipped`: the test did not run, or did not run to completion. `skipReason` is required. It is free text; fukurou currently writes `not run`, `versions: <spec> does not include <version>`, the reason of `@Disabled` or of a failed assumption, `server died during <id>`, `client relaunch failed: <player>` and `interrupted` (SIGTERM, Ctrl+C or a cancelled Gradle build while the test was running). A test abandoned this way keeps no trace of its partial run (`session`, `reset`, `steps`, `screenshots` and `logRanges` are reset as for a test that never ran) and is not listed in `sessions[].tests`.
- `failure` is `{ "phase", "message", "stepIndex" }` or `null`. `stepIndex` is an index into this test's `steps`, or `null` when no step caused it.
- `session` is the index into `sessions` the test ran in, or `null` when it did not run.
- `players` are the server's players with their `op` flag. The reset ops or deops every joined player to match.
- `reset` is the harness reset before the test (not a step): `{ "durationMs", "error" }`, or `null` when the test did not run.
- `steps[]`: see [Steps](#steps).
- `screenshots[]` live under `tests/<id>/screenshots/<player>/<name>.png`. Names are unique per player within a test. The name `failure` is reserved for the screenshot fukurou takes of every player when the test fails.
- `logRanges` maps a log path (as listed in `sessions[].logs`) to the lines written while the test ran: `{ "from", "to" }`, 1-based and inclusive. The range starts at the harness reset, so the reset's own console output (the RCON replies) is included as a clue for `reset.error`; the test's log waits (`awaitLog`, `awaitChat`, …) only match lines written after the reset finished. Paths are the keys because a relaunched client writes to a new file. It is `null` when the test did not run.

### Steps

Every call of the API inside a test is one entry of `tests[].steps`, in the order the steps started (see [docs/usage.md](usage.md#steps-in-resultjson) for which call writes which entry):

```json
{ "index": 7, "phase": "test", "fixture": null, "on": "Bob", "action": "screenshot", "label": "shot-2", "status": "passed",
  "durationMs": 1800, "error": null, "screenshot": "tests/greeting/screenshots/Bob/shot-2.png",
  "parallel": { "block": 1, "lane": 1 },
  "repeat": [ { "block": 0, "iteration": 2, "of": 3 } ],
  "startedAt": "2026-09-24T03:02:14.120Z", "finishedAt": "2026-09-24T03:02:15.920Z" }
```

- `index` is the position in this test's `steps`. `phase` is `beforeEach` (inside the extension's `setUp`), `fixture` (inside `server.fixture(name) { }`, with `fixture` set to the name) or `test`; `fixture` is `null` outside a fixture. Steps outside a test (`onStarted`, `tearDown`) are not recorded here, only in `logs/harness.log`.
- `on` is `"server"`, a player name, or `null` for steps that belong to no server or player (`wait`, `step`, `eventually`, `await_until`).
- `action` is a free string (the schema does not restrict it). fukurou writes `command`, `wait_for_log`, `assert_no_log`, `wait`, `press_key`, `type_text`, `chat`, `screenshot` (the v2 names) and, since v3, `hold_key`, `mouse_move`, `click`, `hold_mouse`, `scroll`, `query`, `wait_ticks`, `execute`, `subscribe`, `await_event`, `assert_no_event`, `step`, `eventually`, `await_until` and `compare_screenshot`. A reader must accept unknown actions.
- `label` describes the step (the command, the pattern, the key, the task name, ...). `screenshot` is the artifact path for `screenshot` steps, else `null`.
- `status` is `passed`, `failed` or `skipped`, with `error` set for a failed step. Steps after a failure are not written (the code after a throw does not run).
- `startedAt` / `finishedAt` are ISO 8601 UTC timestamps (millisecond precision) of the step's run, or `null` when it did not run.
- `step(label) { }` writes one `step` entry for the block and, after it, the entries of the steps inside. `eventually { }` and `awaitUntil { }` write one entry each; the attempts inside are not recorded.

#### Parallel and repeat blocks

`parallel { lane { } }` and `repeat(n) { }` have no entries of their own. The steps inside carry these optional fields; a reader treats a missing field as `null`:

- `parallel` is `{ "block", "lane" }` for a step inside a parallel block, else `null`. `block` numbers the parallel blocks of the test from 0 in the order they ran. `lane` is the lane's position in the block from 0 (declaration order). The steps of one block are written next to each other in `steps`, lane 0 first, when the block finishes; lanes run at the same time, so their `durationMs` overlap and `startedAt` / `finishedAt` show by how much. `failure.stepIndex` and `screenshots[].stepIndex` point at the final positions.
- `repeat` is the list of enclosing repeat blocks, outermost first, or `null` outside any repeat. Each item is `{ "block", "iteration", "of" }`: `block` numbers the repeat blocks of the test from 0, `iteration` counts from 1, and `of` is the number of iterations. fukurou v3 does not nest `repeat`, so the list has exactly one item, but readers must accept longer lists (written by fukurou 2.1's scenario runner). The list is never empty.
- A parallel block fails when any of its lanes fails. Lanes that were already running finish and keep their own `status`; the test fails with the lane failure that came first. If the test's deadline passes during a block, the lanes are stopped: a step that was still running is recorded as `failed` with an error starting with `the test exceeded its timeout`, and the test is `error` with `failure.phase: "timeout"` unless a lane had already failed. If the server dies in one lane, the others are cut short with an error starting with `cancelled:`.

### Compatibility

Adding fields is backwards compatible, and the viewer ignores unknown fields. Removing or changing the meaning of a field bumps `schemaVersion`. fukurou v3 writes only version 2, the same version as v2.x, so the viewer reads results of v2 and v3 alike. The viewer reads only version 2: runs with any other `schemaVersion` are shown as "unsupported" instead of breaking the page. The version 1 schema (`schema/result.v1.json`) is only in the `v1` and `v2` tags.

## 3. Site (written by `fukurou/ui`)

`ui/scripts/build_manifest.py --artifacts-dir <dir> --out <site>` collects `<dir>/<artifact>/result.json` (and `<dir>/<artifact>/<run id>/result.json` for nested bundles) and writes:

```
index.html, assets/*                               # prebuilt viewer copied from ui/dist (relative base, hash routing)
manifest.json                                      # schema below
manifest.js                                        # window.__FUKUROU_MANIFEST__ = {...}; loaded as a classic script so file:// works
runs/<run id>/result.json
runs/<run id>/tests/<test id>/screenshots/<player>/<name>.png
runs/<run id>/logs/...                             # only when include-logs is true
runs/<run id>/crash-reports/...                    # only when include-logs is true
```

When `actions/download-artifact` matches a single artifact it extracts it directly into the target directory. `build_manifest.py` detects that layout (a `result.json`, or only `tests/`, `logs/` and `crash-reports/` directories) and reads it as one run named `fukurou-<result.id>`.

An artifact directory without its own `result.json` that is not that flat layout, but has one or more `<child>/result.json`, is a nested bundle (§1): every child directory becomes a run whose `artifact` is `<artifact>/<child>`. A child without `result.json` is still a run and gets the usual "result.json missing" warning. When a single nested bundle is downloaded, its run directories land directly in `<dir>` and are read as artifacts of their own.

When a run has no usable `result.id`, its id comes from the end of the artifact name (for a nested child, of the child directory name): `<server>-<version>[-<label>]` where `<server>` is any lowercase type id (`fukurou-paper-1.21.9` → `paper-1.21.9`, `fukurou-paper-26.3-stamp-arena` → `paper-26.3-stamp-arena`), otherwise the sanitized artifact name.

### `manifest.json` (schemaVersion 2)

```json
{
  "schemaVersion": 2,
  "generator": { "name": "fukurou-ui", "version": "2.0.0" },
  "generatedAt": "2026-09-24T03:10:00Z",
  "title": "ExamplePlugin abc1234",
  "ci": { "repository": "example/plugin", "sha": "...", "ref": "refs/pull/1/merge", "runId": "123", "runAttempt": "1", "runUrl": "https://github.com/example/plugin/actions/runs/123" },
  "summary": {
    "runs": { "total": 2, "passed": 1, "failed": 1, "error": 0 },
    "tests": { "total": 5, "passed": 3, "failed": 1, "error": 0, "skipped": 1 }
  },
  "players": ["Alice", "Bob"],
  "tests": [
    {
      "id": "greeting", "name": "greeting", "tags": ["chat"],
      "players": ["Alice", "Bob"], "shots": ["hello"], "status": "passed",
      "cells": { "paper-1.21.10": "passed", "paper-1.21.11": "passed" }
    },
    {
      "id": "farewell", "name": "farewell", "tags": ["chat"],
      "players": ["Alice", "Bob"], "shots": ["bye"], "status": "failed",
      "cells": { "paper-1.21.10": "passed", "paper-1.21.11": "failed" }
    },
    {
      "id": "legacy-format", "name": "legacy-format", "tags": [],
      "players": ["Alice"], "shots": [], "status": "skipped",
      "cells": { "paper-1.21.11": "skipped" }
    }
  ],
  "runs": [
    {
      "id": "paper-1.21.11",
      "artifact": "fukurou-paper-1.21.11",
      "base": "runs/paper-1.21.11/",
      "label": null,
      "status": "failed",
      "result": { "...": "the whole result.json, embedded" }
    }
  ],
  "warnings": ["fukurou-paper-1.21.7: result.json missing (job cancelled?)"]
}
```

- `runs` are sorted by Minecraft release order (oldest first), then by `label`, then by artifact name. Version strings are compared numerically part by part (`1.21.10` > `1.21.9`, `26.1` > `1.21.11`). A run's `status` is its `result.status`.
- `label` is the supported result's `label` (`null` when it has none, when there is no supported result, or when it is not a safe name).
- An artifact without `result.json` becomes a run with `status: "error"`, `result: null`, and a warning.
- A run without a supported `result` (no `result.json`, or a `schemaVersion` other than 2) also gets `logs`, in the same shape as `result.logs`, when `logs/harness.log` was copied into the site: `[{ "kind": "harness", "path": "logs/harness.log", "player": null }]`. This field is optional. It is missing for runs with a supported result (they use `result.logs` / `result.sessions[].logs`) and for sites built with `--no-include-logs`.
- A `result.json` whose `schemaVersion` is not 2 is embedded as is, but the run gets `status: "error"`, does not appear in any `cells`, and adds the warning `<artifact>: unsupported result schemaVersion 1`. There is no reader for version 1.
- `tests` is the union of every supported run's `tests[]`, in the execution order of the run where each test first appears (runs taken oldest first). `cells` maps a run id to that test's status in the run. A run missing from `cells` is shown as "not run" (for example when a filter selected the test in only some versions). It is not counted as an error.
- `tests[].status` is the strongest status across its cells, in the order `error` > `failed` > `passed` > `skipped`, so failures can be sorted first. `tests[].players` and `tests[].shots` are the unions for that test across runs; `shots` excludes `failure`. `tests[].tags` is the union of the test's tags.
- `summary.runs` counts runs by status. `summary.tests` counts the cells (test × run), without "not run".
- `players` is the union of `result.players` across runs.
- Paths inside `result` stay relative to the artifact root. The viewer resolves them against the run's `base`. Before embedding, `build_manifest.py` drops paths that are absolute, contain `..` or use backslashes from `tests[].screenshots`, `tests[].steps[].screenshot`, `tests[].logRanges` keys, `sessions[].logs` and `logs`, with a warning. It also drops tests whose `id` is not a valid test id or repeats an earlier id in the same run.

### Action outputs

`build_manifest.py` writes these to `$GITHUB_OUTPUT` and a Markdown summary (runs table and test × run table; a labelled run's column is `<version>/<label>`) to `$GITHUB_STEP_SUMMARY`:

| output | value |
| --- | --- |
| `status` | `passed` when every run passed, `failed` otherwise, `empty` when there were no runs |
| `summary` | `summary.runs` as compact JSON |
| `tests-summary` | `summary.tests` as compact JSON: `{"total","passed","failed","error","skipped"}` |
| `failed-tests` | the `failed` and `error` cells as `<test id>@<minecraft version>`, or `<test id>@<minecraft version>/<label>` for a labelled run, comma separated |
