# Usage

fukurou has three GitHub Actions. They live in one repository and are released together, so use the same tag (for example `@v1`) for all of them.

- [`morinoparty/fukurou/versions`](#morinoparty-fukurou-versions): resolve a version spec to a matrix.
- [`morinoparty/fukurou`](#morinoparty-fukurou): run a scenario against one version.
- [`morinoparty/fukurou/ui`](#morinoparty-fukurou-ui): build and publish the viewer site.

## `morinoparty/fukurou/versions`

Resolves a version spec against Mojang's version manifest and the Paper API, and outputs a JSON array for `strategy.matrix`. The list is also written to the job summary.

```yaml
- uses: morinoparty/fukurou/versions@v1
  id: versions
  with:
    minecraft-version: 1.21.6-
```

### Inputs

| Input | Default | Description |
| --- | --- | --- |
| `minecraft-version` | `latest` | Version spec. See [Version specs](#version-specs). |
| `max-versions` | `16` | Fail when the spec resolves to more versions than this. |

### Outputs

| Output | Description |
| --- | --- |
| `matrix` | JSON array of versions, oldest first, for example `["1.21.10","1.21.11"]`. Use it with `fromJSON()`. |

### Version specs

| Spec | Resolves to |
| --- | --- |
| `latest` | The newest release whose latest Paper build is `STABLE`. |
| `1.21.11` | Exactly that version. |
| `1.21.6-` | Every release from 1.21.6 onward whose latest Paper build is `STABLE`. |
| `1.20.5-1.21.11` | Every release in that range (both ends included) whose latest Paper build is `STABLE`. |

Versions are ordered by Mojang's release order. A spec that names a version older than 1.20 (a single version or the lower bound of a range) is an error, because fukurou supports Minecraft 1.20 or later. The same command is available locally as `fukurou versions <spec> [--max-versions N]`.

## `morinoparty/fukurou`

Runs one scenario against one Minecraft version on the runner, then uploads `out-dir` as an artifact. The step fails when the scenario did not pass, after the artifact is uploaded and the outputs are set.

```yaml
- uses: morinoparty/fukurou@v1
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    scenario-file: game-test/scenarios/hello.yml
    plugins-dir: build/libs
    plugins: "*-all.jar"
```

What the action does:

1. Installs `xvfb`, `xdotool` and the OpenGL/OpenAL libraries with `apt-get` (unless `skip-system-deps` is `true`).
2. Installs uv and picks the server's Java version (`java-version: auto` runs `fukurou java`), then installs it with `actions/setup-java` (Temurin).
3. Restores the PortableMC and Mojang asset cache under `work-dir` with `actions/cache`.
4. Runs `fukurou run` with Mesa software rendering (`LIBGL_ALWAYS_SOFTWARE=true`).
5. Uploads `out-dir` with `actions/upload-artifact` (even when the run failed) and sets the outputs.

`actions/setup-java` changes `JAVA_HOME` and `PATH` for the rest of the job, so later steps in the same job use the server's JDK. If you build or test after the fukurou step, run `actions/setup-java` again with the version you need, or run fukurou in its own job.

### Inputs

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `accept-eula` | yes | | Must be `true`. fukurou downloads and runs the Minecraft server and clients, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA). |
| `minecraft-version` | yes | | One Minecraft version, such as `1.21.11`. Use the `versions` action for ranges. |
| `scenario` | | | Inline scenario (JSON or YAML). Set either `scenario` or `scenario-file`. |
| `scenario-file` | | | Path to a scenario file (JSON or YAML), relative to the workspace. |
| `plugins-dir` | | `${{ github.workspace }}` | Directory that contains the plugin jars under test. |
| `plugins` | | `*.jar` | Glob patterns inside `plugins-dir`, separated by newlines or commas. An empty string installs no plugins. |
| `dependencies` | | | YAML list of extra plugins to download. See [Dependencies](#dependencies). |
| `server-properties` | | | Extra `server.properties` lines, one `key=value` per line. They override fukurou's defaults (a flat world, peaceful difficulty, offline mode) and a `server.properties` inside `server-files`. `server-ip`, `server-port`, `enable-rcon`, `rcon.port`, `rcon.password` and `max-players` are managed by fukurou and are ignored with a warning. |
| `server-files` | | | Directory whose contents are copied into the server directory before it starts (for example `plugins/MyPlugin/config.yml`). |
| `server-build` | | latest stable | Paper build number. |
| `java-version` | | `auto` | Java major version for the server. `auto` picks the newer of the version Mojang requires and the version the plugin jars are compiled for. The clients always use Mojang's Java runtime. |
| `work-dir` | | `$RUNNER_TEMP/fukurou-work` | Working directory for the server, the clients and the caches. |
| `out-dir` | | `$RUNNER_TEMP/fukurou-out` | Output directory for `result.json`, screenshots and logs. |
| `upload-artifact` | | `true` | Upload `out-dir` as an artifact. |
| `artifact-name` | | `fukurou-paper-<minecraft-version>` | Name of the artifact. If you use the `ui` action, keep the `fukurou-paper-` prefix or set its `artifact-pattern` to match your names. |
| `retention-days` | | `14` | Retention days of the artifact. |
| `github-token` | | `${{ github.token }}` | Token for the GitHub API, used to download `github:` dependencies. It avoids the rate limit for unauthenticated requests and lets you download assets from private repositories the token can read. |
| `skip-system-deps` | | `false` | Skip installing the system packages (for self-hosted runners that already have them). |

Every input is passed to the scripts through environment variables, so multi-line values (`scenario`, `plugins`, `dependencies`, `server-properties`) are passed as they are.

### Outputs

| Output | Description |
| --- | --- |
| `result` | `passed`, `failed` or `error`, read from `result.json`. `error` when there is no `result.json`. |
| `result-file` | Path to `result.json`. |
| `artifact-name` | Name of the uploaded artifact. |
| `out-dir` | Output directory. |

### Dependencies

`dependencies` is a YAML list. Each entry downloads one plugin jar into the server's `plugins` directory:

```yaml
- uses: morinoparty/fukurou@v1
  with:
    accept-eula: "true"
    minecraft-version: ${{ matrix.minecraft-version }}
    scenario-file: game-test/scenarios/hello.yml
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
result.json                   # always written once the inputs are parsed
screenshots/<player>/<name>.png
logs/harness.log
logs/server.log
logs/clients/<player>.log
crash-reports/<player>/*.txt  # only when a client crashed
```

See [contract.md](contract.md) for the format of `result.json`.

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
    - uses: morinoparty/fukurou/ui@v1
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
| `site-dir` | Local directory that holds the site. |

## Command line

The actions call the `fukurou` command. You can run it locally with `uvx --from git+https://github.com/morinoparty/fukurou fukurou ...`.

| Command | Description |
| --- | --- |
| `fukurou versions <spec> [--max-versions N]` | Print the versions a spec resolves to, as a JSON array. |
| `fukurou validate (--scenario-file PATH \| --scenario TEXT)` | Validate a scenario. Exit code 0 when it is valid, 2 otherwise. |
| `fukurou schema {scenario\|result}` | Print a JSON Schema. |
| `fukurou java --minecraft-version X [--plugins-dir DIR] [--plugins GLOBS]` | Print the Java major version to use for the server. |
| `fukurou run --minecraft-version X (--scenario-file PATH \| --scenario TEXT) --accept-eula [options]` | Run a scenario. Exit code 0 when it passed, 1 when it failed or could not run, 2 for invalid input. |

`fukurou run` options:

| Option | Default | Description |
| --- | --- | --- |
| `--plugins-dir DIR` | current directory | Directory that contains the plugin jars. |
| `--plugins GLOBS` | `*.jar` | Glob patterns, separated by newlines or commas. An empty string installs no plugins. |
| `--dependencies YAML` | | Extra plugins to download. See [Dependencies](#dependencies). |
| `--server-properties TEXT` | | Extra `server.properties` lines. |
| `--server-files DIR` | | Files to copy into the server directory. |
| `--server-build N` | latest stable | Paper build number. |
| `--java PATH` | `$JAVA_HOME/bin/java`, or `java` on `PATH` | Java for the server. |
| `--client-java PATH` | Mojang's runtime | Java for the clients. |
| `--work-dir DIR` | `./.fukurou-work` | Working directory. |
| `--out-dir DIR` | `./fukurou-out` | Output directory. |
