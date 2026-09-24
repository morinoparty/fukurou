# Results contract

This is the only interface between the test runner (`fukurou`) and the viewer (`fukurou/ui`). Both live in this repository and are released together under one tag.

fukurou v2 runs every selected test for one Minecraft version in one job, one process and one server session. Paper starts once, the union of all tests' players joins once, and the tests run in order. Between tests the harness resets the world and the clients. A test with `isolation: fresh-server` gets its own server session.

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

`result.json` is written three times: a stub right after the tests are discovered (every test `skipped` with `skipReason: "not run"`), again after every test, and a final version at teardown (also when interrupted). A job that is killed by `timeout-minutes` or loses its runner therefore still has the results of the tests that finished.

## 2. `result.json` (schemaVersion 2)

Defined by the pydantic model `fukurou.result.model.ResultV2`. The generated JSON Schema lives at `schema/result.v2.json` (`fukurou schema result`). Keys are camelCase.

```json
{
  "schemaVersion": 2,
  "id": "paper-1.21.11",
  "status": "failed",
  "fukurou": { "version": "2.0.0", "portablemc": "5.0.4" },
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

- `id` is `<server>-<minecraft version>`, the same as the end of the artifact name. All tests of a version share one artifact, so ids never collide.
- `status`:
  - `error` when `failure` is set. `failure` is `{ "phase": "setup" | "server-start" | "client-join" | "server" | "teardown" | "interrupted", "message": "..." }`: the infrastructure kept tests from running, and the remaining tests are `skipped`. A server that dies during a session is not restarted (`phase: "server"`). `phase: "server"` also covers an unexpected exception inside the harness while tests were running; the `message` then describes that exception (a server death says `server died during <id>: ...`).
  - otherwise `failed` when any test is `failed` or `error`.
  - otherwise `passed` (`skipped` tests count as success).
- `summary` counts `tests[].status`.
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

### Compatibility

Adding fields is backwards compatible and the viewer ignores unknown fields. Removing or changing the meaning of a field bumps `schemaVersion`. Version 2 moved `steps`, `screenshots` and `scenario` from the root into `tests[]`, moved logs into `sessions[]` and removed `players[].op`, so it is a new `schemaVersion`. The runner writes only version 2. The viewer reads only version 2: runs with any other `schemaVersion` are shown as "unsupported" instead of breaking the page. `schema/result.v1.json` stays in the repository for users of the `v1` tag.

## 3. Site (written by `fukurou/ui`)

`ui/scripts/build_manifest.py --artifacts-dir <dir> --out <site>` collects `<dir>/<artifact>/result.json` and writes:

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
      "status": "failed",
      "result": { "...": "the whole result.json, embedded" }
    }
  ],
  "warnings": ["fukurou-paper-1.21.7: result.json missing (job cancelled?)"]
}
```

- `runs` are sorted by Minecraft release order (oldest first). Version strings are compared numerically part by part (`1.21.10` > `1.21.9`, `26.1` > `1.21.11`). A run's `status` is its `result.status`.
- An artifact without `result.json` becomes a run with `status: "error"`, `result: null`, and a warning.
- A run without a supported `result` (no `result.json`, or a `schemaVersion` other than 2) also gets `logs`, in the same shape as `result.logs`, when `logs/harness.log` was copied into the site: `[{ "kind": "harness", "path": "logs/harness.log", "player": null }]`. This field is optional. It is missing for runs with a supported result (they use `result.logs` / `result.sessions[].logs`) and for sites built with `--no-include-logs`.
- A `result.json` whose `schemaVersion` is not 2 is embedded as is, but the run gets `status: "error"`, does not appear in any `cells`, and adds the warning `<artifact>: unsupported result schemaVersion 1`. There is no reader for version 1.
- `tests` is the union of every supported run's `tests[]`, in the execution order of the run where each test first appears (runs taken oldest first). `cells` maps a run id to that test's status in the run. A run missing from `cells` is shown as "not run" (for example when a filter selected the test in only some versions). It is not counted as an error.
- `tests[].status` is the strongest status across its cells, in the order `error` > `failed` > `passed` > `skipped`, so failures can be sorted first. `tests[].players` and `tests[].shots` are the unions for that test across runs; `shots` excludes `failure`. `tests[].tags` is the union of the test's tags.
- `summary.runs` counts runs by status. `summary.tests` counts the cells (test × version), without "not run".
- `players` is the union of `result.players` across runs.
- Paths inside `result` stay relative to the artifact root. The viewer resolves them against the run's `base`. Before embedding, `build_manifest.py` drops paths that are absolute, contain `..` or use backslashes from `tests[].screenshots`, `tests[].steps[].screenshot`, `tests[].logRanges` keys, `sessions[].logs` and `logs`, with a warning. It also drops tests whose `id` is not a valid test id or repeats an earlier id in the same run.

### Action outputs

`build_manifest.py` writes these to `$GITHUB_OUTPUT` and a Markdown summary (runs table and test × version table) to `$GITHUB_STEP_SUMMARY`:

| output | value |
| --- | --- |
| `status` | `passed` when every run passed, `failed` otherwise, `empty` when there were no runs |
| `summary` | `summary.runs` as compact JSON |
| `tests-summary` | `summary.tests` as compact JSON: `{"total","passed","failed","error","skipped"}` |
| `failed-tests` | the `failed` and `error` cells as `<test id>@<minecraft version>`, comma separated |
