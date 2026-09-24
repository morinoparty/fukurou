import { Link } from "@tanstack/react-router";

interface ShotLinksProps {
  shots: string[];
  /** 比較ページで表示中のスクリーンショット名（強調表示する） */
  current?: string;
}

/** スクリーンショット名ごとの比較ページへのリンク */
export function ShotLinks({ shots, current }: ShotLinksProps) {
  if (shots.length === 0) return null;
  return (
    <nav aria-label="Compare screenshots" className="flex flex-wrap items-center gap-2 text-sm">
      <span className="text-zinc-500">Compare:</span>
      {shots.map((shot) => (
        <Link
          key={shot}
          to="/compare/$shot"
          params={{ shot }}
          className={`rounded-full border px-2.5 py-0.5 no-underline hover:no-underline ${
            shot === current
              ? "border-sky-600 bg-sky-600 text-white dark:border-sky-500 dark:bg-sky-500 dark:text-zinc-950"
              : "border-zinc-300 text-zinc-700 hover:border-sky-500 dark:border-zinc-700 dark:text-zinc-300"
          }`}
        >
          {shot}
        </Link>
      ))}
    </nav>
  );
}
