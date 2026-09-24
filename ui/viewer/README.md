# fukurou viewer

The static page that shows fukurou results: an overview of every Minecraft version × player with screenshots, a detail page per run, a side-by-side comparison of one screenshot across versions, and a log viewer for the server, harness, client and crash logs of a run.

It is a Vite + React + TypeScript single-page app using TanStack Router with hash routing, styled with [Panda CSS](https://panda-css.com) and morinoparty's design system [Chlorophyll](https://github.com/morinoparty/Chlorophyll) (`@morinoparty/chlorophyll-react`). The build output is committed to [`ui/dist`](../dist) so that the `morinoparty/fukurou/ui` action can copy it without installing Node.js.

## How it finds the data

The viewer reads the site manifest described in [`docs/contract.md`](../../docs/contract.md):

1. `window.__FUKUROU_MANIFEST__`, set by `manifest.js` (a classic `<script>` next to `index.html`, loaded before the app). This is what makes the page work when opened from `file://`.
2. Otherwise `fetch("./manifest.json")`.
3. If neither exists, a short explanation is shown instead.

Every screenshot and log path in a `result.json` is relative to the artifact root; the viewer resolves it as `run.base + path`. Runs whose `result` is `null` or whose `schemaVersion` is newer than 1 are shown as error / unsupported cards.

Routes (hash based, so the site works from any sub-path and from `file://`):

| Route | Page |
| --- | --- |
| `#/` | Overview: summary counts, CI link, warnings, runs × players grid |
| `#/runs/<id>` | One run: failure, screenshots (with each player's client log), steps, logs, environment, plugins |
| `#/runs/<id>/logs/<index>` | Log viewer for `result.logs[<index>]` |
| `#/compare/<shot>` | The screenshot named `<shot>` for every run, per player |

## Log viewer

`#/runs/<id>/logs/<index>` shows one entry of the run's `result.logs` (harness, server, each client, crash reports), loaded with `fetch(run.base + path)`. The run page links to it from the header (server log) and from each player's screenshots (client log), and its Logs section embeds a smaller copy of the same viewer with a button per log.

- Line numbers are those of the file, also while filtering.
- `ERROR` / `WARN` lines are highlighted (Log4j's `[time] [thread/LEVEL]:` and Python's `date time LEVEL` headers). Lines without a header, such as stack traces, take the level of the header above them, so **Errors only** keeps the whole exception. Crash reports have no such headers; there the exception, `Caused by:` and stack-frame lines count as errors. Client `[CHAT]` lines get a subtle tint.
- The filter box is a case-insensitive substring match; matches are marked. **Wrap** toggles line wrapping and keeps the line you were reading (or the end of the log) in view. **Raw** opens the file, **Download** saves it as `<run id>-[<player>-]<file>`.
- The log tabs keep their full labels and scroll sideways on narrow screens; a crash report is labelled `Crash · <player>` (numbered when a player has several) with its path shown on hover and under the heading.
- Only a window of lines is in the DOM (1,000 at a time, 200 in the embedded copy), with "Show more", "Show earlier" and "Jump to end". A 4 MB / 44,000-line server log renders in about 0.25 s and filters in about 0.3 s in headless Chromium.
- Browsers do not let a page opened from `file://` read other local files, so there the viewer does not try and shows an **Open raw log** link instead. Everything else works from `file://`.
- A log that the site does not contain (built with `include-logs: false`) shows a note pointing at the artifact.

## Development

Requirements: Node.js 20.19+ or 22.12+, pnpm, and a GitHub token that can read packages (`read:packages`).

### Registry access for Chlorophyll

`@morinoparty/chlorophyll-react` is published to GitHub Packages, which always needs a token, even for public packages. [`.npmrc`](.npmrc) routes the `@morinoparty` scope to `https://npm.pkg.github.com` and references the token only as `${NODE_AUTH_TOKEN}`; never commit a token.

pnpm (12 and later) does not expand environment variables in credentials that come from a repository's `.npmrc`; it warns `Ignored project-level auth setting` and skips them. Put the same line in your user config instead:

```sh
# once: ~/.npmrc (pnpm expands ${NODE_AUTH_TOKEN} there)
echo '//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}' >> ~/.npmrc

# per shell: a token with read:packages, for example from the GitHub CLI
# (gh auth refresh -s read:packages if your gh token lacks the scope)
export NODE_AUTH_TOKEN="$(gh auth token)"
```

In CI, the `ui` job has `permissions: packages: read`, and `actions/setup-node` with `registry-url: https://npm.pkg.github.com` writes that line to the runner's user config; `NODE_AUTH_TOKEN` is the job's `GITHUB_TOKEN`.

[`pnpm-workspace.yaml`](pnpm-workspace.yaml) allows Chlorophyll 0.4.8 despite pnpm's minimum release age and skips esbuild's postinstall (its binary comes from an optional dependency).

### Run

```sh
cd ui/viewer
pnpm install
pnpm dev        # http://localhost:5173 with sample data from dev/
```

`pnpm dev` serves [`dev/`](dev) as the public directory, so `dev/manifest.js` and the small PNGs under `dev/runs/` act as a fake site. Edit `dev/manifest.js` to try other shapes of data. The `dev/` directory is not part of the build.

To preview a real report, build a site with `ui/scripts/build_manifest.py` and open its `index.html` directly, or serve it with any static file server.

## Checks and build

```sh
pnpm typecheck  # panda codegen, then tsc --noEmit
pnpm build      # panda codegen, tsc --noEmit, then write ../dist (ui/dist)
```

Format with `pnpm dlx prettier@3.6.2 --write src build-plugins vite.config.ts panda.config.ts postcss.config.cjs` (settings in `.prettierrc.json`).

Commit the updated `ui/dist` together with the source change. The build:

- uses relative URLs (`base: "./"`), so the site can live under any path;
- emits a single classic (IIFE) script loaded with `defer` and no `crossorigin` attribute, because browsers refuse module scripts over `file://`;
- keeps the `<script src="./manifest.js">` tag from `index.html` as-is (`vite-ignore`), ahead of the app bundle;
- empties `ui/dist` first, so it only ever contains `index.html` and `assets/`;
- inlines the CSS into that script (Vite injects it at startup), so there is no separate stylesheet to load;
- is deterministic: two builds, also from different checkout paths, produce byte-identical files. CI rebuilds and fails if the committed `ui/dist` differs.

The bundle is about 534 kB (154 kB gzip), of which about 112 kB is CSS. Most of the growth over the Tailwind version is Ark UI's tooltip machinery (zag + floating-ui) behind Chlorophyll's `Tooltip`.

## Styling: Panda CSS and Chlorophyll

[`panda.config.ts`](panda.config.ts) uses the presets `@pandacss/preset-base` and Chlorophyll's `createPreset({ brandColor: "mori", grayColor: stone, radius: "md" })`, with the class and variable prefix `fk`. `panda codegen --clean` writes the generated `styled-system/` (git-ignored; every script regenerates it), and the PostCSS plugin in [`postcss.config.cjs`](postcss.config.cjs) fills the `@layer` declaration in `src/index.css` with only the styles found in `src/` and in the Chlorophyll components the viewer uses.

- The palette is `mori`: `colorPalette: "mori"` is set on `html` (so portalled tooltips get it too) and on the app root. Colors come from semantic tokens (`bg`, `bg.panel`, `fg`, `fg.muted`, `border`, `bg.error` / `fg.error`, `colorPalette.*`), never raw values.
- Light and dark follow the OS. Chlorophyll 0.4.8's semantic colors are light-only, so the config maps each palette's 12-step scale (`gray.1` … `gray.12`, `a1` … `a12`) to the dark reference ramp under `_osDark`; everything built on those steps follows. A few tokens that point at white or a fixed ramp (`bg.panel`, `bg.inverted`, `fg.inverted`, `overlay`) get explicit dark values.
- Chlorophyll marks every recipe `staticCss: ["*"]`, so a `config:resolved` hook drops the recipes the viewer does not use (and the unused `umi` palette) to keep the CSS small.
- Components come from [`src/chlorophyll.ts`](src/chlorophyll.ts), which imports each one from its own directory through the `chlorophyll-components/*` alias (Vite and `tsconfig.json`). The package's public entry points are barrels that also pull in three.js / skinview3d / react-three-fiber, and the package ships TypeScript sources, so the barrel would also type-check components we do not use under this project's stricter compiler options. Add new components to that file and their directory to `include` in `panda.config.ts`.
- Panda classes are atomic: to override a shared style, merge style objects with `css(baseStyle, { ... })` (see `src/styles.ts`) instead of joining class names with `cx`.

Used components: `Badge` (statuses), `Table` (steps, plugins), `Button` (links, toggles, log tabs), `Breadcrumb`, `ModalDialog` (screenshot lightbox), `Tooltip` (full hashes and failure messages), `Skeleton` / `Spinner` (loading), `Separator`.

`ModalDialog` only starts its exit animation on a backdrop click and has no Escape handling or focus trap. The lightbox adds Escape, keeps Tab inside the dialog, returns focus to the thumbnail, and closes through the same exit animation by clicking the backdrop element for Escape and the Close button (with a timeout in case the animation is disabled).

## Layout

```
src/contract.ts        TypeScript types for result.json v1 and manifest v1 (mirror docs/contract.md)
src/manifest/          loading the manifest and exposing it through the router context
src/lib/               pure helpers: asset URLs, formatting, run lookups, log parsing and filtering
src/chlorophyll.ts     the Chlorophyll components the viewer uses
src/styles.ts          shared Panda styles (panel, headings, ...)
src/components/        shared UI (badges, lightbox, thumbnails) and per-page parts; logs/ is the log viewer
src/pages/             the four routes
src/router.tsx         route tree and hash history
build-plugins/         the Vite plugin that turns the module script into a classic one
panda.config.ts        Panda CSS config (Chlorophyll preset, dark mode, trimmed recipes)
dev/                   sample manifest and images for `pnpm dev`
```

When the contract changes, update `src/contract.ts` first and let `pnpm typecheck` point at everything that needs to follow.
