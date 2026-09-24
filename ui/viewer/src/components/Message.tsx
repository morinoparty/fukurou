import type { ReactNode } from "react";

interface MessageProps {
  title: string;
  children?: ReactNode;
}

/** manifest が無い、run が見つからないなど、ページ全体で伝えたいメッセージ */
export function Message({ title, children }: MessageProps) {
  return (
    <div className="mx-auto mt-16 max-w-xl rounded-lg border border-zinc-200 bg-white p-6 text-center dark:border-zinc-800 dark:bg-zinc-900">
      <h1 className="text-xl font-semibold">{title}</h1>
      {children && <div className="mt-3 space-y-2 text-sm text-zinc-600 dark:text-zinc-400">{children}</div>}
    </div>
  );
}
