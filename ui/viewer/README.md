# fukurou viewer

The static page that shows fukurou results: an overview of every Minecraft version × player with screenshots, a detail page per run, and a side-by-side comparison of one screenshot across versions.

It is a Vite + React + TypeScript single-page app using TanStack Router with hash routing. The build output is committed to [`ui/dist`](../dist) so that the `morinoparty/fukurou/ui` action can copy it without installing Node.js.

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
| `#/runs/<id>` | One run: failure, steps, screenshots, environment, plugins, logs |
| `#/compare/<shot>` | The screenshot named `<shot>` for every run, per player |

## Development

Requirements: Node.js 20.19+ or 22.12+ and pnpm.

```sh
cd ui/viewer
pnpm install
pnpm dev        # http://localhost:5173 with sample data from dev/
```

`pnpm dev` serves [`dev/`](dev) as the public directory, so `dev/manifest.js` and the small PNGs under `dev/runs/` act as a fake site. Edit `dev/manifest.js` to try other shapes of data. The `dev/` directory is not part of the build.

To preview a real report, build a site with `ui/scripts/build_manifest.py` and open its `index.html` directly, or serve it with any static file server.

## Checks and build

```sh
pnpm typecheck  # tsc --noEmit
pnpm build      # typecheck, then write ../dist (ui/dist)
```

Format with `pnpm dlx prettier@3.6.2 --write src build-plugins vite.config.ts` (settings in `.prettierrc.json`).

Commit the updated `ui/dist` together with the source change. The build:

- uses relative URLs (`base: "./"`), so the site can live under any path;
- emits a single classic (IIFE) script loaded with `defer` and no `crossorigin` attribute, because browsers refuse module scripts over `file://`;
- keeps the `<script src="./manifest.js">` tag from `index.html` as-is (`vite-ignore`), ahead of the app bundle;
- empties `ui/dist` first, so it only ever contains `index.html` and `assets/`.

## Layout

```
src/contract.ts        TypeScript types for result.json v1 and manifest v1 (mirror docs/contract.md)
src/manifest/          loading the manifest and exposing it through the router context
src/lib/               pure helpers: asset URLs, formatting, run lookups
src/components/        shared UI (badges, lightbox, thumbnails) and per-page parts
src/pages/             the three routes
src/router.tsx         route tree and hash history
build-plugins/         the Vite plugin that turns the module script into a classic one
dev/                   sample manifest and images for `pnpm dev`
```

When the contract changes, update `src/contract.ts` first and let `pnpm typecheck` point at everything that needs to follow.
