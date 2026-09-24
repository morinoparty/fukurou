import type { CSSProperties } from "react";
import { RunRow } from "../components/overview/RunRow";
import { ShotLinks } from "../components/overview/ShotLinks";
import { SummaryHeader } from "../components/overview/SummaryHeader";
import { Warnings } from "../components/overview/Warnings";
import { useManifest } from "../manifest/useManifest";

/** #/ : 全バージョン（行）× 全プレイヤー（列）のスクリーンショット一覧 */
export function OverviewPage() {
  const manifest = useManifest();
  const { players, runs } = manifest;
  // 列数は CSS 変数で渡し、md 以上でだけグリッドとして並べる（狭い画面では縦積み）
  const gridStyle = { "--players": Math.max(players.length, 1) } as CSSProperties;

  return (
    <div className="space-y-6">
      <SummaryHeader manifest={manifest} />
      <Warnings warnings={manifest.warnings} />
      <ShotLinks shots={manifest.shots} />

      {runs.length === 0 ? (
        <p className="text-sm text-zinc-500">This report contains no runs.</p>
      ) : (
        <div className="space-y-3" style={gridStyle}>
          {/* 列見出し。行と同じグリッドにして、プレイヤー列の位置を揃える */}
          <div className="hidden gap-4 px-3 text-xs font-medium uppercase tracking-wide text-zinc-500 md:grid md:grid-cols-[11rem_repeat(var(--players),minmax(0,1fr))]">
            <span>Minecraft</span>
            {players.map((player) => (
              <span key={player} className="truncate">
                {player}
              </span>
            ))}
          </div>
          {runs.map((run) => (
            <RunRow key={run.id} run={run} players={players} />
          ))}
        </div>
      )}
    </div>
  );
}
