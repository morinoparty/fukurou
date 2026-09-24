import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import { buttonLink } from "../../styles";

interface ShotLinksProps {
  shots: string[];
  /** 比較ページで表示中のスクリーンショット名（強調表示する） */
  current?: string;
}

/** スクリーンショット名ごとの比較ページへのリンク。表示中のものは primary で示す */
export function ShotLinks({ shots, current }: ShotLinksProps) {
  if (shots.length === 0) return null;
  return (
    <nav aria-label="Compare screenshots" className={css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" })}>
      <span className={css({ color: "fg.muted" })}>Compare:</span>
      {shots.map((shot) => (
        <Button key={shot} asChild size="sm" intent={shot === current ? "primary" : "secondary"} className={buttonLink}>
          <Link to="/compare/$shot" params={{ shot }} aria-current={shot === current ? "page" : undefined}>
            {shot}
          </Link>
        </Button>
      ))}
    </nav>
  );
}
