import type { CSSProperties } from "react";
import { css } from "styled-system/css";
import { runGridStyle } from "../components/overview/grid";
import { RunRow } from "../components/overview/RunRow";
import { ShotLinks } from "../components/overview/ShotLinks";
import { SummaryHeader } from "../components/overview/SummaryHeader";
import { Warnings } from "../components/overview/Warnings";
import { useManifest } from "../manifest/useManifest";
import { eyebrowStyle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
// 列見出し。行と同じグリッドにしてプレイヤー列の位置を揃える。縦積みになる狭い画面では隠す
const columnHeads = css(runGridStyle, eyebrowStyle, { display: "none", px: "3", md: { display: "grid" } });

/** #/ : 全バージョン（行）× 全プレイヤー（列）のスクリーンショット一覧 */
export function OverviewPage() {
  const manifest = useManifest();
  const { players, runs } = manifest;
  // 列数は CSS 変数で渡す（Panda のクラスは静的なので、実行時に決まる値はここで入れる）
  const gridStyle = { "--players": Math.max(players.length, 1) } as CSSProperties;

  return (
    <div className={page}>
      <SummaryHeader manifest={manifest} />
      <Warnings warnings={manifest.warnings} />
      <ShotLinks shots={manifest.shots} />

      {runs.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>This report contains no runs.</p>
      ) : (
        <div className={css({ display: "flex", flexDirection: "column", gap: "3" })} style={gridStyle}>
          <div className={columnHeads}>
            <span>Minecraft</span>
            {players.map((player) => (
              <span key={player} className={css({ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" })}>
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
