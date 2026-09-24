import { Link, Outlet } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { useManifest } from "../manifest/useManifest";
import { LightboxProvider } from "./lightbox/LightboxProvider";

// アプリの最上位。Chlorophyll のコンポーネントが mori パレット（colorPalette.*）で描かれるようにする
const root = css({ colorPalette: "mori", minHeight: "100vh" });

const header = css({
  position: "sticky",
  top: "0",
  zIndex: "sticky",
  bg: "bg.panel",
  borderBottomWidth: "1px",
  borderBottomStyle: "solid",
  borderBottomColor: "border.subtle",
});

const headerInner = css({
  maxWidth: "7xl",
  mx: "auto",
  px: "4",
  py: "3",
  display: "flex",
  alignItems: "center",
  gap: "3",
});

const brand = css({
  fontWeight: "bold",
  color: "colorPalette.fg",
  textDecoration: "none",
  minWidth: "0",
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
  _hover: { textDecoration: "underline" },
});

const main = css({ maxWidth: "7xl", mx: "auto", px: "4", pt: "6", pb: "16" });

/** 全ページ共通の枠。上部にタイトル（一覧へのリンク）を置く */
export function RootLayout() {
  const manifest = useManifest();
  return (
    <div className={root}>
      <LightboxProvider>
        <header className={header}>
          <div className={headerInner}>
            <Link to="/" className={brand}>
              {manifest.title}
            </Link>
            <span className={css({ ml: "auto", fontSize: "xs", color: "fg.muted", flexShrink: 0 })}>fukurou</span>
          </div>
        </header>
        <main className={main}>
          <Outlet />
        </main>
      </LightboxProvider>
    </div>
  );
}
