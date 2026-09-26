# Results contract

This is the only interface between the test runner (`fukurou`) and the viewer (`fukurou/ui`). Both live in this repository and are released together under one tag.

A `result.json` describes one server: fukurou v2 runs every selected test for one Minecraft version in one job, one process and one server session. Paper starts once, the union of all tests' players joins once, and the tests run in order. Between tests the harness resets the world and the clients. A test with `isolation: fresh-server` gets its own server session.

## 1. Per-version output directory (one artifact per Minecraft version)

`fukurou run` writes everything under `--out-dir`. The action uploads that directory as the artifact `<artifact-prefix>-<server>-<minecraft version>` (for example `fukurou-paper-1.21.11`). It is uploaded even when the run fails.

```
result.json                                    # schemaVersion 2; written right after discovery, after every test, and at teardown
tests/<test id>/screenshots/<player>/<name>.png
tests/<test id>/screenshots/<player>/failure.png
logs/harness.log                               # fukurou's own output
logs/sessions/<n>/server.log                   # the server console record of session n (includes JVM errors); target of logRanges
logs/sessions/<n>/clients/<player>.log         # the client's latest.log from its first launch in session n
logs/sessions/<n>/clients/<player>.<k>.log     # the latest.log after the client was relaunched for the k-th time (k >= 2)
crash-reports/<player>/*.txt                   # only when a client crashed
```

Never included: server jars, client jars, assets, Java runtimes, worlds.

**Nested bundle.** The Kotlin library (fukurou-kotlin, see the README) can run several independent servers of one Minecraft version in one job. It writes one run directory per server, each with exactly the layout above, and uploads them together as one artifact:

```
<artifact>/<run id>/result.json                # for example fukurou-paper-26.3/paper-26.3-stamp-arena/result.json
<artifact>/<run id>/tests/...
<artifact>/<run id>/logs/...
```

Each run directory is a separate run for the viewer. The Python runner always writes the flat layout.

`result.json` is written three times: a stub right after the tests are discovered (every test `skipped` with `skipReason: "not run"`), again after every test, and a final version at teardown (also when interrupted). A job that is killed by `timeout-minutes` or loses its runner therefore still has the results of the tests that finished.

## 2. `result.json` (schemaVersion 2)

Defined by the pydantic model `fukurou.result.model.ResultV2`. The generated JSON Schema lives at `schema/result.v2.json` (`fukurou schema result`). Keys are camelCase.

```json
{
  "schemaVersion": 2,
  "id": "paper-1.21.11",
  "label": null,
  "status": "failed",
  "fukurou": { "version": "2.0.0", "portablemc": "5.0.4", "runner": null },
  "minecraft": { "version": "1.21.11", "server": "paper", "build": 130, "channel": "STABLE" },
  "java": { "server": 25 },
  "plugins": [
    { "file": "ExamplePlugin-all.jar", "sha256": "...", "name": "ExamplePlugin", "version": "1.0", "role": "under-test", "source": null, "classFileMajor": 69, "enabled": true },
    { "file": "ProtocolLib.jar", "sha256": "...", "name": "ProtocolLib", "version": "5.4.0", "role": "dependency", "source": "github:dmulloy2/ProtocolLib@dev-build/ProtocolLib.jar", "classFileMajor": 65, "enabled": true }
  ],
  "suite": { "source": "file:game-test/fukurou.yml", "sha256": "...", "isolation": "reset", "settle": 3, "gamemode": "survival", "arena": { "size": 32, "height": 24 } },
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
      "id": "greeting", "name": "greeting", "order": 0,
      "source": "file:game-test/scenarios/greeting.json", "sha256": "...",
      "tags": ["chat"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "passed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3400, "error": null },
      "steps": [
        { "index": 0, "phase": "fixture", "fixture": "arena", "on": "server", "action": "command", "label": "fill 0 -60 0 0 -50 0 minecraft:stone", "status": "passed", "durationMs": 20, "error": null, "screenshot": null },
        { "index": 5, "phase": "test", "fixture": null, "on": "Alice", "action": "screenshot", "label": "hello", "status": "passed", "durationMs": 1900, "error": null, "screenshot": "tests/greeting/screenshots/Alice/hello.png" }
      ],
      "failure": null,
      "screenshots": [
        { "player": "Alice", "name": "hello", "path": "tests/greeting/screenshots/Alice/hello.png", "width": 1280, "height": 720, "stepIndex": 5 }
      ],
      "logRanges": {
        "logs/sessions/0/server.log": { "from": 212, "to": 240 },
        "logs/sessions/0/clients/Alice.log": { "from": 88, "to": 103 }
      },
      "startedAt": "2026-09-24T03:02:10Z", "durationMs": 31000
    },
    {
      "id": "farewell", "name": "farewell", "order": 1,
      "source": "file:game-test/scenarios/farewell.json", "sha256": "...",
      "tags": ["chat"], "isolation": "reset", "timeout": 600, "versions": null, "session": 0,
      "status": "failed", "skipReason": null,
      "players": [ { "name": "Alice", "op": true }, { "name": "Bob", "op": false } ],
      "reset": { "durationMs": 3300, "error": null },
      "steps": [
        { "index": 0, "phase": "test", "fixture": null, "on": "server", "action": "wait_for_log", "label": "Alice issued server command: /bye", "status": "failed", "durationMs": 10004, "error": "server log did not match '...' within 10s", "screenshot": null },
        { "index": 1, "phase": "test", "fixture": null, "on": "Bob", "action": "screenshot", "label": "bye", "status": "skipped", "durationMs": null, "error": null, "screenshot": null }
      ],
      "failure": { "phase": "scenario", "message": "server log did not match '...' within 10s", "stepIndex": 0 },
      "screenshots": [
        { "player": "Alice", "name": "failure", "path": "tests/farewell/screenshots/Alice/failure.png", "width": 1280, "height": 720, "stepIndex": 0 }
      ],
      "logRanges": { "logs/sessions/0/server.log": { "from": 241, "to": 262 } },
      "startedAt": "2026-09-24T03:02:45Z", "durationMs": 16200
    },
    {
      "id": "legacy-format", "name": "legacy-format", "order": 2,
      "source": "file:game-test/scenarios/legacy-format.json", "sha256": "...",
      "tags": [], "isolation": "reset", "timeout": 600, "versions": "1.21.6-1.21.9", "session": null,
      "status": "skipped", "skipReason": "versions: 1.21.6-1.21.9 does not include 1.21.11",
      "players": [ { "name": "Alice", "op": true } ],
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

### Run (one Minecraft version)

- `id` is `<server>-<minecraft version>[-<label>]`, the same as the end of the artifact name (or the run directory name in a nested bundle). All tests of a server share one result, so ids never collide.
- `label` is optional. It is `null` for the Python runner. When one job writes several results for one version (fukurou-kotlin, one per server), `label` names the server (kebab case, `^[a-z0-9]+(?:-[a-z0-9]+)*$`, at most 40 characters) and `id` is `<server>-<minecraft version>-<label>`, for example `paper-26.3-stamp-arena`. Minecraft versions never contain `-` (pre-releases and snapshots are not supported), so the id splits unambiguously.
- `fukurou` is `{ "version", "portablemc", "runner" }`. `runner` is optional: `"kotlin"` for the JVM runner, `null` for the Python runner.
- `status`:
  - `error` when `failure` is set. `failure` is `{ "phase": "setup" | "server-start" | "client-join" | "server" | "teardown" | "interrupted", "message": "..." }`: the infrastructure kept tests from running, and the remaining tests are `skipped`. A server that dies during a session is not restarted (`phase: "server"`). `phase: "server"` also covers an unexpected exception inside the harness while tests were running; the `message` then describes that exception (a server death says `server died during <id>: ...`).
  - otherwise `failed` when any test is `failed` or `error`.
  - otherwise `passed` (`skipped` tests count as success).
- `summary` counts `tests[].status`.
- `minecraft` is `{ "version", "server", "build", "channel" }`. `build` is the Paper build number and `channel` is the channel of that build as Paper reports it: `STABLE`, `BETA` or `ALPHA`. A run uses the newest build whose channel is at least as stable as `--paper-channel` (default `stable`), so `channel` is `BETA` or `ALPHA` only when the run allowed it, or when `--server-build` named such a build (fukurou logs a warning then). `build` and `channel` are `null` when the run failed before a build was picked. The viewer shows a small `alpha` / `beta` badge next to the version of a non-`STABLE` run.
- `suite` describes the suite file (`source` / `sha256` are `null` when the run used only `--scenario-file` / `--scenario`) and the reset settings that applied. `arena` is `{ "size", "height" }`, or `false` when the arena reset is disabled. `suite` is `null` only when the run failed before the tests were discovered.
- `selection` records the filters: `--test` ids or globs (`tests`), `--tag` (`tags`), a forced `--isolation`, and `--fail-fast`. Empty lists mean "no filter".
- `players` is the union of all tests' players, joined once per session. `op` is per test (`tests[].players[].op`).
- `sessions[]`: `kind` is `initial` (index 0) or `fresh-server` (one per `isolation: fresh-server` test). `players` joined that session, `tests` ran in it (in order), `logs` are its server and client logs (crash reports use `kind: "crash"`). `failure` is the message when the server died during that session, else `null`.
- `logs` at the root holds logs that belong to no session (`logs/harness.log`).

### Tests

- `id` is the scenario file name without its extension (`inline` for `--scenario`) and matches `^[A-Za-z0-9][A-Za-z0-9_.-]*$`. It is used in `tests/<id>/...` and in viewer routes. `order` is the execution order: `reset` tests in declaration order, then `fresh-server` tests in declaration order.
- `status`:
  - `passed`: every step passed (skipped steps for players outside the test are fine).
  - `failed`: a step failed. `failure.phase` is `beforeEach`, `fixture` or `scenario`, which separates suite setup failures from failures of the plugin under test.
  - `error`: the harness failed around the test. `failure.phase` is `reset` (a reset command's RCON reply was an error, see `reset.error`), `client` (a client process died) or `timeout` (the test's `timeout` passed).
  - `skipped`: the test did not run, or did not run to completion. `skipReason` is required. It is free text; the runner currently writes `versions: <spec> does not include <version>`, `fail-fast`, `not run`, `server died during <id>`, `client relaunch failed: <player>`, `interrupted` (SIGTERM / Ctrl+C while the test was running, including during its failure screenshots) and `harness error: <ExceptionName>: <message>` (an unexpected exception inside the harness; the run's `failure` carries the same message). A test abandoned this way keeps no trace of its partial run (`session`, `reset`, `steps`, `screenshots` and `logRanges` are reset as for a test that never ran) and is not listed in `sessions[].tests`.
- `failure` is `{ "phase", "message", "stepIndex" }` or `null`. `stepIndex` is an index into this test's `steps`, or `null` when no step caused it.
- `session` is the index into `sessions` the test ran in, or `null` when it did not run.
- `players` are this test's players with their `op` flag. The reset ops or deops every joined player to match.
- `reset` is the harness reset before the test (not a step): `{ "durationMs", "error" }`, or `null` when the test did not run.
- `steps[]` are expanded in the order `beforeEach` → `use` fixtures → the test's own steps. `phase` is `beforeEach`, `fixture` or `test`. `fixture` is the fixture name when `phase` is `fixture`, else `null`. `index` is the position in this test's `steps`. After the first failed step the remaining steps are `skipped`. A `beforeEach` or fixture step aimed at a player outside the test is `skipped` with `error: "player Bob is not in this test"`.
- `screenshots[]` live under `tests/<id>/screenshots/<player>/<name>.png`. Names are unique per player within a test. The name `failure` is reserved for the screenshot fukurou takes of every player of the test when it fails.
- `logRanges` maps a log path (as listed in `sessions[].logs`) to the lines written while the test ran: `{ "from", "to" }`, 1-based and inclusive. The range starts at the harness reset, so the reset's own console output (the RCON replies) is included as a clue for `reset.error`; the test's `wait_for_log` / `assert_no_log` steps only match lines written after the reset finished. Paths are the keys because a relaunched client writes to a new file. It is `null` when the test did not run.

### Parallel and repeat blocks (fukurou 2.1)

fukurou 2.1 adds three ways to write steps: `{"action": "parallel", "steps": [...]}` runs its children at the same time, `{"action": "repeat", "times": N, "as": "i", "steps": [...]}` runs its steps N times, and a player action may name several players (`"on": ["Alice", "Bob"]`), which is a `parallel` block with one copy per player. The blocks are expanded when the tests are planned, so `result.json` keeps `schemaVersion: 2` and lists only the expanded steps. The blocks themselves have no entries.

Every entry of `tests[].steps` gets these optional fields. Runners older than 2.1 do not write them, and a reader treats a missing field as `null`:

```json
{ "index": 7, "phase": "test", "fixture": null, "on": "Bob", "action": "screenshot", "label": "shot-2", "status": "passed",
  "durationMs": 1800, "error": null, "screenshot": "tests/greeting/screenshots/Bob/shot-2.png",
  "parallel": { "block": 1, "lane": 1 },
  "repeat": [ { "block": 0, "iteration": 2, "of": 3 } ],
  "startedAt": "2026-09-24T03:02:14.120Z", "finishedAt": "2026-09-24T03:02:15.920Z" }
```

- `parallel` is `{ "block", "lane" }` for a step inside a parallel block, else `null`. `block` numbers the parallel blocks of the test from 0 in plan order, across `beforeEach`, fixtures and the test's own steps. Each expanded copy is a separate block, so a parallel block inside a `repeat` of 3 gives 3 blocks. `lane` is the child's position in the block from 0. A child with several players in `on` takes one lane per player. The steps of one block are next to each other in `steps`, lane 0 first. Lanes run at the same time, and the steps of one lane (a `repeat` child) run one after another.
- `repeat` is the list of enclosing repeat blocks, outermost first, or `null` outside any repeat. Each item is `{ "block", "iteration", "of" }`: `block` numbers the repeat blocks of the test from 0 in plan order (an inner repeat gets a new number for every iteration of the outer one), `iteration` counts from 1, and `of` is `times`. The list is never empty.
- `startedAt` / `finishedAt` are ISO 8601 UTC timestamps of the step's run, or `null` when it did not run. The `durationMs` of steps in one parallel block overlap, and these timestamps show by how much.
- `index` stays the position in `steps` (plan order), so `failure.stepIndex` and `screenshots[].stepIndex` still point into it.
- A parallel block fails when any of its children fails. Children that were already running finish and keep their own `status`. The steps after the block are `skipped` as usual. When several children fail, `failure` is the one that failed first in time (a client that died in one lane can be reported on a sibling's step, as in sequential runs). If the test's `timeout` passes during a block, the runner stops the lanes: a step that was waiting (`wait`, `wait_for_log`) or still running is recorded as `failed` with an error starting with `the test exceeded its timeout` (its `durationMs` may be `null` when the lane did not return in time), steps not started stay `skipped`, and the test is `error` with `failure.phase: "timeout"` as before, unless a child had already failed: then that earlier failure is kept and the cut-short steps still carry the timeout error. If the server dies in one lane, the waiting siblings are cut short with an error starting with `cancelled:` and the run fails with phase `server`.
- A `beforeEach` or fixture step for a player outside the test is skipped one expanded step at a time. For `"on": ["Alice", "Bob"]` in a test without Bob, only Bob's copy is `skipped` with `error: "player Bob is not in this test"`.
- Screenshot names are checked after expansion and are still unique per player within a test. The same `name` for several players in one `on` list is fine because screenshots are stored per player.

The scenario rules (checked by `fukurou validate` before anything starts):

- `parallel`: 1 to 16 children after expansion. A `parallel` cannot contain another `parallel`, either directly or inside a `repeat` child, and a step with several players in `on` counts as a parallel block. A step with several players in `on` written directly as a child of `parallel` adds one lane per player instead. Two children must not both send client input (`press_key`, `type_text`, `chat`, `screenshot`) to the same player. Server actions, `wait`, `wait_for_log` and `assert_no_log` may appear in several children.
- `repeat`: `times` is 1 to 100 and `as` matches `^[a-z][a-z0-9_]*$` (default `i`). In the `text`, `command`, `pattern`, `name` and `key` of the steps inside, `${i}` becomes the iteration number counted from 1 and `${i0}` the number counted from 0. A placeholder whose name is not defined by an enclosing repeat is an error. A nested repeat must use a different `as` (and `i0` clashes with an outer `i`). Outside a repeat, `${...}` in these fields is an error.
- A test has at most 1000 expanded steps, counting `beforeEach`, fixtures and its own steps.

`fukurou validate` prints the expanded steps under each test, one per line: the index, the phase (or `fixture <name>`), the target, the action, the label, and `[parallel <block> lane <lane>]` / `[repeat <block> <iteration>/<of>]` for steps inside blocks.

### Compatibility

Adding fields is backwards compatible and the viewer ignores unknown fields. Removing or changing the meaning of a field bumps `schemaVersion`. Version 2 moved `steps`, `screenshots` and `scenario` from the root into `tests[]`, moved logs into `sessions[]` and removed `players[].op`, so it is a new `schemaVersion`. The runner writes only version 2. The viewer reads only version 2: runs with any other `schemaVersion` are shown as "unsupported" instead of breaking the page. `schema/result.v1.json` stays in the repository for users of the `v1` tag.

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
