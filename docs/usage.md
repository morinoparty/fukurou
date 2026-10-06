# Usage

fukurou v3 is a Kotlin library (`com.github.morinoparty:fukurou` on JitPack) plus four GitHub Actions. The actions live in one repository and are released together, so use the same tag (for example `@v3`) for all of them, and the matching library tag (for example `v3.0.0`).

- [`morinoparty/fukurou/versions`](#morinopartyfukurouversions): resolve a version spec to a matrix.
- [`morinoparty/fukurou`](#morinopartyfukurou): run your Gradle test task against one Minecraft version.
- [`morinoparty/fukurou/setup`](#morinopartyfukurousetup): install the system packages and restore the cache, if you run Gradle yourself.
- [`morinoparty/fukurou/ui`](#morinopartyfukuroui): build and publish the viewer site.
- [Configuration](#configuration): the `fukurou.*` keys and their `FUKUROU_*` environment variables.
- [Writing tests](#writing-tests): the Kotlin API.
- [Steps in result.json](#steps-in-resultjson), [Errors and test status](#errors-and-test-status), [Running locally](#running-locally), [Estimating `timeout-minutes`](#estimating-timeout-minutes).

See [contract.md](contract.md) for the exact shape of `result.json` and `manifest.json`.

## `morinoparty/fukurou/versions`

Resolves a version spec against Mojang's version manifest and the Paper API, and outputs a JSON array for `strategy.matrix`. The list is also written to the job summary.

```yaml
- uses: morinoparty/fukurou/versions@v3
  id: versions
  with:
    minecraft-version: 1.21.6-
```

The resolution is done by fukurou's Kotlin command line (`party.morino.fukurou.cli.FukurouCli`), which the action runs with `kotlin/gradlew -q fukurouCli` from the action's own checkout. To do that, it runs `actions/setup-java` with Java 21 and `gradle/actions/setup-gradle`. `actions/setup-java` changes `JAVA_HOME` and `PATH` for the rest of the job, so run this action in its own job (as in the README) or set up Java again afterwards.

### Inputs

| Input | Default | Description |
| --- | --- | --- |
| `minecraft-version` | `latest` | Version spec. See [Version specs](#version-specs). |
| `max-versions` | `16` | Fail when the spec resolves to more versions than this. |
| `paper-channel` | `stable` | Least stable Paper build channel to accept: `stable` (only `STABLE` builds), `beta` (`BETA` or `STABLE`) or `alpha` (any build). See [Paper channels](#paper-channels). |

Values containing whitespace, quotes or backslashes are rejected (none of the valid values contain them).

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

The accepted channels are set by `paper-channel` (only `STABLE` by default). Versions are ordered by Mojang's release order (the order of the version manifest, not numeric order). A spec that names a version older than 1.20 (a single version or the lower bound of a range) is an error, because fukurou supports Minecraft 1.20 or later. A test's `@MinecraftVersions` uses the same syntax minus `latest`.

From a checkout of this repository the same command runs as:

```sh
kotlin/gradlew -p kotlin -q fukurouCli --args="versions 1.21.6- --max-versions 16 --paper-channel stable --output $PWD/matrix.json"
```

The command runs in the Gradle project directory (`kotlin/`), so a relative `--output` is resolved against `kotlin/`; pass an absolute path as above to write the file where you are (without spaces: Gradle splits `--args` on whitespace). Without `--output`, the JSON array is printed to standard output.

### Paper channels

Every Paper build has a channel: `ALPHA` right after a Minecraft release, then `BETA`, then `STABLE`. `paper-channel` names the least stable channel you accept, and defaults to `stable`:

| Value | Accepted builds |
| --- | --- |
| `stable` | `STABLE` |
| `beta` | `BETA`, `STABLE` |
| `alpha` | `ALPHA`, `BETA`, `STABLE` |

A release counts for `latest`, ranges and single versions when it has at least one Paper build in an accepted channel, even when its newest build is less stable (the run then uses the newest accepted build). With `alpha`, a release that Paper has only as `ALPHA` builds is picked too. Give the `versions` action and the run action the same value, so that the run finds a build for every version in the matrix:

```yaml
- uses: morinoparty/fukurou/versions@v3
  id: versions
  with:
    minecraft-version: 1.21.6-
    paper-channel: alpha
# ... and in the matrix job:
- uses: morinoparty/fukurou@v3
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    paper-channel: alpha
```

The run uses the newest build of the version in the accepted channels and records its channel in `result.json` (`minecraft.channel`); the viewer marks `ALPHA` and `BETA` runs with a small badge. An explicit `paper-build` is used whatever its channel, with a warning when it is less stable than `paper-channel`.

## `morinoparty/fukurou`

Runs your fukurou tests (a Gradle task of your build) against one Minecraft version, then uploads the output directory as an artifact. The step fails when the tests did not pass, after the artifact is uploaded and the outputs are set: when Gradle fails, and also when Gradle succeeds but the aggregated `result` output is not `passed` (for example when no `result.json` was written to `out-dir` because the task ran no fukurou tests, was restored from the build cache, or wrote its results elsewhere through `-Dfukurou.outDir`).

```yaml
- uses: actions/checkout@v7
  with:
    persist-credentials: false
- uses: morinoparty/fukurou@v3
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    gradle-task: gameTest
```

What the action does:

1. Checks the inputs (`accept-eula`, `minecraft-version`, a non-empty `gradle-task`, and that `java-version` is not the v2 value `auto`) and computes `work-dir` / `out-dir` / `artifact-name`. Relative `work-dir` / `out-dir` are made absolute against the workspace.
2. Installs Xvfb, `xdotool`, `xmodmap`, `jq` and the OpenGL/OpenAL libraries with `apt-get` (unless `skip-system-deps` is `true`).
3. Restores the PortableMC, Mojang asset, Paper and server JDK cache under `work-dir` with `actions/cache`.
4. Installs the JDK that runs Gradle with `actions/setup-java` (Temurin, `java-version`, default 21) and sets up Gradle with `gradle/actions/setup-gradle`.
5. Runs `./gradlew --console=plain <gradle-task> <gradle-args>` in `working-directory`, with `LIBGL_ALWAYS_SOFTWARE=true`, `GITHUB_TOKEN` and the settings as `FUKUROU_*` environment variables: `FUKUROU_ACCEPT_EULA=true`, `FUKUROU_MINECRAFT_VERSION`, `FUKUROU_PAPER_CHANNEL`, `FUKUROU_PAPER_BUILD` (only when `paper-build` is set), `FUKUROU_WORK_DIR` and `FUKUROU_OUT_DIR`.
6. Uploads `out-dir` with `actions/upload-artifact` (even when the tests failed), sets the outputs from every `<out-dir>/*/result.json`, and appends a per-test table to the job summary.

Your Gradle task must:

- run the fukurou tests in **one** test JVM (`maxParallelForks = 1`; tests that share a server must be in the same JVM),
- **always run** (`outputs.upToDateWhen { false }` and `outputs.cacheIf { false }`): the Minecraft version and the other settings reach the test JVM as environment variables, which are not task inputs, so Gradle could otherwise mark the task `UP-TO-DATE` or restore it `FROM-CACHE` (with `org.gradle.caching=true`, even from another matrix job's version) without starting a server. `out-dir` would then be empty, the result would be `error`, and the step would fail,
- pass the plugin jar(s) your extensions read with `PluginSource.systemProperty("<key>")` as `-Dfukurou.plugin.<key>=<path>`. The action has no plugin input, and `fukurou.plugin.*` is not read from the environment.

See the [README quick start](../README.md#1-gradle) for a complete `gameTest` suite. `actions/setup-java` changes `JAVA_HOME` and `PATH` for the rest of the job.

### Inputs

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `accept-eula` | yes | | Must be `true`. fukurou downloads and runs the Minecraft server and clients, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). |
| `minecraft-version` | yes | | One Minecraft version, such as `1.21.11`. Use the `versions` action to expand ranges into a matrix. Passed as `FUKUROU_MINECRAFT_VERSION`, which overrides the `defaultVersion` of `Paper.fromProperties`. |
| `paper-channel` | | `stable` | Least stable Paper build channel to accept: `stable`, `beta` or `alpha`. The newest accepted build is used, and the run fails when the version has none. Always passed as `FUKUROU_PAPER_CHANNEL`, so it overrides the `defaultChannel` of `Paper.fromProperties`. See [Paper channels](#paper-channels). |
| `paper-build` | | newest accepted | Paper build number. An explicit build is used even when its channel is less stable (with a warning). |
| `working-directory` | | `.` | Directory of the Gradle build that contains the fukurou tests (where `./gradlew` is), relative to the workspace. |
| `gradle-task` | | `gameTest` | Gradle task(s) that run the fukurou tests, separated by spaces. |
| `gradle-args` | | | Extra Gradle arguments, separated by spaces or newlines (for example `-Pfukurou.missingHost=fail --info`, or `--tests *Stamp*`). Quoting is not supported. |
| `java-version` | | `21` | Java version that runs Gradle and the tests. The Minecraft server's Java is picked (and downloaded if needed) by fukurou itself (see [`fukurou.serverJava`](#server-java)). |
| `work-dir` | | `$RUNNER_TEMP/fukurou-work` | Working directory for the server, clients and caches. |
| `out-dir` | | `$RUNNER_TEMP/fukurou-out` | Output directory for `result.json`, screenshots and logs (one subdirectory per server run). |
| `upload-artifact` | | `true` | Upload `out-dir` as an artifact (also when the tests fail). |
| `artifact-name` | | `fukurou-paper-<minecraft-version>` | Artifact name. If you use the `ui` action, keep the `fukurou-paper-` prefix or set its `artifact-pattern` to match your names. |
| `retention-days` | | `14` | Retention days of the uploaded artifact. |
| `github-token` | | `${{ github.token }}` | Token for the GitHub API, passed to the tests as `GITHUB_TOKEN` (used by `PluginSource.githubRelease` to avoid the unauthenticated rate limit and to read private repositories the token can access). |
| `skip-system-deps` | | `false` | Skip installing Xvfb, `xdotool`, `jq` and the OpenGL/OpenAL libraries with `apt-get` (for self-hosted runners that already have them). `jq` is still required to set the outputs. |

Every input is passed to the scripts through environment variables and never interpolated directly into a shell command.

### Outputs

| Output | Description |
| --- | --- |
| `result` | `passed`, `failed` or `error`, aggregated over every `<out-dir>/*/result.json`: `error` when there is none, when one cannot be read, or when any run errored; otherwise `failed` when any run failed; otherwise `passed`. |
| `tests-summary` | Test counts summed over every run as JSON, for example `{"total":3,"passed":2,"failed":1,"error":0,"skipped":0}`. |
| `failed-tests` | Comma-separated `<run label or id>/<test id>` of the tests whose status is `failed` or `error`, for example `stamp-arena/stamp-thinking-face`. |
| `result-file` | Path to the first `result.json` (empty when there is none). |
| `artifact-name` | Name of the uploaded artifact. |
| `out-dir` | Output directory. |

### Output directory

`out-dir` holds one run directory per `GameServerExtension` (per server). The run id is `<type>-<minecraft version>-<label>`:

```
<run id>/result.json                                     # schemaVersion 2; written when the server is planned, after every test, and at teardown
<run id>/tests/<test id>/screenshots/<player>/<name>.png
<run id>/tests/<test id>/screenshots/<player>/failure.png
<run id>/tests/<test id>/screenshots/<player>/<name>.diff.png   # only when assertMatches failed
<run id>/logs/harness.log
<run id>/logs/sessions/<n>/server.log                    # session n's server console record
<run id>/logs/sessions/<n>/clients/<player>.log          # the client's first launch in session n
<run id>/logs/sessions/<n>/clients/<player>.<k>.log      # after a relaunch (k >= 2)
<run id>/crash-reports/<player>/*.txt                    # only when a client crashed
```

See [contract.md](contract.md) for the format of `result.json`.

## `morinoparty/fukurou/setup`

For workflows that call Gradle themselves (for example to run the tests together with other tasks). It installs the system packages (the same list as the root action), sets `FUKUROU_WORK_DIR` and `LIBGL_ALWAYS_SOFTWARE=true` in the job environment, and restores the same tool and asset cache as the root action. It does not install Java, run Gradle, upload artifacts or set test outputs.

```yaml
- uses: morinoparty/fukurou/setup@v3
  with:
    minecraft-version: ${{ matrix.minecraft-version }}
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: "21"
- run: ./gradlew gameTest
  env:
    FUKUROU_ACCEPT_EULA: "true"
    FUKUROU_MINECRAFT_VERSION: ${{ matrix.minecraft-version }}
    FUKUROU_OUT_DIR: ${{ runner.temp }}/fukurou-out
- uses: actions/upload-artifact@v7
  if: always()
  with:
    name: fukurou-paper-${{ matrix.minecraft-version }}
    path: ${{ runner.temp }}/fukurou-out
```

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `minecraft-version` | yes | | Minecraft version (cache key). |
| `work-dir` | | `$RUNNER_TEMP/fukurou-work` | Work directory for tools and caches. A relative path is made absolute against the workspace, so the cache and the test JVM (which runs in the Gradle project directory) use the same directory. |
| `skip-system-deps` | | `false` | `true` skips `apt-get`. |

| Output | Description |
| --- | --- |
| `work-dir` | Resolved (absolute) work directory. |

## `morinoparty/fukurou/ui`

Downloads the run artifacts of the workflow run, builds one static viewer site from them, uploads the site as a workflow artifact, and optionally publishes it to S3-compatible storage (for example Cloudflare R2). Run it in a job that `needs` the test jobs with `if: always()`, so failed runs are shown too. The site also works when opened from disk (`file://`). Every server of every version is its own run, labelled `<version> · <label>`.

```yaml
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

### Inputs

| Input | Default | Description |
| --- | --- | --- |
| `artifact-pattern` | `fukurou-paper-*` | Glob pattern of the run artifacts to download. |
| `artifacts-dir` | | Use this local directory (one subdirectory per artifact) instead of downloading artifacts. |
| `title` | repository name | Title shown in the viewer. |
| `include-logs` | `true` | Include the server and client logs and crash reports in the site (`true` or `false`). |
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
| `tests-summary` | Test counts across every run (test x run) as JSON, for example `{"total":18,"passed":14,"failed":2,"error":0,"skipped":2}`. A "not run" cell (a test absent from a run) is not counted. |
| `failed-tests` | `<test id>@<minecraft version>` (or `<test id>@<minecraft version>/<label>` for a labelled run, which every v3 run is) of every cell whose status is `failed` or `error`, comma separated. Note that this differs from the root action's `<run label or id>/<test id>`. |
| `site-dir` | Local directory that holds the site. |

## Configuration

The library reads its settings from `fukurou.*` system properties of the test JVM and from `FUKUROU_*` environment variables. For every key in the table below the precedence is:

1. the system property (`-Dfukurou.minecraftVersion=1.21.11`, usually forwarded from Gradle's `-Pfukurou.*`),
2. the environment variable (`FUKUROU_MINECRAFT_VERSION`; the name is the key without `fukurou.`, camel case split into words, `.` turned into `_`, upper-cased),
3. the system property `<key>.default` (`-Dfukurou.minecraftVersion.default=1.21.11`, for a default baked into a build script),
4. the built-in default.

An empty value counts as unset. The resolved values are also written back into `FukurouConfig.properties`, so server type factories such as `Paper.fromProperties` see the environment variables too. Unknown `FUKUROU_*` variables are ignored, and invalid values (for example `fukurou.keepWork=yes`) are a `SetupException`.

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `fukurou.acceptEula` | `FUKUROU_ACCEPT_EULA` | `false` | Must be `true`: running means accepting the Minecraft EULA. Only `true` / `false` are accepted. |
| `fukurou.minecraftVersion` | `FUKUROU_MINECRAFT_VERSION` | the `defaultVersion` of `Paper.fromProperties` | Exact Minecraft version. Without it and without a `defaultVersion`, the server fails to start. |
| `fukurou.paperChannel` | `FUKUROU_PAPER_CHANNEL` | the `defaultChannel` of `Paper.fromProperties` (`Stable`) | Least stable Paper channel: `stable`, `beta` or `alpha`. |
| `fukurou.paperBuild` | `FUKUROU_PAPER_BUILD` | newest accepted | Pin a Paper build number. |
| `fukurou.workDir` | `FUKUROU_WORK_DIR` | `.fukurou-work` | Tools (`tools/`), caches (`cache/`, including `cache/jdks/`) and the server and client directories (`servers/<run id>/`). Relative to the test JVM's working directory. |
| `fukurou.outDir` | `FUKUROU_OUT_DIR` | `fukurou-out` | One `<run id>/` directory per server. |
| `fukurou.serverJava` | `FUKUROU_SERVER_JAVA` | `auto` | Java for the server: `auto`, `current` or the path of a `java` executable. See [Server Java](#server-java). |
| `fukurou.memoryBudgetMb` | `FUKUROU_MEMORY_BUDGET_MB` | 90% of `MemAvailable` | Memory budget, in MB, for the servers and clients alive at the same time. See [Memory budget](#memory-budget). |
| `fukurou.missingHost` | `FUKUROU_MISSING_HOST` | `fail` | When Xvfb, `xdotool` or `xmodmap` is missing: `fail` (a setup error) or `skip` (the tests that need clients are aborted, i.e. skipped). |
| `fukurou.keepWork` | `FUKUROU_KEEP_WORK` | `false` | Keep `servers/<run id>/` after the run for debugging. |
| `fukurou.updateBaselines` | `FUKUROU_UPDATE_BASELINES` | `false` | Make `Screenshot.assertMatches` overwrite the baseline images instead of comparing. Read directly when `assertMatches` runs: the system property (even an empty one) wins over the environment variable, and `.default` is not consulted. |
| `fukurou.selection.tests` | `FUKUROU_SELECTION_TESTS` | | Comma-separated; only recorded in `result.selection.tests`. Filter with Gradle `--tests`. |
| `fukurou.selection.tags` | `FUKUROU_SELECTION_TAGS` | | Comma-separated; only recorded in `result.selection.tags`. Filter with JUnit tags. |
| `fukurou.plugin.<key>` | (none) | | Path of a plugin jar, read by `PluginSource.systemProperty("<key>")`. Only a system property; there is no environment variable and no `.default`. |
| | `GITHUB_TOKEN` | | Token for `PluginSource.githubRelease` downloads. |

The JUnit side also reads `junit-platform.properties` (see the README) and these annotations: [`@GameTestId`, `@GameTimeout`, `@FreshServer`, `@MinecraftVersions`](#annotations).

### Server Java

`fukurou.serverJava` decides which `java` starts the Minecraft server (the clients always use Mojang's Java runtime):

| Value | Behaviour |
| --- | --- |
| `auto` (default) | The required major version is the newer of Mojang's `javaVersion.majorVersion` for that release and the newest class file version among the plugin jars (class major − 44). fukurou uses, in order: the test JVM if its major version matches; `$JAVA_HOME_<N>_X64` (`_ARM64` on arm) if it points at a JDK, as on GitHub-hosted runners; a Temurin JDK in `<workDir>/cache/jdks/temurin-<N>/`, downloaded from the Adoptium API (checked against its SHA-256, under a cache lock) when missing. |
| `current` | The test JVM's `java`. If it is too old for the server or the plugins, the run fails with a `SetupException`. |
| a path | That `java` executable (a bare name such as `java` is looked up on `PATH`). |

`harness.log` records which one was used, and `result.json` records the major version in `java.server`.

## Writing tests

All public API is in `party.morino.fukurou` and its sub-packages. Everything that talks to a server is `suspend`.

### The extension

```kotlin
class StampArena : GameServerExtension() {
    val alice by player("Alice", op = true)   // declaration order = join order
    val bob by player("Bob")
    val carol by player("Carol", locale = Locale.JAPAN)   // client language ja_jp (default Locale.US = en_us)

    override fun type(config: FukurouConfig): ServerType = Paper.fromProperties(config)        // optional; this is the default
    override fun ServerSpec.configure() { label = "stamp-arena" }                                 // required
    override suspend fun GameServer.onStarted() {}   // after start and every player joined, per session; harness log only
    override suspend fun GameServer.setUp() {}       // after each test's reset; recorded as phase "beforeEach"
    override suspend fun GameServer.tearDown() {}    // after each test, also after a failure; harness log only
}
```

- One subclass = one independent server = one `result.json`. Every test class that registers it (with `@ExtendWith(StampArena::class)` or a static `@JvmField @RegisterExtension` field) shares the running server. `FukurouClassOrderer` (in `junit-platform.properties`) runs the classes that share a server one after another.
- `player(name, op, locale)` declares a player. Names match `^[A-Za-z0-9_]{3,16}$` and `server` is reserved. The property returns the joined `Player` of the current session (it stays valid across a fresh server).
- `locale` (default `Locale.US`) is the client's language: it is written to the client's `options.txt` as `lang` (`Locale.JAPAN` → `ja_jp`) and is the player's Adventure `Identity.LOCALE`. It needs a language and a country (`Locale.JAPAN`, `Locale.forLanguageTag("ja-JP")`; `Locale.JAPANESE` is rejected). Text the client translates itself — join/leave messages, vanilla command feedback, item names — appears in that language in the client log, so write `awaitChat` patterns for it. `Fukurou.player(name, op, locale)` takes the same argument.
- A test method can take the extension (`arena: StampArena`) as a parameter, and the `GameServer` when exactly one fukurou extension is registered on the class. With several extensions, take the extensions and use `arena.server`. `Player` is not injected.
- `server` is also available as `arena.server`.

### `ServerSpec`

| Member | Default | Description |
| --- | --- | --- |
| `label` | the class name in kebab case | The end of the run id and the run directory name. `^[a-z0-9]+(?:-[a-z0-9]+)*$`, at most 40 characters. |
| `isolation` | `Isolation.Reset()` | See [Isolation](#isolation). |
| `startTimeout` | 600 s | Wait for the server to start (and the agent to answer). |
| `installTimeout` | 900 s | Wait for the client installation. |
| `joinTimeout` | 900 s | Wait for each player to join. |
| `testTimeout` | 600 s | Soft deadline of one test. Override per test or class with `@GameTimeout`. |
| `serverHeap` | `2G` | Server `-Xmx`. |
| `clientHeap` | `1536M` | Client `-Xmx`. |
| `plugins { … }` | | `underTest(source)` (role `under-test`) and `dependency(source)` (role `dependency`), installed in declaration order. |
| `serverFiles(dir)` | | Copy a directory into the server directory before it starts (for example `plugins/MyPlugin/config.yml`). |

Plugin sources (`party.morino.fukurou.plugin.PluginSource`):

| Source | Description |
| --- | --- |
| `systemProperty("key")` | The jar at `-Dfukurou.plugin.key=<path>`. Unset is a `SetupException` that tells you which property to pass. |
| `file(path)` | A local jar. |
| `url(url, sha256 = null)` | Download a jar (checked against `sha256` when given). |
| `githubRelease("owner/repo", tag, asset)` | Download a release asset (uses `GITHUB_TOKEN` when set). |

### Server types

`Paper(version, channel = PaperChannel.Stable, build = null, properties = emptyMap(), agent = true)` is the only server type; `version` is a `String` such as `"1.21.11"` or a `MinecraftVersion`, and every other argument works with either (`Paper("1.21.11", agent = false)`). `Paper.fromProperties(config, defaultVersion = null, defaultChannel = PaperChannel.Stable)` reads `fukurou.minecraftVersion`, `fukurou.paperChannel` and `fukurou.paperBuild`. Adjust the result with `copy`:

```kotlin
override fun type(config: FukurouConfig): ServerType =
    Paper.fromProperties(config, defaultVersion = "1.21.11")
        .withProperties("difficulty" to "normal", "spawn-monsters" to "true") // server.properties on top of fukurou's defaults
        .copy(agent = false)                                                     // no in-server agent
```

fukurou's `server.properties` defaults are a flat world (ground at y = -61), peaceful difficulty without monsters, offline mode, no whitelist and a view distance of 4; the ports, RCON and `max-players` are managed by fukurou and cannot be overridden. With `agent = true` (the default) fukurou copies the `FukurouAgent` plugin into `plugins/` and connects to it after the server has started. Without it, state queries, events and `execute` throw `UnsupportedCapabilityException`.

### Isolation

| Value | Between tests |
| --- | --- |
| `Isolation.Reset(arena = Arena(32, 24), gamemode = GameMode.SURVIVAL, settle = 2.seconds, spawns = emptyMap(), normalizeView = true)` (default) | Teleports the players to their spawn slot (`spawns[name]`, or a fixed slot by join order), fills the arena (`size` x `size` around the origin, `height` from the ground; at most 32768 blocks; `null` leaves blocks alone) with air, re-lays the ground, removes non-player entities, sets the time to noon and the weather to clear, clears each player's inventory, effects, XP and title, sets the game mode and spawn point, ops or de-ops them, waits `settle`, and (with `normalizeView`) clears the chat with F3+D and returns the view to first person. The reset is recorded in `tests[].reset`, not as steps. |
| `Isolation.FreshServer` | Stops the server and starts a new session (new world) before every test. Clients are relaunched, not reinstalled. |
| `Isolation.None` | Nothing. |

`@FreshServer` on a test restarts the server before that test only (a session that has not run a test yet is already fresh and is kept). A client that was left mid-action (keys held, a screen open) by a test that did not pass is relaunched before the next test.

### Annotations

| Annotation | Target | Description |
| --- | --- | --- |
| `@GameTestId("id")` | method | The test id in `result.json` (default: the method name with characters outside `[A-Za-z0-9_.-]` replaced by `-`). Must match `^[A-Za-z0-9][A-Za-z0-9_.-]*$`. Ids that collide inside one result become `<ClassSimpleName>.<id>`; parameterized invocations get `-<n>`. |
| `@GameTimeout(seconds)` | method, class | The test's soft deadline, overriding `ServerSpec.testTimeout`. Keep it shorter than the JUnit timeout in `junit-platform.properties`. |
| `@FreshServer` | method | Restart the server before this test. |
| `@MinecraftVersions("1.21.6-")` | method, class | Run the test only on these versions (`1.21.9`, `1.21.6-`, `1.21.6-1.21.11`). Elsewhere it is `skipped` with `versions: <spec> does not include <version>`. |

JUnit's own `@Tag` becomes `tests[].tags`, `@DisplayName` becomes `tests[].name`, and `@Disabled` and failed assumptions become `skipped`.

### Memory budget

All servers and clients of the JVM share a memory budget, `fukurou.memoryBudgetMb` (default: 90% of `MemAvailable` when first needed). One server is estimated at `serverHeap + 750 MB` plus `clientHeap + 900 MB` per player (5,234 MB for the default heaps and one player: 2048 + 750 + 1536 + 900). Before a server starts, servers that no test class is using any more are stopped, least recently used first, until the new one fits. If the servers in use alone do not fit, a `ServerBudgetException` lists the estimates; raise the budget, use a bigger runner, or lower `serverHeap` / `clientHeap` or the number of players.

### `GameServer`

| Member | Description |
| --- | --- |
| `type`, `label`, `resultId`, `joinAddress`, `players`, `log` | The server's type, label, run id, address, joined players and console log view. |
| `player(name)` | A joined player by name. |
| `command(command, check = CommandCheck.FailOnError)` | Run a console command over RCON (a leading `/` is ignored). An error reply (`Unknown or incomplete command`, `Incorrect argument`, …) throws `CommandFailedError` unless `check = CommandCheck.ReturnRaw`. Returns `CommandResponse(command, text)`. |
| `fill(from, to, block, world)`, `setBlock(at, block, world)`, `time(ticks)`, `weatherClear()` | Command shortcuts. `block` is a block state string such as `minecraft:oak_stairs[facing=east]`. |
| `mark()`, `awaitLog(pattern, timeout = 60.seconds, after = null)`, `assertNoLog(pattern, after = null)` | Server console log matching, inside the test's window. |
| `block(at, world)`, `entities(query)`, `worldState(world)`, `currentTick()`, `awaitTicks(ticks)` | [Server state](#server-state) through the agent. |
| `events` | [Events](#events). |
| `execute(task, args, timeout)` | [Run a companion task](#running-tasks-on-the-server). |
| `fixture(name) { }` | Record the steps inside as phase `fixture` with that fixture name (`^[A-Za-z0-9][A-Za-z0-9_.-]*$`). |
| `capability<C>()` (or `capability(C::class)`) / `require<C>()` | Low-level capabilities of the server type: `null` / `UnsupportedCapabilityException` when the type does not provide `C`. |
| Adventure `Audience` | `sendMessage`, `showTitle`, `playSound`, boss bars… are sent to every joined player (as console commands). |

### `Player`

| Member | Description |
| --- | --- |
| `name`, `uuid`, `profile`, `server`, `log` | The offline-mode name and UUID, declaration, server and client log (`logs/latest.log`) view. |
| `pressKey(key)`, `pressChord(KeySym.F3 + KeySym.D)`, `typeText(text)` | Keyboard input through `xdotool`. |
| `chat(text)` | Open chat with T, type, press Enter. Works for commands. |
| `sendCommand(command, timeout = 10.seconds)` | `chat("/command")`, then wait for the server's `issued server command` line. |
| `perspective(Perspective.THIRD_PERSON_BACK)` | Press F5 until the view matches. |
| `screenshot(name)` | Press F2 and save `tests/<id>/screenshots/<player>/<name>.png`. Names match `^[A-Za-z0-9][A-Za-z0-9_.-]*$`; `failure` is reserved. Returns a `Screenshot(player, name, path, artifactPath, width, height)`. |
| `awaitChat(pattern or Component, timeout = 60.seconds, after = null)`, `assertNoChat(…)`, `mark()` | Match `[CHAT]` lines of the client log inside the test's window. A `Component` is matched as its plain text. |
| `teleport(location)`, `teleport(x, y, z, yaw, pitch, world)`, `look(yaw, pitch)`, `gamemode(mode)`, `op()`, `deop()`, `give(item, count)` | Server-side operations (console commands). |
| `state()` | The player's `PlayerSnapshot` through the agent. |
| Adventure `Audience` | Messages, titles, sounds and boss bars for this player. |

`screenshot(alice, bob, name = "both")` (top level) and `listOf(alice, bob).screenshot("both")` take the screenshots of several players at once.

### Input

| Function | Description |
| --- | --- |
| `keyDown(key)` / `keyUp(key)` | Press and keep a key down / release it. |
| `holdKey(key, duration)` | Hold a key for `duration`. |
| `holding(key) { … }` / `holding(listOf(k1, k2)) { … }` | Press the keys, run the block (which may send more input to the same player), then release them in reverse order, also on an exception or cancellation. There is no vararg overload because `KeySym` is a value class. |
| `mouseMove(x, y)` | Move the cursor in window coordinates (1280x720). This moves the GUI cursor; use `look` to turn the camera in game. |
| `click(button = MouseButton.LEFT)` / `click(x, y, button)` | Click at the cursor / move there and click. |
| `holdMouse(button, duration)` | Hold a mouse button (for example to break a block). |
| `scroll(steps)` | Turn the wheel; positive scrolls down (to the right in the hotbar), negative up. |
| `selectHotbar(slot)` | Press the number key of hotbar slot 0..8. |
| `attack()` / `useItem()` | Left / right click. |
| `look(yaw, pitch)` | Turn the player server-side without moving. |

`KeySym` holds an X11 keysym name: `KeySym("F5")`, `KeySym.of("Enter")` (`Enter`, `Esc` and `Space` are aliases), or the constants `F1`–`F12`, `ENTER`, `ESCAPE`, `SPACE`, `TAB`, `T`, `W`, `A`, `S`, `D`, `E`, `Q`, `F`, `SHIFT`, `CONTROL`, `BACKSPACE`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `DIGIT_1`–`DIGIT_9`, `SLASH`. `MouseButton` is `LEFT`, `MIDDLE` or `RIGHT`.

Keys and buttons still held when a test ends are released, even after a failure, a cancellation or the deadline. If that fails, the client is marked dirty and relaunched before the next test.

### Server state

These go through the agent and run on the server's main thread.

| Function | Returns |
| --- | --- |
| `player.state()` | `PlayerSnapshot`: `name`, `uuid`, `location`, `gameMode` (`"survival"`…), `health`, `maxHealth`, `food`, `saturation`, `level`, `exp`, `flying`, `sneaking`, `sprinting`, `op`, `selectedSlot`, `inventory` (41 slots: 0–35 main with 0–8 the hotbar, 36–39 armor from feet to head, 40 off hand), `openInventory` (`InventoryViewSnapshot(type, title, size, contents)`, or `null` for the player's own inventory, including the creative one, or nothing open), `effects`, `tags`, `locale` (the client language the server received, e.g. `ja_jp`; right after joining it can still be `en_us`); helpers `mainHand`, `offHand`, `count(itemKey)`. A player who is not online is `AgentRequestException("not_found")`. |
| `server.block(BlockPos(x, y, z), world = Worlds.OVERWORLD)` | `BlockSnapshot(world, x, y, z, type, data)`; `data` is the block state string, usable with `setBlock`. |
| `server.entities(EntityQuery(world, type, near, radius = 16.0, tag, limit = 256))` | `List<EntitySnapshot>` (`uuid`, `type`, `location`, `name`, `customName`, `tags`, `health`, `dead`), players included. `limit` is 1..4096. |
| `server.worldState(world = Worlds.OVERWORLD)` | `WorldSnapshot(key, name, time, fullTime, storm, thundering, difficulty, players)`. |
| `server.currentTick()` | The server's current tick. |
| `server.awaitTicks(ticks)` | Waits until `ticks` (1..72000) server ticks have passed. |

Item stacks are `ItemSnapshot(type, amount, name, lore, customModelData, enchantments, damage, unbreakable)`. Keys are Adventure `Key`s (`Key.key("minecraft:diamond")`); worlds are `Worlds.OVERWORLD`, `Worlds.NETHER`, `Worlds.END` or a plugin world's key.

### Events

`server.events` subscribes to events by class name: a fully qualified name (`org.bukkit.event.block.BlockBreakEvent`, or a plugin's event class) or a simple name of a class in the standard Bukkit and Paper event packages (`BlockBreakEvent`, `AsyncChatEvent`). `BukkitEvents` has constants for common ones. An unknown class is `AgentRequestException("not_found")`; an abstract event without a handler list (such as `PlayerEvent`) is `bad_request`. Events are observed at `MONITOR` priority, cancelled ones included.

| Function | Description |
| --- | --- |
| `events.record(vararg types): EventRecorder` | Subscribe now and collect events. Close it with `use { }`; it is also closed at the end of the test. |
| `recorder.events()` | The events received so far, in order. |
| `recorder.await(timeout = 60.seconds) { predicate }` | Wait for a matching event, including ones that already arrived. |
| `recorder.awaitCount(count, timeout) { predicate }` | Wait until `count` matching events have arrived. |
| `recorder.assertNone { predicate }` | Fail if a matching event has arrived. |
| `recorder.assertNoneWithin(duration) { predicate }` | Wait `duration` and fail as soon as a matching event arrives. |
| `events.await(type, timeout = 60.seconds) { predicate }` | Subscribe and wait for a matching event. Only events that happen **after the call** are seen. |
| `events.expect(type, timeout, predicate) { action }` | Subscribe, run `action`, then wait. Use it (or `record`) when the action itself causes the event. |

A `ServerEvent` has `type` (the class name of the event that fired, which may be a subclass of the subscribed type), `simpleName`, `tick`, `cancelled` (`null` when not cancellable) and `fields`: every public no-argument `getX()` / `isX()` of the event as JSON, named without the prefix (`getPlayer()` → `player`, `isCancelled()` → `cancelled`). Read them with `event["field"]`, `string`, `long`, `double`, `boolean`, or `event.player`. Values are converted as follows: players become their name; other entities, locations, blocks and items become the snapshot JSON above; worlds and other `Keyed` values their key; enums their name; components their JSON; `NaN` and infinities strings; collections, arrays and maps at most 64 elements and 4 levels deep; anything else `toString()`. A wait that runs out throws `EventAssertionError` (an `AssertionError`).

If the connection to the agent drops while the server is alive, fukurou reconnects once (and logs that events in between may be missing). A request that may already have run (`execute`) is not resent; it fails with `AgentRequestException("connection_lost")`. If the server process is gone, the result is `ServerUnavailableException`.

### Running tasks on the server

`server.execute` runs a task that a companion plugin registered with `party.morino.fukurou.agent.api.FukurouTasks`:

| Function | Description |
| --- | --- |
| `execute(task, args: JsonElement? = null, timeout = 30.seconds): JsonElement` | The raw JSON (`JsonNull` for `null`). |
| `execute<R>(task, timeout = 30.seconds): R` | Decode the result with kotlinx.serialization. **The type argument is required**: `val n: Int = server.execute("t")` resolves to the member above and does not compile; write `server.execute<Int>("t")`. |
| `execute<A, R>(task, args: A, timeout = 30.seconds): R` | Encode `args` and decode the result. Here a declared result type is enough: `val back: Echo = server.execute("echo", Echo("hi"))`. |

The Java API, `party.morino.fukurou.agent.api` (Java 17 bytecode, shipped inside the fukurou jar for `compileOnly` and inside the agent plugin at runtime):

| Type | Members |
| --- | --- |
| `FukurouTasks` | `register(name, task)` (names `^[A-Za-z0-9][A-Za-z0-9_.:-]*$`; `IllegalStateException` if already registered), `unregister(name)`, `names()`, `find(name)`. Thread-safe. |
| `FukurouTask` | `Object run(TaskContext context) throws Exception`, called on the main thread. Return `null`, a JSON-able value (same conversion as event fields; other objects go through Gson), a Gson `JsonElement`, or a `CompletionStage` that fukurou waits for. |
| `TaskContext` | `task()`, `argsJson()` (`"null"` without args), `args(Class<T>)` (Gson), `log(message)`. |

The companion plugin must `depend: [FukurouAgent]` and must not shade the API classes (that would create a separate, empty registry). Declare it with `plugins { dependency(PluginSource.systemProperty("companion")) }` and build it against `compileOnly("com.github.morinoparty:fukurou:<tag>")` and `paper-api`; if it compiles for Java 17, add `java { disableAutoTargetJvm() }` because fukurou's Gradle metadata declares JVM 21. See the README for a full example.

Failures: an exception thrown by the task is `ServerTaskException` (`task`, `remoteType`, `remoteStackTrace`; an `AssertionError` thrown on the server shows up as `remoteType = "java.lang.AssertionError"`), an unknown task is `AgentRequestException("not_found")`, a task that does not finish within `timeout` is `HarnessTimeoutException`, and a return value that cannot be converted is a `FukurouException` (agent error `internal`).

### Flow

| Function | Description |
| --- | --- |
| `pause(duration)` | A recorded wait, cut short by the test's deadline. |
| `step(label) { … }` | Record the block as one step named `label`; the steps inside are recorded too. |
| `repeat(times) { index -> … }` | Run the block `times` (0..1000; 0 does nothing) times; `index` is 0-based. The steps inside carry `repeat: [{block, iteration, of}]` (iteration 1-based). Cannot be nested; works inside `parallel` lanes and around `parallel`. See [the note on `repeat` and imports](#repeat-and-imports). |
| `eventually(timeout = 10.seconds, interval = 500.milliseconds) { … }` | Retry the block while it throws `AssertionError` (including `LogAssertionError`, `CommandFailedError`, `EventAssertionError`); after `timeout`, rethrow the last one. Other exceptions (`ServerTaskException`, `AgentRequestException`, fukurou's own) are not retried. |
| `awaitUntil(description, timeout = 30.seconds, interval = 500.milliseconds) { condition }` | Wait until `condition` returns `true`, else `AssertionError("timed out after …s waiting until <description>")`. |
| `parallel { lane { … }; lane { … } }` | Run the lanes at the same time and wait for all of them. At most 16 lanes, no nesting, and client input to one player from one lane only. A failing lane does not stop the others; a dead server or the test's deadline stops all of them. |

`eventually` and `awaitUntil` record one step each; the attempts inside are not recorded.

#### `repeat` and imports

fukurou's `repeat` has the same name as Kotlin's `kotlin.repeat`, and which one an unqualified `repeat(n) { … }` calls depends on the imports:

- `import party.morino.fukurou.repeat` (explicit): fukurou's `repeat`.
- `import party.morino.fukurou.*` (star import): also fukurou's `repeat`. Star imports take priority over Kotlin's default imports, so **every** unqualified `repeat` in that file becomes fukurou's. It cannot be nested, is limited to 1000 iterations, only works in suspend code, and records `repeat` information on the steps inside. Write `kotlin.repeat(n) { … }` for a plain loop in such a file.
- Neither: Kotlin's `kotlin.repeat`. It runs the block too, but records no `repeat` information.

### Images

`party.morino.fukurou.image`:

| Function | Description |
| --- | --- |
| `Screenshot.image()` | The PNG as a `BufferedImage`. |
| `Screenshot.pixel(x, y)` | The `Rgb(red, green, blue)` of one pixel. |
| `Screenshot.averageColor(Region(x, y, width, height))` | The average colour of a region. |
| `Rgb.distanceTo(other)` | The largest per-channel difference. |
| `ImageComparison.compare(actual, expected, channelTolerance = 16, ignore = emptyList())` | An `ImageDiff(differentPixels, totalPixels, diffImage)` with `ratio`. Images of different sizes differ 100%. |
| `Screenshot.assertMatches(baseline, maxDifferentRatio = 0.02, channelTolerance = 16, ignore = emptyList())` | Compare with a baseline PNG. Above the ratio, write `<name>.diff.png` (different pixels red, ignored regions grey) next to the screenshot and throw `AssertionError`. When the baseline is missing, save the screenshot as the baseline (with a warning in `harness.log`) and pass; with `fukurou.updateBaselines=true`, always overwrite it. Baselines live in your repository, so commit them. |

### Without JUnit

The library also works without JUnit:

```kotlin
suspend fun main() = Fukurou.fromSystemProperties().use { fukurou ->
    val server = fukurou.server(Paper("1.21.11")) { label = "scratch" }.start()
    val alice = server.join(fukurou.player("Alice", op = true))
    server.test("scratch-1") {
        alice.chat("hello")
        alice.screenshot("hello")
    }
}
```

`GameServer.test(id) { }` does the same start, reset, recording and `result.json` writing as the extension.

## Steps in result.json

Every call below becomes one entry in `tests[].steps` (`on` is `server`, a player name, or `null`):

| API | `on` | `action` | `label` |
| --- | --- | --- | --- |
| `command`, `fill`, `setBlock`, `time`, `weatherClear`, `teleport`, `look`, `gamemode`, `op`, `deop`, `give`, Audience operations | `server` | `command` | the command, without `/` |
| `awaitLog` / `assertNoLog` | `server` | `wait_for_log` / `assert_no_log` | the pattern |
| `pause(d)` | `null` | `wait` | `1.5s` |
| `pressKey` / `pressChord` | player | `press_key` | `F5` / `F3+d` |
| `keyDown` / `keyUp` (and `holding`) | player | `press_key` | `w down` / `w up` |
| `selectHotbar(slot)` | player | `press_key` | `1`..`9` |
| `perspective(p)` | player | `press_key` (one per F5) | `F5` |
| `holdKey` | player | `hold_key` | `w 2s` |
| `typeText` / `chat` | player | `type_text` / `chat` | the text |
| `sendCommand(c)` | player, then `server` | `chat`, then `wait_for_log` | `/c`, then the echo pattern |
| `mouseMove` | player | `mouse_move` | `640,360` |
| `click` / `attack` / `useItem` | player | `click` | `left` / `right` / `middle` |
| `click(x, y)` | player | `mouse_move`, then `click` | `x,y`, then the button |
| `holdMouse` | player | `hold_mouse` | `left 1.5s` |
| `scroll` | player | `scroll` | `3` |
| `screenshot` | player | `screenshot` | the name (`screenshot` holds the artifact path) |
| `awaitChat` / `assertNoChat` | player | `wait_for_log` / `assert_no_log` | the pattern, including `\[CHAT\]` |
| `player.state()` | `server` | `query` | `player Alice` |
| `block` / `entities` / `worldState` / `currentTick` | `server` | `query` | `block 0 -61 0` / `entities type=minecraft:zombie …` / `world minecraft:overworld` / `tick` |
| `awaitTicks(n)` | `server` | `wait_ticks` | `20` |
| `execute(task)` | `server` | `execute` | the task name |
| `events.record(types)` | `server` | `subscribe` | the types, comma separated |
| `recorder.await` / `events.await` | `server` | `await_event` | the types / the type |
| `recorder.awaitCount(n)` | `server` | `await_event` | `n x <types>` |
| `recorder.assertNone` / `assertNoneWithin(d)` | `server` | `assert_no_event` | the types / `<types> 2s` |
| `events.expect` | `server` | `subscribe`, the action's steps, `await_event` | |
| `step(label) { }` | `null` | `step` | the label |
| `eventually` | `null` | `eventually` | `10s` |
| `awaitUntil(description)` | `null` | `await_until` | the description |
| `Screenshot.assertMatches(baseline)` | player | `compare_screenshot` | the baseline file name |

`repeat { }` and `parallel { }` have no entries of their own: the steps inside carry `repeat` and `parallel` fields. `phase` is `beforeEach` inside `setUp`, `fixture` inside `server.fixture(name) { }`, and `test` otherwise. Steps outside a test (`onStarted`, `tearDown`) are only written to `logs/harness.log`. See [contract.md](contract.md#steps).

## Errors and test status

| What the test threw | `status` | `failure.phase` |
| --- | --- | --- |
| `AssertionError` (JUnit / kotlin.test assertions, `LogAssertionError`, `CommandFailedError`, `EventAssertionError`, `assertMatches`, `eventually` / `awaitUntil` giving up) | `failed` | the phase of the failing step (`beforeEach`, `fixture` or `scenario` for the test body) |
| `ServerTaskException`, `AgentRequestException`, any other exception from your code | `failed` | same |
| `ServerUnavailableException` (the server died or the agent connection could not be restored) | `failed`, and every later test of that server is `skipped` (`server died during <id>`) | `scenario`; the run's `failure.phase` is `server` |
| `ClientDiedException`, or input to a client that died | `error` | `client` |
| `HarnessTimeoutException` (the test's deadline, a harness wait, an agent request or task that timed out) or the JUnit timeout | `error` | `timeout` |
| A reset command that failed | `error` | `reset` |
| A failed assumption, `@Disabled`, `@MinecraftVersions` out of range, `fukurou.missingHost=skip` without the host tools | `skipped` | |

A server that cannot start (`SetupException` such as a missing EULA, a missing plugin jar, an unknown version; `ServerBudgetException`; a client that cannot join) sets the run's `failure` (`setup`, `server-start` or `client-join`), the run's status becomes `error`, and its tests stay `skipped` (`not run`); JUnit reports the class as failed.

## Running locally

```sh
./gradlew gameTest -Pfukurou.acceptEula=true -Pfukurou.minecraftVersion=1.21.11
```

This needs a Linux x86_64 machine. When any extension declares players, install the client tools: `xvfb`, `xdotool` and `x11-xserver-utils` (for `xmodmap`), plus the OpenGL/OpenAL libraries (`libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 libopenal1` on Debian/Ubuntu; [`scripts/install-system-deps.sh`](../scripts/install-system-deps.sh) has the full list). fukurou starts its own Xvfb displays, so no X server or GPU is needed; set `LIBGL_ALWAYS_SOFTWARE=true` if your machine has a GPU driver that misbehaves under Xvfb. Extensions without players (state queries, events and `execute` only) need none of this, only Java.

The results land in `fukurou-out/<run id>/` and the server, clients and caches in `.fukurou-work/` (relative to the test JVM's working directory, which for Gradle is the project directory). Add `-Pfukurou.keepWork=true` to keep the server directories, or `-Pfukurou.missingHost=skip` to skip the tests that need clients on a machine without the tools. To view the results, point the `ui` action's `build_manifest.py` at the output directory itself: `python3 ui/scripts/build_manifest.py --artifacts-dir <project dir>/fukurou-out --out site` from a checkout of this repository. Each `<run id>/` inside becomes one run. Do not pass the project directory: every other folder in it (`src/`, `build/`, `.fukurou-work/`, …) would show up as an errored run.

## Estimating `timeout-minutes`

Each server costs a fixed startup (cold: about 2.5–3 minutes for the server plus each player's Quick Play join, more if a server JDK has to be downloaded; warm caches: about 1 minute), then roughly 1 minute per test with `Isolation.Reset` (dominated by `settle` and the test's own waits) or an extra 1–4 minutes per fresh server (`Isolation.FreshServer` or `@FreshServer`). Add the Gradle build itself. A starting point:

```
timeout-minutes: 10 + 3 * (servers - 1) + (number of tests) + 4 * (number of fresh-server tests)
```

Tune it down once you have seen a few real runs; the job summary's per-test durations are the easiest way to do that.
