# fukurou

fukurou is an in-game test runner for Minecraft [Paper](https://papermc.io/) plugins. It starts a real Paper server with your plugin, joins it with one or more real vanilla Minecraft clients, drives them from a JSON or YAML scenario, and saves screenshots, logs and a machine-readable `result.json`. A static viewer turns the results of every tested version into one page.

It runs on GitHub Actions (or any Linux x86_64 machine) without a GPU or a Microsoft account:

- **Server:** Paper in offline mode on a flat world (players stand at y=-60), with RCON enabled so the scenario can run console commands.
- **Clients:** vanilla clients installed with [PortableMC](https://github.com/theorzr/portablemc) (a pinned version, checked against its SHA-256). Each player gets its own Xvfb display, and input is sent with `xdotool`. Players join one at a time with Quick Play.
- **Versions:** a version spec such as `1.21.6-` is resolved against Mojang's version manifest and the Paper API, so each version can run as its own matrix job.

fukurou has three GitHub Actions in one repository, released together under one tag:

| Action | What it does |
| --- | --- |
| `morinoparty/fukurou/versions@v1` | Resolves a version spec to a JSON array for a job matrix. |
| `morinoparty/fukurou@v1` | Runs a scenario against one Minecraft version and uploads the results as an artifact. |
| `morinoparty/fukurou/ui@v1` | Builds the viewer site from the artifacts of every version and uploads it to S3-compatible storage. |

See [docs/usage.md](docs/usage.md) for every input and output.

## Quick start

Build your plugin, pass the jar to fukurou, and write a scenario. This workflow tests every version from 1.21.6 onward and publishes one page with the results:

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
      - uses: morinoparty/fukurou/versions@v1
        id: versions
        with:
          minecraft-version: 1.21.6-

  game-test:
    name: Minecraft ${{ matrix.minecraft-version }}
    needs: versions
    runs-on: ubuntu-24.04
    timeout-minutes: 40
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

      - uses: morinoparty/fukurou@v1
        with:
          accept-eula: "true" # You accept the Minecraft EULA: https://aka.ms/MinecraftEULA
          minecraft-version: ${{ matrix.minecraft-version }}
          scenario-file: game-test/scenarios/hello.yml
          plugins-dir: build/libs
          plugins: "*-all.jar"

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

The `ui` action downloads the `fukurou-paper-*` artifacts, builds one viewer page, uploads it as the `fukurou-site` artifact, and publishes it to S3-compatible storage such as Cloudflare R2 when credentials are set. Its `url` output (exposed here as the job output `fukurou_url`) points at the published `index.html`, so later jobs can, for example, post it to the pull request. On pull requests from forks the secrets are empty, and the site is only kept as an artifact.

> [!WARNING]
> GitHub drops a job output that contains the value of any secret. If a secret (for example the bucket name) also appears in `public-base-url`, `fukurou_url` arrives empty with the warning `Skip output 'fukurou_url' since it may contain secret`. In that case, pass the non-secret `uploaded` output between jobs instead, and build the URL in the job that uses it.

The server's Java version is picked automatically: the newer of the version Mojang requires for that Minecraft release and the version your plugin jars are compiled for. The clients use Mojang's own Java runtime. The action installs the server's JDK with `actions/setup-java`, which leaves `JAVA_HOME` and `PATH` pointing at it for the rest of the job; build your plugin before the fukurou step, as above, or set up Java again afterwards.

## Scenario format

A scenario lists its `players` and its `steps`. It can be written in JSON or YAML. The `on` field of a step selects its target:

- `on: server` runs a server action.
- `on: <player name>` runs a player action on that player's client. The player must be listed in `players`.
- No `on` runs a common action.

fukurou reads YAML with YAML 1.2 booleans (only `true` and `false`), so a bare `on:` key works. Quoting it as `"on":` also works and keeps the file readable by YAML 1.1 tools, which treat a bare `on` as `true`.

Unknown fields, actions sent to the wrong target, undeclared players and invalid regular expressions are rejected before anything starts. Check a scenario with `fukurou validate`, or get its JSON Schema with `fukurou schema scenario`.

```yaml
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

`players` entries:

| Field | Description |
| --- | --- |
| `name` | Offline player name (3–16 letters, digits or `_`). `server` is reserved. |
| `op` | Optional. If `true`, the player is made an operator after joining. |

Server actions (`on: server`):

| Action | Fields | Description |
| --- | --- | --- |
| `command` | `command` | Run a console command through RCON. A leading `/` is ignored. |
| `wait_for_log` | `pattern`, `timeout`? | Wait until a regex matches the server log. The default timeout is 60 seconds. |
| `assert_no_log` | `pattern` | Fail if a regex matches the server log. |

Player actions (`on: <player name>`):

| Action | Fields | Description |
| --- | --- | --- |
| `press_key` | `key` | Press and release one key. Use an X11 keysym name, such as `F5`, `t` or `Return`. `Enter`, `Esc` and `Space` also work. |
| `type_text` | `text` | Type text. |
| `chat` | `text` | Open chat with T, type the text, and press Enter. Works for commands too. |
| `wait_for_log` | `pattern`, `timeout`? | Wait until a regex matches the client log. Chat lines appear there with `[CHAT]`. The default timeout is 60 seconds. |
| `assert_no_log` | `pattern` | Fail if a regex matches the client log. |
| `screenshot` | `name` | Press F2 and save the new screenshot as `screenshots/<player>/<name>.png`. The name starts with a letter or digit and may contain letters, digits, `_`, `.` and `-`. `failure` is reserved for the screenshot taken automatically when a run fails. |

Common actions (no `on`):

| Action | Fields | Description |
| --- | --- | --- |
| `wait` | `seconds` | Sleep. |

Screenshots are not compared with baseline images. They are saved so that you can look at them in the viewer.

## Running it locally

fukurou is a Python package. With [uv](https://docs.astral.sh/uv/) you can run it straight from this repository:

```sh
# Which versions does a spec resolve to?
uvx --from git+https://github.com/morinoparty/fukurou fukurou versions 1.21.6-

# Check a scenario
uvx --from git+https://github.com/morinoparty/fukurou fukurou validate --scenario-file game-test/scenarios/hello.yml

# Run it (needs xvfb and xdotool; fukurou starts its own Xvfb displays)
uvx --from git+https://github.com/morinoparty/fukurou fukurou run \
  --accept-eula \
  --minecraft-version 1.21.11 \
  --scenario-file game-test/scenarios/hello.yml \
  --plugins-dir build/libs --plugins '*-all.jar'
```

`fukurou run` writes `fukurou-out/result.json`, screenshots and logs, and keeps the server, clients and caches in `.fukurou-work/`. It exits with 0 when the scenario passed, 1 when it failed or could not run, and 2 for invalid input. Run `fukurou --help` for every command and option.

## Requirements

- **Linux x86_64.** The runner uses Xvfb, `xdotool` and the Linux build of PortableMC. On GitHub Actions, `ubuntu-24.04` works; the action installs the system packages with `apt-get`.
- **Minecraft 1.20 or later.** Clients join with Quick Play and the flat world assumes the 1.18+ world height. Only releases whose latest Paper build is `STABLE` are picked from ranges and `latest`.
- **The Minecraft EULA.** fukurou downloads and runs the Minecraft server and client, so you must accept the [Minecraft EULA](https://aka.ms/MinecraftEULA) with `accept-eula: "true"` (or `--accept-eula`). Without it, nothing is started.
- **Offline mode.** The server runs with `online-mode=false`, so no Microsoft account is needed. Your plugin has to work with offline-mode UUIDs.

## Artifacts and the results contract

Each version uploads one artifact, `fukurou-paper-<version>` by default. It contains `result.json`, `screenshots/<player>/<name>.png`, the harness, server and client logs, and client crash reports. It never contains server jars, client jars, assets, Java runtimes or worlds, so nothing from Minecraft is redistributed. The artifact is uploaded even when the run fails.

The format of `result.json` and of the viewer's `manifest.json` is described in [docs/contract.md](docs/contract.md). Its JSON Schema is printed by `fukurou schema result`.

## Limitations

- Screenshots are saved, not compared. Particles, lighting and animations are not pixel-deterministic.
- Input is keyboard only (key presses and typed text). There is no mouse input, and no way to read the screen other than logs and screenshots.
- Clients are rendered in software by Mesa. They are slow, so a job with several players needs a few minutes per version.
- Only Paper servers are supported.

## License

[MIT](LICENSE)

## Acknowledgements

fukurou is based on the in-game test workflow of [sya-ri/ktAdvancements](https://github.com/sya-ri/ktAdvancements/blob/master/.github/workflows/game-test.yml): a real server and a vanilla client launched with PortableMC, run under Xvfb and driven with `xdotool`. Thank you!
