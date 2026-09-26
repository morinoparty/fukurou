# Usage

fukurou has four GitHub Actions. They live in one repository and are released together, so use the same tag (for example `@v2`) for all of them.

- [`morinoparty/fukurou/versions`](#morinoparty-fukurou-versions): resolve a version spec to a matrix.
- [`morinoparty/fukurou`](#morinoparty-fukurou): run a suite of tests against one version, in one server session.
- [`morinoparty/fukurou/ui`](#morinoparty-fukurou-ui): build and publish the viewer site.
- [`morinoparty/fukurou/setup`](../setup/action.yml): install the system packages and restore the cache for the Kotlin / JUnit library. See [Kotlin / JUnit (JVM)](../README.md#kotlin--junit-jvm) in the README.

See [contract.md](contract.md) for the exact shape of `result.json` and `manifest.json`.

## `morinoparty/fukurou/versions`

Resolves a version spec against Mojang's version manifest and the Paper API, and outputs a JSON array for `strategy.matrix`. The list is also written to the job summary.

```yaml
- uses: morinoparty/fukurou/versions@v2
  id: versions
  with:
    minecraft-version: 1.21.6-
```

### Inputs

| Input | Default | Description |
| --- | --- | --- |
| `minecraft-version` | `latest` | Version spec. See [Version specs](#version-specs). |
| `max-versions` | `16` | Fail when the spec resolves to more versions than this. |
| `paper-channel` | `stable` | Least stable Paper build channel to accept: `stable` (only `STABLE` builds), `beta` (`BETA` or `STABLE`) or `alpha` (any build). See [Paper channels](#paper-channels). |

### Outputs

| Output | Description |
| --- | --- |
| `matrix` | JSON array of versions, oldest first, for example `["1.21.10","1.21.11"]`. Use it with `fromJSON()`. |

### Version specs

| Spec | Resolves to |
| --- | --- |
| `latest` | The newest release that has a Paper build in the accepted channels. |
| `1.21.11` | Exactly that version. It is an error when Paper has no build of it in the accepted channels. |
| `1.21.6-` | Every release from 1.21.6 onward that has a Paper build in the accepted channels. |
| `1.20.5-1.21.11` | Every release in that range (both ends included) that has a Paper build in the accepted channels. |

The accepted channels are set by `paper-channel` (only `STABLE` by default; see [Paper channels](#paper-channels)). Versions are ordered by Mojang's release order. A spec that names a version older than 1.20 (a single version or the lower bound of a range) is an error, because fukurou supports Minecraft 1.20 or later. The same command is available locally as `fukurou versions <spec> [--max-versions N] [--paper-channel CHANNEL]`. A test's own `versions:` field uses the same syntax minus `latest` (see [Test fields](#test-fields)).

### Paper channels

Every Paper build has a channel: `ALPHA` right after a Minecraft release, then `BETA`, then `STABLE`. `paper-channel` (`--paper-channel`) names the least stable channel you accept, and defaults to `stable`:

| Value | Accepted builds |
| --- | --- |
| `stable` | `STABLE` |
| `beta` | `BETA`, `STABLE` |
| `alpha` | `ALPHA`, `BETA`, `STABLE` |

A release counts for `latest`, ranges and single versions when it has at least one Paper build in an accepted channel, even when its newest build is less stable (the run then uses the newest accepted build). With `alpha`, a release that Paper has only as `ALPHA` builds is picked too. Give the `versions` action and the run action the same value, so that the run finds a build for every version in the matrix:

```yaml
- uses: morinoparty/fukurou/versions@v2
  id: versions
  with:
    minecraft-version: 1.21.6-
    paper-channel: alpha
# ... and in the matrix job:
- uses: morinoparty/fukurou@v2
  with:
    minecraft-version: ${{ matrix.minecraft-version }}
    paper-channel: alpha
    accept-eula: "true"
```

The run uses the newest build of the version in the accepted channels and records its channel in `result.json` (`minecraft.channel`); the viewer marks `ALPHA` and `BETA` runs with a small badge. An explicit `server-build` is used whatever its channel, with a warning when it is less stable than `paper-channel`.

## `morinoparty/fukurou`

Selects a set of tests (see [Selecting tests](#selecting-tests)), runs them against one Minecraft version in one server session on the runner, then uploads `out-dir` as an artifact. The step fails when the run did not pass, after the artifact is uploaded and the outputs are set.

```yaml
- uses: morinoparty/fukurou@v2
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    suite: game-test/fukurou.yml
    plugins-dir: build/libs
    plugins: "*-all.jar"
```

What the action does:

1. Checks the inputs and computes `work-dir` / `out-dir` / `artifact-name`.
2. Installs uv, then runs `fukurou list` with the same selection inputs to catch a bad suite, an empty selection or a typo'd filter **before** installing anything else — the step fails here (exit 2) without touching `apt-get` or downloading the server.
3. Installs `xvfb`, `xdotool` and the OpenGL/OpenAL libraries with `apt-get` (unless `skip-system-deps` is `true`).
4. Picks the server's Java version (`java-version: auto` runs `fukurou java`), then installs it with `actions/setup-java` (Temurin).
5. Restores the PortableMC and Mojang asset cache under `work-dir` with `actions/cache`.
6. Runs `fukurou run` with Mesa software rendering (`LIBGL_ALWAYS_SOFTWARE=true`): one server session, every selected test's players joined once, tests run in order with a harness reset (or a fresh server, for `isolation: fresh-server` tests) between them.
7. Uploads `out-dir` with `actions/upload-artifact` (even when the run failed), sets the outputs, and appends a per-test table to the job summary.

`actions/setup-java` changes `JAVA_HOME` and `PATH` for the rest of the job, so later steps in the same job use the server's JDK. If you build or test after the fukurou step, run `actions/setup-java` again with the version you need, or run fukurou in its own job.

### Selecting tests

At least one of `suite`, `scenarios`, `scenario-file` and `scenario` must be set; they combine freely (a suite plus extra ad hoc scenario files, for example). `tests` and `tags` then filter that selection (both given: a test must match both). An empty selection is an error (exit 2), so a typo'd filter fails loudly instead of silently running zero tests.

### Inputs

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `accept-eula` | yes | | Must be `true`. fukurou downloads and runs the Minecraft server and clients, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). |
| `minecraft-version` | yes | | One Minecraft version, such as `1.21.11`. Use the `versions` action for ranges. |
| `suite` | | | Path to a suite file (`fukurou.yml`), relative to the workspace. Its `scenarios` globs, players and fixtures are shared by every test. |
| `scenarios` | | | Glob patterns of scenario files to run, relative to the workspace, separated by newlines or commas. |
| `scenario` | | | Inline scenario (JSON or YAML), run as the test `inline`. |
| `scenario-file` | | | Path to a scenario file (JSON or YAML), relative to the workspace. Multiple lines run one test each. |
| `tests` | | | Only run tests whose id matches one of these ids or glob patterns (`fnmatch`), separated by newlines or commas. Empty runs every selected test. |
| `tags` | | | Only run tests that have one of these tags, separated by newlines or commas. |
| `isolation` | | | Force `reset` or `fresh-server` for every test, overriding what each test and the suite declare. Empty keeps each test's own isolation. |
| `fail-fast` | | `false` | Stop the run at the first `failed` or `error` test; the rest are recorded as `skipped`. |
| `plugins-dir` | | `${{ github.workspace }}` | Directory that contains the plugin jars under test. |
| `plugins` | | `*.jar` | Glob patterns inside `plugins-dir`, separated by newlines or commas. An empty string installs no plugins. |
| `dependencies` | | | YAML list of extra plugins to download. See [Dependencies](#dependencies). |
| `server-properties` | | | Extra `server.properties` lines, one `key=value` per line. They override fukurou's defaults (a flat world, peaceful difficulty, offline mode, whitelist off) and a `server.properties` inside `server-files`. `server-ip`, `server-port`, `enable-rcon`, `rcon.port`, `rcon.password` and `max-players` are managed by fukurou and are ignored with a warning. |
| `server-files` | | | Directory whose contents are copied into the server directory before it starts (for example `plugins/MyPlugin/config.yml`). |
| `server-build` | | newest accepted | Paper build number. Used even when its channel is less stable than `paper-channel` (with a warning). |
| `paper-channel` | | `stable` | Least stable Paper build channel to accept: `stable`, `beta` or `alpha`. The run uses the newest build in the accepted channels and fails when the version has none. See [Paper channels](#paper-channels). |
| `java-version` | | `auto` | Java major version for the server. `auto` picks the newer of the version Mojang requires and the version the plugin jars are compiled for. The clients always use Mojang's Java runtime. |
| `work-dir` | | `$RUNNER_TEMP/fukurou-work` | Working directory for the server, the clients and the caches. |
| `out-dir` | | `$RUNNER_TEMP/fukurou-out` | Output directory for `result.json`, screenshots and logs. |
| `upload-artifact` | | `true` | Upload `out-dir` as an artifact. |
| `artifact-name` | | `fukurou-paper-<minecraft-version>` | Name of the artifact. If you use the `ui` action, keep the `fukurou-paper-` prefix or set its `artifact-pattern` to match your names. |
| `retention-days` | | `14` | Retention days of the artifact. |
| `github-token` | | `${{ github.token }}` | Token for the GitHub API, used to download `github:` dependencies. It avoids the rate limit for unauthenticated requests and lets you download assets from private repositories the token can read. |
| `skip-system-deps` | | `false` | Skip installing the system packages (for self-hosted runners that already have them). |

Every input is passed to the scripts through environment variables, so multi-line values (`scenario`, `scenarios`, `scenario-file`, `tests`, `tags`, `plugins`, `dependencies`, `server-properties`) are passed as they are, and never interpolated directly into a shell command.

### Outputs

| Output | Description |
| --- | --- |
| `result` | `passed`, `failed` or `error`, read from `result.json`'s run status. `error` when there is no `result.json`. |
| `tests-summary` | Test counts as JSON, read from `result.json`'s `summary`, for example `{"total":3,"passed":2,"failed":1,"error":0,"skipped":0}`. An empty object when `result.json` is missing. |
| `failed-tests` | Comma-separated ids of the tests whose status is `failed` or `error`. |
| `result-file` | Path to `result.json`. |
| `artifact-name` | Name of the uploaded artifact. |
| `out-dir` | Output directory. |

### Dependencies

`dependencies` is a YAML list. Each entry downloads one plugin jar into the server's `plugins` directory:

```yaml
- uses: morinoparty/fukurou@v2
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    suite: game-test/fukurou.yml
    plugins-dir: build/libs
    dependencies: |
      - github: dmulloy2/ProtocolLib
        tag: dev-build
        asset: ProtocolLib.jar
      - url: https://example.com/SomePlugin-1.0.jar
        sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

| Form | Fields | Description |
| --- | --- | --- |
| URL | `url`, `sha256`? | Download a jar from a URL. When `sha256` is set, the download is checked against it. |
| GitHub release | `github`, `tag`, `asset` | Download the asset `asset` of the release `tag` of the repository `github` (`owner/repo`). The request uses the `github-token` input. |

### Output directory

```
result.json                                    # schemaVersion 2; written right after discovery, after every test, and at teardown
tests/<test id>/screenshots/<player>/<name>.png
tests/<test id>/screenshots/<player>/failure.png
logs/harness.log
logs/sessions/<n>/server.log                   # session n's server console record
logs/sessions/<n>/clients/<player>.log         # the client's first launch in session n
logs/sessions/<n>/clients/<player>.<k>.log     # after a relaunch (k >= 2)
crash-reports/<player>/*.txt                   # only when a client crashed
```

See [contract.md](contract.md) for the format of `result.json`.

## Suite file

A suite file (conventionally `fukurou.yml`) is what a group of tests shares. It is optional — `scenario-file` / `scenario` alone run without one, using fukurou's defaults below. Globs and paths inside it are relative to the suite file's own directory.

```yaml
$schema: https://raw.githubusercontent.com/morinoparty/fukurou/v2/schema/suite.v1.json
scenarios:                 # ordered; each glob is sorted, and a file matched twice is used once
  - scenarios/*.json
players:                   # default for tests that omit "players"; the union of every test's players joins once
  - { name: Alice, op: true }
  - { name: Bob }
isolation: reset           # default for tests that omit "isolation"
arena: { size: 32, height: 24 }   # the region the reset fills with air; x,z centered on the origin, y from the ground up. false disables it
gamemode: survival         # gamemode the reset sets participating players to
spawn:                     # tp destination on reset (players not listed get a default slot: (0.5 + 2i, -60, -8.5))
  Alice: "0.5 -60 -8.5 0 0"
settle: 2                  # seconds to wait after the reset for clients to catch up on block updates
fixtures:                  # named step sequences a test opts into with "use"
  arena:
    - { on: server, action: command, command: "fill -16 -60 -16 15 -50 15 minecraft:stone" }
    - { action: wait, seconds: 2 }
beforeEach: []              # steps that run before every test, ahead of its "use" fixtures
```

| Field | Default | Description |
| --- | --- | --- |
| `scenarios` | `[]` | Ordered glob patterns of scenario files, relative to the suite file. |
| `players` | `[]` | Default players for a test that does not declare its own. |
| `isolation` | `reset` | Default isolation for a test that does not declare its own. |
| `arena` | `{ size: 32, height: 24 }` | Region the reset fills with air (and re-lays a 4-block-thick ground under), centered on the world origin. `size * size * height` must be at most 32768 (the vanilla single-`fill` block limit). `false` disables the arena reset — use this if you bring your own world with `server-files`. |
| `gamemode` | `survival` | Gamemode the reset sets every participating player to. |
| `spawn` | `{}` | Map of player name to `"x y z"` or `"x y z yaw pitch"`, the reset's teleport destination. A player not listed gets slot `i` at `(0.5 + 2*i, -60, -8.5)`, where `i` is that player's position in the joined players list — stable across tests, so the same player always lands in the same place. |
| `settle` | `2` | Seconds to wait after the reset, so clients receive the resulting block updates before the test's own steps start. |
| `fixtures` | `{}` | Named, non-empty step sequences. A test lists the names it wants in `use:`, in order; they expand ahead of the test's own `steps`, after `beforeEach`. A fixture step aimed at a player the test does not declare is skipped for that test (`"player <name> is not in this test"`), so one fixture can serve tests with different player sets. Screenshot names must still be unique per player across `beforeEach` + the fixtures used + the test's own steps. |
| `beforeEach` | `[]` | Steps that run before every test, ahead of any `use` fixtures. |

## Test fields

Every test is one scenario file (its id is the file name without the extension) or the inline `scenario` input (id `inline`). Besides `players` and `steps` (see the [scenario format](../README.md#scenario-format) in the README), a test can set:

| Field | Default | Description |
| --- | --- | --- |
| `name` | the test id | Display name in the viewer. |
| `tags` | `[]` | Strings to filter on with `--tag` / the `tags` input. |
| `isolation` | the suite's `isolation` (default `reset`) | `reset`: run in the shared server session after a harness reset. `fresh-server`: delete the world and restart the server and clients before running (`server/` only — installed clients are kept and just relaunched). |
| `timeout` | `600` | Seconds checked between steps; exceeding it errors the test out (phase `timeout`). |
| `versions` | `null` | Same syntax as `fukurou versions`, minus `latest` (for example `1.21.9-`, `1.21.6-1.21.11`, `1.21.11`). Evaluated against the release list fukurou already fetched at startup, so it costs no extra request. A version not included skips the test with `skipReason: "versions: <spec> does not include <version>"`. |
| `use` | `[]` | Names of the suite's `fixtures` to expand before this test's own steps, in this order. |
| `players` | the suite's `players` | Fully replaces the suite's players for this test when set (a subset is fine). A test with no players anywhere — no suite, and none of its own — is an error. |

## Parallel and repeat steps

Added in fukurou 2.1 (`result.json` stays `schemaVersion: 2`; see [What's new in 2.1](#whats-new-in-21)). A step can be a block instead of a single action, and any player action's `on` can name several players. Both are expanded into a flat list of steps when the test is planned, before anything runs — `fukurou validate` prints the result (see [below](#validate-output)), and `result.json` records only the expanded steps.

### `on` as a list of players

```json
{ "on": ["Alice", "Bob"], "action": "screenshot", "name": "greeting" }
```

`on` may be a non-empty list of unique player names instead of a single name. It is shorthand for a `parallel` block with one copy of the step per player — a one-name list is exactly the same as the plain string. The same `name` is fine for every player, because screenshots are stored per player (`tests/<id>/screenshots/<player>/<name>.png`). In a `beforeEach` step or a fixture, a player not declared by the test skips only that player's copy, the same as any other fixture step aimed at a player the test does not declare.

### `parallel`

```json
{
  "action": "parallel",
  "steps": [
    { "on": "server", "action": "command", "command": "weather clear" },
    { "on": "Alice", "action": "screenshot", "name": "midday" }
  ]
}
```

`parallel` has no `on`. Its `steps` (1 to 16 after expansion — an `on` list counts one lane per player) run at the same time, each in its own lane, and the block finishes once every lane has finished. If any child fails, the block fails and the steps after it are `skipped` as usual, but lanes that were already running are left to finish and keep their own result. A child may be any action or a `repeat` block. `parallel` cannot contain another `parallel`: an `on` list written directly as a `parallel` child is not nesting — it just adds one lane per player, as above — but a nested `parallel`, or a multi-player `on` list inside a `repeat` child (it is a `parallel` block too), is a validation error, since nested lanes would make the client-input and thread rules ambiguous. Two children must not send client input to the same player at once: `fukurou validate` rejects a `parallel` where two children both use `press_key`, `type_text`, `chat` or `screenshot` on the same player (this also looks inside a `repeat` child). Server actions, `wait`, `wait_for_log` and `assert_no_log` have no such limit and may appear in several children.

### `repeat`

```json
{ "action": "repeat", "times": 3, "as": "i", "steps": [
  { "on": "Alice", "action": "screenshot", "name": "shot-${i}" }
] }
```

`repeat` has no `on`. `times` is 1 to 100, and `as` (default `i`) names the loop variable and must match `^[a-z][a-z0-9_]*$`. It is unrolled — run `times` times, one after another — while the test is planned, not while it runs. In each iteration, `${<as>}` (1-based) and `${<as>0}` (0-based) are replaced in the `text`, `command`, `pattern`, `name` and `key` of every step inside (`on` is never substituted). A placeholder for a name no enclosing `repeat` defines is a validation error, and so is any `${...}` in these fields outside a `repeat` at all — there is no way to escape it, so a literal `${` cannot appear in these fields outside a `repeat`. A `repeat` may nest inside a `repeat` or a `parallel`, and a `parallel` may nest inside a `repeat`; a nested `repeat`'s `as` must differ from every enclosing one, in both its 1-based and 0-based form (an inner `as: i0` inside an outer `as: i` repeat is the same clash as reusing `i` itself).

`parallel` and `repeat` blocks may also appear in a suite's `beforeEach` and in `fixtures`, with the usual per-player skip rule applying to each expanded copy.

After expansion, screenshot names must still be unique per player within the test (`failure` stays reserved), and a test may have at most 1000 planned steps in total, counting `beforeEach`, every fixture pulled in with `use`, and the test's own `steps`.

### Validate output

`fukurou validate` prints one indented line per expanded step under each test's summary, with `[parallel <block> lane <lane>]` and/or `[repeat <block> <iteration>/<of>]` markers. For [`examples/parallel-repeat.json`](../examples/parallel-repeat.json):

```
$ fukurou validate --scenario-file examples/parallel-repeat.json
parallel-repeat: valid (players: 1, steps: 8, planned steps: 8, isolation: reset)
    0 test server command 'time set noon'
    1 test - wait '3s'
    2 test server command 'weather clear' [parallel 0 lane 0]
    3 test Alice screenshot 'midday' [parallel 0 lane 1]
    4 test Alice press_key 'F5' [repeat 0 1/2]
    5 test Alice screenshot 'shot-1' [repeat 0 1/2]
    6 test Alice press_key 'F5' [repeat 0 2/2]
    7 test Alice screenshot 'shot-2' [repeat 0 2/2]
```

### Result fields

Each expanded step in `result.json`'s `tests[].steps` gets optional `parallel` (`{ "block", "lane" }` or `null`) and `repeat` (a list of `{ "block", "iteration", "of" }`, outermost first, or `null`) fields, plus `startedAt` / `finishedAt` timestamps — steps in the same `parallel` block overlap in time, which is how the viewer draws them as concurrent. See [Parallel and repeat blocks (fukurou 2.1)](contract.md#parallel-and-repeat-blocks-fukurou-21) in contract.md for the exact shape, block numbering and how a timeout or a failed lane is recorded.

## `morinoparty/fukurou/ui`

Downloads the run artifacts of the workflow run, builds one static viewer site from them, uploads the site as a workflow artifact, and optionally publishes it to S3-compatible storage (for example Cloudflare R2). Run it in a job that `needs` the test jobs with `if: always()`, so failed runs are shown too. The site also works when opened from disk (`file://`).

```yaml
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

### Inputs

| Input | Default | Description |
| --- | --- | --- |
| `artifact-pattern` | `fukurou-paper-*` | Glob pattern of the run artifacts to download. |
| `artifacts-dir` | | Use this local directory (one subdirectory per artifact) instead of downloading artifacts. |
| `title` | repository name | Title shown in the viewer. |
| `include-logs` | `true` | Include the server and client logs and crash reports in the site. |
| `output-dir` | `$RUNNER_TEMP/fukurou-site` | Directory to write the site to. |
| `site-artifact-name` | `fukurou-site` | Name of the workflow artifact that holds the site. |
| `retention-days` | `7` | Retention days of the site artifact. |
| `upload` | `auto` | Publish to S3-compatible storage: `auto` (only when `aws-access-key-id` is set, so pull requests from forks still work), `true` or `false`. |
| `s3-endpoint` | | S3-compatible endpoint URL (for R2, `https://<account id>.r2.cloudflarestorage.com`). Empty for AWS S3. |
| `s3-bucket` | | Bucket to publish to. Required when uploading. |
| `s3-prefix` | `<owner>/<repo>/<run id>-<run attempt>` | Key prefix inside the bucket. Screenshots are uploaded with an immutable cache policy, so use a prefix unique to the run. |
| `s3-region` | `auto` | Region of the bucket (`auto` works for R2). |
| `aws-access-key-id` | | Access key ID for the bucket. Required when uploading. |
| `aws-secret-access-key` | | Secret access key for the bucket. Required when uploading. |
| `public-base-url` | | Public URL that serves the bucket root. Used to build the `url` output. |

### Outputs

| Output | Description |
| --- | --- |
| `url` | Public URL of the published `index.html`. Empty when the site was not uploaded or `public-base-url` is empty. |
| `uploaded` | `true` when the site was published to S3-compatible storage. |
| `status` | `passed` when every run passed, `failed` when any run failed or errored, `empty` when no runs were found. |
| `summary` | Run counts as JSON, for example `{"total":7,"passed":6,"failed":1,"error":0}`. |
| `tests-summary` | Test counts across every run (test x version) as JSON, for example `{"total":18,"passed":14,"failed":2,"error":0,"skipped":2}`. A "not run" cell (a test absent from a run, for example because a filter excluded it there) is not counted. |
| `failed-tests` | `<test id>@<minecraft version>` (or `<test id>@<minecraft version>/<label>` for a labelled fukurou-kotlin run) of every cell whose status is `failed` or `error`, comma separated. |
| `site-dir` | Local directory that holds the site. |

## Command line

The actions call the `fukurou` command. You can run it locally with `uvx --from git+https://github.com/morinoparty/fukurou fukurou ...`.

| Command | Description |
| --- | --- |
| `fukurou versions <spec> [--max-versions N] [--paper-channel CHANNEL]` | Print the versions a spec resolves to, as a JSON array. |
| `fukurou list [selection] [--minecraft-version X]` | Print the selected test ids in run order, as a JSON array. With `--minecraft-version`, tests `versions:` would skip on it are reported (on stderr) too. |
| `fukurou validate [selection]` | Check the suite and every selected test without starting anything: one valid line per test on stdout, problems on stderr. Exit 2 on any problem, or when the selection is empty. |
| `fukurou schema {scenario\|suite\|result}` | Print a JSON Schema (`scenario` is schemaVersion 2, `suite` is v1, `result` is schemaVersion 2). |
| `fukurou java --minecraft-version X [--plugins-dir DIR] [--plugins GLOBS]` | Print the Java major version to use for the server. |
| `fukurou run --minecraft-version X [selection] --accept-eula [options]` | Run the selected tests in one server session. Exit 0 when every selected test passed or was skipped, 1 when any failed, errored, or the run could not proceed, 2 for invalid input. |

`[selection]` (shared by `list`, `validate` and `run`; at least one of the first four is required):

| Option | Description |
| --- | --- |
| `--suite PATH` | Suite file (`fukurou.yml`); its `scenarios` globs are relative to it. |
| `--scenarios GLOB` | Glob of scenario files relative to the cwd (repeatable; commas and newlines also separate). |
| `--scenario-file PATH` | Scenario file, JSON or YAML (repeatable; newlines also separate). |
| `--scenario TEXT` | Inline scenario text, JSON or YAML (test id `inline`). |
| `--test ID_OR_GLOB` | Run only tests whose id matches (repeatable; commas and newlines also separate; combined with `--tag`, a test must match both). |
| `--tag TAG` | Run only tests with one of these tags (repeatable; commas and newlines also separate). |
| `--isolation {reset,fresh-server}` | Force this isolation for every test. |

`fukurou run` also takes:

| Option | Default | Description |
| --- | --- | --- |
| `--fail-fast` | off | Stop at the first `failed`/`error` test; the rest are recorded `skipped`. |
| `--plugins-dir DIR` | current directory | Directory that contains the plugin jars. |
| `--plugins GLOBS` | `*.jar` | Glob patterns, separated by newlines or commas. An empty string installs no plugins. |
| `--dependencies YAML` | | Extra plugins to download. See [Dependencies](#dependencies). |
| `--server-properties TEXT` | | Extra `server.properties` lines. |
| `--server-files DIR` | | Files to copy into the server directory. |
| `--server-build N` | newest accepted | Paper build number, used whatever its channel (with a warning when it is less stable than `--paper-channel`). |
| `--paper-channel {stable,beta,alpha}` | `stable` | Least stable Paper build channel to accept. The run uses the newest build in the accepted channels and exits 2 when the version has none. |
| `--java PATH` | `$JAVA_HOME/bin/java`, or `java` on `PATH` | Java for the server. |
| `--client-java PATH` | Mojang's runtime | Java for the clients. |
| `--work-dir DIR` | `./.fukurou-work` | Working directory. |
| `--out-dir DIR` | `./fukurou-out` | Output directory. |

## Estimating `timeout-minutes`

One server session costs a fixed startup (cold: about 2.5–3 minutes for the server plus each player's Quick Play join; warm caches: about 1 minute), then per test roughly 1 minute with `isolation: reset` (dominated by `settle` and the test's own waits) or an extra 1–4 minutes for `isolation: fresh-server` (a full server + client restart). A starting point:

```
timeout-minutes: 10 + (number of "reset" tests) + 4 * (number of "fresh-server" tests)
```

Tune it down once you have seen a few real runs — the job summary's per-test durations are the easiest way to do that.

## What's new in 2.1

Additive, with one exception (below): `result.json` stays `schemaVersion: 2`, and nothing from 2.0 changed meaning otherwise.

- **[Parallel and repeat steps](#parallel-and-repeat-steps):** a `parallel` block runs its children at once, a `repeat` block unrolls its steps N times with `${i}` placeholders, and a player action's `on` can name several players as shorthand for a `parallel`.
- `fukurou validate` prints the expanded steps of every test (see [Validate output](#validate-output) above); `fukurou list` is unchanged.
- `result.json` steps gained optional `parallel`, `repeat`, `startedAt` and `finishedAt` fields — see [Result fields](#result-fields) above and [contract.md](contract.md#parallel-and-repeat-blocks-fukurou-21).
- **One thing 2.0 accepted is now rejected:** a plain step's `text`, `command`, `pattern`, `name` or `key` containing `${...}` outside a `repeat` is a validation error (previously kept as a literal string). This only matters if a scenario happened to use that exact syntax outside a `repeat`.

## Migrating from v1

Bump the tag on all three actions (`@v1` → `@v2`); everything else in your workflow file can stay as it is, since `scenario` / `scenario-file` still work exactly as before (one test, id = the file's stem or `inline`, still gets a harness reset before it runs). What changes if you look closer:

- **`result.json` is schemaVersion 2.** `steps`, `screenshots` and the reset moved from the root into `tests[]` (a run can now hold more than one test); logs moved into `sessions[]`; `players[].op` was removed (it is now per test, in `tests[].players[].op`). If anything of yours reads `result.json` directly, update it — see [contract.md](contract.md). The viewer does not read schemaVersion 1: artifacts from `@v1` show up as "unsupported" with a warning, so keep the whole pipeline (runner + ui) on one major version.
- **Screenshots and logs moved.** `screenshots/<player>/<name>.png` is now `tests/<id>/screenshots/<player>/<name>.png`; `logs/server.log` / `logs/clients/<player>.log` are now `logs/sessions/<n>/server.log` / `logs/sessions/<n>/clients/<player>.log` (a session per `fresh-server` switch).
- **Log matching is now windowed per test.** `wait_for_log` and `assert_no_log` only see lines written since the current test's reset. A stray error at server startup no longer fails your first test — but if you relied on seeing the whole session's log from a single-scenario run, scope your patterns to what happens during that test.
- **New action inputs:** `suite`, `scenarios`, `tests`, `tags`, `isolation`, `fail-fast` — all optional, so a v1-style single-scenario invocation needs none of them.
- **New outputs:** `tests-summary` and `failed-tests` on both `fukurou` and `fukurou/ui`, alongside the unchanged `result` / `status` / `summary`.
- **New CLI commands:** `fukurou list` and multi-test `fukurou validate`; `fukurou schema` gained `suite`.

`schema/scenario.v1.json` and `schema/result.v1.json` stay in this repository for reference if you are still on `@v1`.
