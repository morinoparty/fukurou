import { Link } from "@tanstack/react-router";
import type { ManifestRun } from "../../contract";
import { runLabel } from "../../lib/runs";

interface RunNavProps {
  runs: ManifestRun[];
  current: string;
}

/** 一覧へ戻るリンクと、前後のバージョンへの移動リンク */
export function RunNav({ runs, current }: RunNavProps) {
  const index = runs.findIndex((run) => run.id === current);
  const previous = index > 0 ? runs[index - 1] : undefined;
  const next = index >= 0 ? runs[index + 1] : undefined;
  return (
    <nav className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
      <Link to="/">← All runs</Link>
      <span className="ml-auto flex gap-4">
        {previous && (
          <Link to="/runs/$id" params={{ id: previous.id }}>
            ‹ {runLabel(previous)}
          </Link>
        )}
        {next && (
          <Link to="/runs/$id" params={{ id: next.id }}>
            {runLabel(next)} ›
          </Link>
        )}
      </span>
    </nav>
  );
}
