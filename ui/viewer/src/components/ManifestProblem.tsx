import type { ManifestState } from "../manifest/loadManifest";
import { Message } from "./Message";

interface ManifestProblemProps {
  state: Exclude<ManifestState, { kind: "loaded" }>;
}

/** manifest が見つからない・読めないときの案内。ルーターを使わずに描画する */
export function ManifestProblem({ state }: ManifestProblemProps) {
  if (state.kind === "invalid") {
    return (
      <Message title="This report could not be read">
        <p>{state.reason}</p>
      </Message>
    );
  }
  return (
    <Message title="No test results here yet">
      <p>
        This is the fukurou viewer, but no <code>manifest.js</code> or <code>manifest.json</code> was found next to{" "}
        <code>index.html</code>.
      </p>
      <p>
        Build a site with <code>ui/scripts/build_manifest.py</code> (or the <code>morinoparty/fukurou/ui</code> action) and open
        its <code>index.html</code>.
      </p>
    </Message>
  );
}
