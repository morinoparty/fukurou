import { useState } from "react";
import { css } from "styled-system/css";
import { Skeleton } from "../chlorophyll";
import type { ManifestRun, ScreenshotInfo } from "../contract";
import { assetUrl } from "../lib/assets";
import { FAILURE_SHOT } from "../lib/runs";
import { useLightbox } from "./lightbox/LightboxContext";

const frameStyle = css.raw({
  position: "relative",
  display: "block",
  width: "full",
  overflow: "hidden",
  borderRadius: "control",
  bg: "bg.muted",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.subtle",
  cursor: "zoom-in",
  transitionProperty: "box-shadow, border-color",
  transitionDuration: "fast",
  _hover: { borderColor: "colorPalette.border.emphasized", boxShadow: "md" },
  _focusVisible: { outlineStyle: "solid", outlineWidth: "2px", outlineColor: "colorPalette.focus.ring", outlineOffset: "2px" },
});

// failure スクリーンショットは赤い枠で目立たせる
const frame = css(frameStyle);
const failureFrame = css(frameStyle, {
  borderColor: "border.error",
  borderWidth: "2px",
  _hover: { borderColor: "border.error" },
});

const img = css({ display: "block", width: "full", height: "auto" });

// 読み込み中は画像の上に Skeleton を重ね、読み込み後に消す
const skeletonLayer = css({ position: "absolute", inset: "0", borderRadius: "0" });

const captionStyle = css.raw({
  mt: "1",
  fontSize: "xs",
  color: "fg.muted",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});
const caption = css(captionStyle);
const failureCaption = css(captionStyle, { color: "fg.error", fontWeight: "semibold" });

interface ScreenshotThumbProps {
  run: ManifestRun;
  /** どのテストのスクリーンショットか。ライトボックスの見出しに使う */
  testId: string;
  shot: ScreenshotInfo;
  /** サムネイルの下に出す説明。省略時はスクリーンショット名 */
  caption?: string;
}

/** スクリーンショットのサムネイル。クリックでライトボックスを開く */
export function ScreenshotThumb({ run, testId, shot, caption: captionText }: ScreenshotThumbProps) {
  const openLightbox = useLightbox();
  const [loaded, setLoaded] = useState(false);
  const src = assetUrl(run, shot.path);
  const isFailure = shot.name === FAILURE_SHOT;
  const label = `${run.id} / ${testId} / ${shot.player} / ${shot.name}`;

  return (
    <figure className={css({ minWidth: "0" })}>
      <button
        type="button"
        title={`Open ${label}`}
        onClick={() => openLightbox({ src, caption: label })}
        className={isFailure ? failureFrame : frame}
      >
        {/* width/height を渡して、読み込み前からアスペクト比の分だけ場所を確保する */}
        <img
          src={src}
          alt={label}
          width={shot.width}
          height={shot.height}
          loading="lazy"
          className={img}
          onLoad={() => setLoaded(true)}
          // 画像が無くても Skeleton を出し続けないようにする（壊れた画像アイコンと alt が見える）
          onError={() => setLoaded(true)}
        />
        {!loaded && <Skeleton variant="rect" className={skeletonLayer} />}
      </button>
      <figcaption className={isFailure ? failureCaption : caption}>{captionText ?? shot.name}</figcaption>
    </figure>
  );
}
