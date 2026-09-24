import { useRouteContext } from "@tanstack/react-router";
import type { ManifestV1 } from "../contract";

/** ルーターのコンテキストに載せた manifest を取り出す */
export function useManifest(): ManifestV1 {
  return useRouteContext({ from: "__root__", select: (context) => context.manifest });
}
