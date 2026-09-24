import { Link, Outlet } from "@tanstack/react-router";
import { useManifest } from "../manifest/useManifest";
import { LightboxProvider } from "./lightbox/LightboxProvider";

/** 全ページ共通の枠。上部にタイトル（一覧へのリンク）を置く */
export function RootLayout() {
  const manifest = useManifest();
  return (
    <LightboxProvider>
      <header className="border-b border-zinc-200 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-900/80">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3">
          <Link to="/" className="font-semibold text-zinc-900 dark:text-zinc-100">
            {manifest.title}
          </Link>
          <span className="ml-auto text-xs text-zinc-500">fukurou</span>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 pb-16 pt-6">
        <Outlet />
      </main>
    </LightboxProvider>
  );
}
