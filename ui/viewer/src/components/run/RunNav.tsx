import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { css } from "styled-system/css";
import { Breadcrumb, Button } from "../../chlorophyll";
import type { ManifestRun } from "../../contract";
import { runLabel } from "../../lib/runs";
import { buttonLink } from "../../styles";

const nav = css({ display: "flex", flexWrap: "wrap", alignItems: "center", columnGap: "4", rowGap: "2" });
const neighbours = css({ ml: "auto", display: "flex", gap: "2" });

interface RunNavProps {
  runs: ManifestRun[];
  current: string;
  /** 現在地がログビューアのときのログ名。run のページ自体ではパンくずの末尾が run になる */
  logLabel?: string;
  /** 前後の run へ移るときの行き先。ログビューアでは同じ種類のログを開けるようにする */
  renderNeighbour?: (run: ManifestRun, children: ReactNode) => ReactNode;
}

/** パンくず（一覧 › run › ログ）と、前後のバージョンへの移動ボタン */
export function RunNav({ runs, current, logLabel, renderNeighbour }: RunNavProps) {
  const index = runs.findIndex((run) => run.id === current);
  const run = index >= 0 ? runs[index] : undefined;
  const previous = index > 0 ? runs[index - 1] : undefined;
  const next = index >= 0 ? runs[index + 1] : undefined;
  const currentLabel = run ? `Minecraft ${runLabel(run)}` : current;

  // 前後の run へのリンク。既定では run のページへ移る
  const neighbour = (target: ManifestRun, children: ReactNode) =>
    renderNeighbour ? (
      renderNeighbour(target, children)
    ) : (
      <Link to="/runs/$id" params={{ id: target.id }}>
        {children}
      </Link>
    );

  return (
    <div className={nav}>
      <Breadcrumb.Root>
        <Breadcrumb.List>
          <Breadcrumb.Item>
            <Breadcrumb.Link asChild>
              <Link to="/" activeOptions={{ exact: true }}>
                All runs
              </Link>
            </Breadcrumb.Link>
          </Breadcrumb.Item>
          <Breadcrumb.Separator />
          <Breadcrumb.Item>
            {logLabel ? (
              <Breadcrumb.Link asChild>
                <Link to="/runs/$id" params={{ id: current }} activeOptions={{ exact: true }}>
                  {currentLabel}
                </Link>
              </Breadcrumb.Link>
            ) : (
              <Breadcrumb.Page>{currentLabel}</Breadcrumb.Page>
            )}
          </Breadcrumb.Item>
          {logLabel && (
            <>
              <Breadcrumb.Separator />
              <Breadcrumb.Item>
                <Breadcrumb.Page>{logLabel}</Breadcrumb.Page>
              </Breadcrumb.Item>
            </>
          )}
        </Breadcrumb.List>
      </Breadcrumb.Root>
      <span className={neighbours}>
        {previous && (
          <Button asChild intent="plain" size="sm" className={buttonLink}>
            {neighbour(previous, <>‹ {runLabel(previous)}</>)}
          </Button>
        )}
        {next && (
          <Button asChild intent="plain" size="sm" className={buttonLink}>
            {neighbour(next, <>{runLabel(next)} ›</>)}
          </Button>
        )}
      </span>
    </div>
  );
}
