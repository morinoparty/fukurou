import { createHashHistory, createRootRouteWithContext, createRoute, createRouter } from "@tanstack/react-router";
import type { ManifestV1 } from "./contract";
import { NotFound } from "./components/NotFound";
import { RootLayout } from "./components/RootLayout";
import { RouteError } from "./components/RouteError";
import { ComparePage } from "./pages/ComparePage";
import { OverviewPage } from "./pages/OverviewPage";
import { RunPage } from "./pages/RunPage";

/** 全ルートで共有するコンテキスト。manifest は起動時に一度だけ読み込む */
export interface RouterContext {
  manifest: ManifestV1;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFound,
});

// #/ : 全バージョン × 全プレイヤーの一覧
const overviewRoute = createRoute({ getParentRoute: () => rootRoute, path: "/", component: OverviewPage });

// #/runs/<id> : 1バージョン分の詳細
const runRoute = createRoute({ getParentRoute: () => rootRoute, path: "/runs/$id", component: RunPage });

// #/compare/<shot> : 同じ名前のスクリーンショットを全バージョンで並べる
const compareRoute = createRoute({ getParentRoute: () => rootRoute, path: "/compare/$shot", component: ComparePage });

const routeTree = rootRoute.addChildren([overviewRoute, runRoute, compareRoute]);

/**
 * ルーターを作る。
 * file:// やサブパス配信でも動くよう、パスはハッシュ（index.html#/runs/...）で持つ。
 */
export function createAppRouter(manifest: ManifestV1) {
  return createRouter({
    routeTree,
    history: createHashHistory(),
    context: { manifest },
    defaultErrorComponent: RouteError,
    scrollRestoration: true,
  });
}

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
