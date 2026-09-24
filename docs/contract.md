# Results contract

This is the only interface between the test runner (`fukurou`) and the viewer (`fukurou/ui`). Both live in this repository and are released together under one tag.

## 1. Per-version output directory (one artifact per Minecraft version)

`fukurou run` writes everything under `--out-dir`. The action uploads that directory as the artifact `<artifact-prefix>-<server>-<minecraft version>` (for example `fukurou-paper-1.21.11`). It is uploaded even when the run fails.

```
result.json                          # always written, also on failure (status "error" for infrastructure failures)
screenshots/<player>/<name>.png
logs/harness.log                     # fukurou's own output
logs/server.log                      # the server's logs/latest.log
logs/clients/<player>.log            # each client's logs/latest.log
crash-reports/<player>/*.txt         # only when a client crashed
```

Never included: server jars, client jars, assets, Java runtimes, worlds.

## 2. `result.json` (schemaVersion 1)

Defined by the pydantic model `fukurou.result.model.ResultV1`. The generated JSON Schema lives at `schema/result.v1.json` (`fukurou schema result`). Keys are camelCase.

```json
{
  "schemaVersion": 1,
  "id": "paper-1.21.11",
  "status": "passed",
  "fukurou": { "version": "0.1.0", "portablemc": "5.0.4" },
  "minecraft": { "version": "1.21.11", "server": "paper", "build": 130, "channel": "STABLE" },
  "java": { "server": 25 },
  "scenario": { "name": "stamp-thinking-face", "source": "file:game-test/scenarios/stamp-thinking-face.json", "sha256": "..." },
  "plugins": [
    { "file": "MineStamp-all.jar", "sha256": "...", "name": "MineStamp", "version": "1.0", "role": "under-test", "classFileMajor": 69, "enabled": true },
    { "file": "ProtocolLib.jar", "sha256": "...", "name": "ProtocolLib", "role": "dependency", "source": "github:dmulloy2/ProtocolLib@dev-build/ProtocolLib.jar", "enabled": true }
  ],
  "players": [ { "name": "Alice", "op": true, "joined": true } ],
  "steps": [
    { "index": 0, "on": "server", "action": "command", "label": "time set noon", "status": "passed", "durationMs": 12 },
    { "index": 5, "on": "Alice", "action": "screenshot", "label": "stamp-thinking-face", "status": "passed", "durationMs": 800, "screenshot": "screenshots/Alice/stamp-thinking-face.png" }
  ],
  "failure": null,
  "screenshots": [ { "player": "Alice", "name": "stamp-thinking-face", "path": "screenshots/Alice/stamp-thinking-face.png", "width": 1280, "height": 720, "stepIndex": 5 } ],
  "logs": [ { "kind": "server", "path": "logs/server.log" }, { "kind": "client", "player": "Alice", "path": "logs/clients/Alice.log" } ],
  "startedAt": "2026-09-24T03:00:00Z",
  "finishedAt": "2026-09-24T03:05:12Z",
  "durationMs": 312000,
  "ci": { "repository": "morinoparty/MineStamp", "sha": "...", "ref": "refs/pull/177/merge", "runId": "123", "runAttempt": "1", "serverUrl": "https://github.com" }
}
```

- `status`: `passed` (every step passed), `failed` (a scenario step failed), `error` (setup, download, server start or client join failed).
- `failure`: `{ "phase": "setup" | "server-start" | "client-join" | "scenario" | "teardown", "message": "...", "stepIndex": 12 | null }`, or `null` when passed.
- Steps that did not run after a failure are recorded with status `skipped`.
- A failure screenshot, when one could be taken, is listed in `screenshots` with the name `failure`.

Compatibility: adding fields is backwards compatible and the viewer ignores unknown fields. Removing or changing the meaning of a field bumps `schemaVersion`. The viewer shows runs with an unsupported `schemaVersion` as "unsupported" instead of breaking the page.

## 3. Site (written by `fukurou/ui`)

`ui/scripts/build_manifest.py --artifacts-dir <dir> --out <site>` collects `<dir>/<artifact>/result.json` and writes:

```
index.html, assets/*                 # prebuilt viewer copied from ui/dist (relative base, hash routing)
manifest.json                        # schema below
manifest.js                          # window.__FUKUROU_MANIFEST__ = {...}; loaded as a classic script so file:// works
runs/<id>/result.json
runs/<id>/screenshots/<player>/<name>.png
runs/<id>/logs/...                   # only when include-logs is true
runs/<id>/crash-reports/...          # only when include-logs is true
```

### `manifest.json` (schemaVersion 1)

```json
{
  "schemaVersion": 1,
  "generator": { "name": "fukurou-ui", "version": "0.1.0" },
  "generatedAt": "2026-09-24T03:10:00Z",
  "title": "MineStamp abc1234",
  "ci": { "repository": "morinoparty/MineStamp", "sha": "...", "runId": "123", "runUrl": "https://github.com/morinoparty/MineStamp/actions/runs/123" },
  "summary": { "total": 7, "passed": 6, "failed": 1, "error": 0 },
  "players": ["Alice", "Bob"],
  "shots": ["stamp-thinking-face"],
  "runs": [
    {
      "id": "paper-1.21.11",
      "artifact": "fukurou-paper-1.21.11",
      "base": "runs/paper-1.21.11/",
      "status": "passed",
      "result": { "...": "the whole result.json, embedded" }
    }
  ],
  "warnings": ["fukurou-paper-1.21.7: result.json missing (job cancelled?)"]
}
```

- `runs` are sorted by Minecraft release order (oldest first). Version strings are compared numerically part by part (`1.21.10` > `1.21.9`, `26.1` > `1.21.11`).
- An artifact without `result.json` becomes a run with `status: "error"`, `result: null`, and a warning.
- `players` and `shots` are the union across runs, excluding the `failure` screenshot from `shots`.
- Paths inside `result` stay relative to the artifact root. The viewer resolves them against the run's `base`.
