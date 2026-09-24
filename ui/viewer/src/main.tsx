import "./index.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "@tanstack/react-router";
import { ManifestProblem } from "./components/ManifestProblem";
import { loadManifest } from "./manifest/loadManifest";
import { createAppRouter } from "./router";

const container = document.getElementById("root");
if (!container) throw new Error("#root element is missing from index.html");
const root = createRoot(container);

// バンドルはクラシックスクリプト（IIFE）なので top-level await は使わず、Promise で待つ
void loadManifest().then((state) => {
  const content =
    state.kind === "loaded" ? <RouterProvider router={createAppRouter(state.manifest)} /> : <ManifestProblem state={state} />;
  root.render(<StrictMode>{content}</StrictMode>);
});
