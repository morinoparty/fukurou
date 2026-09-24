import { Link } from "@tanstack/react-router";
import { Fragment, type ReactElement, type ReactNode } from "react";
import { css } from "styled-system/css";
import { Breadcrumb, Button } from "../chlorophyll";
import { buttonLinkStyle } from "../styles";

const nav = css({ display: "flex", flexWrap: "wrap", alignItems: "center", columnGap: "4", rowGap: "2" });
// 長いテスト id でもスマホ幅からはみ出さないよう、折り返しと省略を許す
const neighbours = css({
  ml: "auto",
  display: "flex",
  flexWrap: "wrap",
  justifyContent: "flex-end",
  gap: "2",
  maxWidth: "full",
  minWidth: "0",
});
const neighbourLink = css(buttonLinkStyle, {
  maxWidth: "full",
  minWidth: "0",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});

/** パンくずの 1 段。link があればリンク、無ければ現在地 */
export interface Crumb {
  key: string;
  label: ReactNode;
  /** 途中の段に置く Link。省略すると現在地（最後の段）として描く */
  link?: ReactElement;
}

interface PageNavProps {
  /** Overview の次からの段。Overview へのリンクはここで足す */
  crumbs: Crumb[];
  /** 前後へ移るリンク（Link 要素）。ページごとに「隣」の意味が違うので呼び出し側で作る */
  previous?: ReactElement;
  next?: ReactElement;
}

/** パンくず（Overview › … › 現在地）と、前後へ移動するボタン */
export function PageNav({ crumbs, previous, next }: PageNavProps) {
  return (
    <div className={nav}>
      <Breadcrumb.Root>
        <Breadcrumb.List>
          <Breadcrumb.Item>
            <Breadcrumb.Link asChild>
              <Link to="/" activeOptions={{ exact: true }}>
                Overview
              </Link>
            </Breadcrumb.Link>
          </Breadcrumb.Item>
          {crumbs.map((crumb) => (
            // Separator は読み上げ対象外の独立した <li> なので、Item の外（List 直下）に並べる
            <Fragment key={crumb.key}>
              <Breadcrumb.Separator />
              <Breadcrumb.Item>
                {crumb.link ? (
                  <Breadcrumb.Link asChild>{crumb.link}</Breadcrumb.Link>
                ) : (
                  <Breadcrumb.Page>{crumb.label}</Breadcrumb.Page>
                )}
              </Breadcrumb.Item>
            </Fragment>
          ))}
        </Breadcrumb.List>
      </Breadcrumb.Root>
      {(previous || next) && (
        <span className={neighbours}>
          {previous && (
            <Button asChild intent="plain" size="sm" className={neighbourLink}>
              {previous}
            </Button>
          )}
          {next && (
            <Button asChild intent="plain" size="sm" className={neighbourLink}>
              {next}
            </Button>
          )}
        </span>
      )}
    </div>
  );
}
