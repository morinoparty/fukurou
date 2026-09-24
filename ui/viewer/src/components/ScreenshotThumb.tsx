import type { ManifestRun, ScreenshotInfo } from "../contract";
import { assetUrl } from "../lib/assets";
import { FAILURE_SHOT } from "../lib/runs";
import { useLightbox } from "./lightbox/LightboxContext";

interface ScreenshotThumbProps {
  run: ManifestRun;
  shot: ScreenshotInfo;
  /** サムネイルの下に出す説明。省略時はスクリーンショット名 */
  caption?: string;
}

/** スクリーンショットのサムネイル。クリックでライトボックスを開く */
export function ScreenshotThumb({ run, shot, caption }: ScreenshotThumbProps) {
  const openLightbox = useLightbox();
  const src = assetUrl(run, shot.path);
  const isFailure = shot.name === FAILURE_SHOT;
  const label = `${run.id} / ${shot.player} / ${shot.name}`;

  return (
    <figure className="min-w-0">
      <button
        type="button"
        title={`Open ${label}`}
        onClick={() => openLightbox({ src, caption: label })}
        className={`block w-full overflow-hidden rounded-md bg-zinc-200 ring-1 hover:ring-2 hover:ring-sky-500 dark:bg-zinc-800 ${
          isFailure ? "ring-red-500" : "ring-zinc-300 dark:ring-zinc-700"
        }`}
      >
        {/* width/height を渡して、読み込み前からアスペクト比の分だけ場所を確保する */}
        <img src={src} alt={label} width={shot.width} height={shot.height} loading="lazy" className="block h-auto w-full" />
      </button>
      <figcaption
        className={`mt-1 truncate text-xs ${isFailure ? "font-medium text-red-600 dark:text-red-400" : "text-zinc-600 dark:text-zinc-400"}`}
      >
        {caption ?? shot.name}
      </figcaption>
    </figure>
  );
}
