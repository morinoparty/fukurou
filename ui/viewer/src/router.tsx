import { createHashHistory, createRootRouteWithContext, createRoute, createRouter } from "@tanstack/react-router";
import type { ManifestV2 } from "./contract";
import { NotFound } from "./components/NotFound";
import { RootLayout } from "./components/RootLayout";
import { RouteError } from "./components/RouteError";
import { ComparePage } from "./pages/ComparePage";
import { LogPage } from "./pages/LogPage";
import { OverviewPage } from "./pages/OverviewPage";
import { RunPage } from "./pages/RunPage";
import { TestPage } from "./pages/TestPage";
import { TestRunPage } from "./pages/TestRunPage";

/** 全ルートで共有するコンテキスト。manifest は起動時に一度だけ読み込む */
export interface RouterContext {
  manifest: ManifestV2;
}

/** ログビューアの検索パラメータ。テストの logRanges から来た行範囲（1 始まり、両端含む） */
export interface LogSearch {
  from?: number;
  to?: number;
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFound,
});

// #/ : テスト（行）× バージョン（列）のステータスグリッド
const overviewRoute = createRoute({ getParentRoute: () => rootRoute, path: "/", component: OverviewPage });

// #/tests/<testId> : 1 テストのバージョン × プレイヤーのスクリーンショット一覧
const testRoute = createRoute({ getParentRoute: () => rootRoute, path: "/tests/$testId", component: TestPage });

// #/tests/<testId>/compare/<shot> : 同じ名前のスクリーンショットを全バージョンで並べる
const compareRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/tests/$testId/compare/$shot",
  component: ComparePage,
});

// #/runs/<runId> : 1バージョン分の詳細（セッション、テストの表、環境）
const runRoute = createRoute({ getParentRoute: () => rootRoute, path: "/runs/$runId", component: RunPage });

// #/runs/<runId>/tests/<testId> : 1 バージョンでの 1 テストの詳細（ステップ、スクリーンショット、ログの範囲）
const testRunRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/runs/$runId/tests/$testId",
  component: TestRunPage,
});

/** URL から来た値を 1 以上の整数にする。それ以外は無視する */
function positiveInteger(value: unknown): number | undefined {
  const number = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isInteger(number) && number >= 1 ? number : undefined;
}

// #/runs/<runId>/logs/<index>?from=&to= : flatLogs(result)[index] のログビューア。
// index はプレイヤー名などを URL に入れずに済むよう配列の添字。from / to はテストの行範囲
const logRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/runs/$runId/logs/$logIndex",
  component: LogPage,
  validateSearch: (search: Record<string, unknown>): LogSearch => {
    const from = positiveInteger(search.from);
    const to = positiveInteger(search.to);
    return { ...(from !== undefined ? { from } : {}), ...(to !== undefined ? { to } : {}) };
  },
});

const routeTree = rootRoute.addChildren([overviewRoute, testRoute, compareRoute, runRoute, testRunRoute, logRoute]);

/**
 * ルーターを作る。
 * file:// やサブパス配信でも動くよう、パスはハッシュ（index.html#/runs/...）で持つ。
 */
export function createAppRouter(manifest: ManifestV2) {
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
