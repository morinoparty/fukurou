import { useRouteContext } from "@tanstack/react-router";
import type { ManifestV2 } from "../contract";

/** ルーターのコンテキストに載せた manifest を取り出す */
export function useManifest(): ManifestV2 {
  return useRouteContext({ from: "__root__", select: (context) => context.manifest });
}
