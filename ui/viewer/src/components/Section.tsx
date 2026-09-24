import type { ReactNode } from "react";

interface SectionProps {
  title: string;
  /** 見出しの右側に並べる補足（件数やリンクなど） */
  aside?: ReactNode;
  children: ReactNode;
}

/** 見出し付きのまとまり。詳細ページの各ブロックで共通に使う */
export function Section({ title, aside, children }: SectionProps) {
  return (
    <section className="mt-8">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-lg font-semibold">{title}</h2>
        {aside && <div className="text-sm text-zinc-500">{aside}</div>}
      </div>
      {children}
    </section>
  );
}
