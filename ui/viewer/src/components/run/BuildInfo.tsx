import type { ReactNode } from "react";
import { css } from "styled-system/css";
import type { ResultV1 } from "../../contract";
import { formatDateTime, formatDuration, shortHash } from "../../lib/format";
import { eyebrow, panelStyle } from "../../styles";
import { Hint } from "../Hint";

const list = css(panelStyle, {
  p: "4",
  display: "grid",
  columnGap: "6",
  rowGap: "3",
  gridTemplateColumns: { base: "minmax(0, 1fr)", sm: "repeat(2, minmax(0, 1fr))", lg: "repeat(3, minmax(0, 1fr))" },
});
const sub = css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" });
const hash = css({ cursor: "help", borderRadius: "item" });

interface BuildInfoProps {
  result: ResultV1;
}

/** Minecraft / Java / fukurou / シナリオ / CI の情報を定義リストで並べる */
export function BuildInfo({ result }: BuildInfoProps) {
  const { minecraft, java, fukurou, scenario, ci } = result;
  return (
    <dl className={list}>
      <Item label="Minecraft">
        {minecraft.version} ({minecraft.server}
        {minecraft.build !== null && ` build ${minecraft.build}`}
        {minecraft.channel && `, ${minecraft.channel}`})
      </Item>
      <Item label="Server Java">{java.server ?? "–"}</Item>
      <Item label="fukurou">
        {fukurou.version} (PortableMC {fukurou.portablemc})
      </Item>
      {scenario && (
        <Item label="Scenario">
          {scenario.name}
          <div className={sub}>
            {scenario.source} ·{" "}
            <Hint content={<code className={css({ wordBreak: "break-all" })}>{scenario.sha256}</code>}>
              <code className={hash} tabIndex={0}>
                sha256 {shortHash(scenario.sha256, 12)}
              </code>
            </Hint>
          </div>
        </Item>
      )}
      <Item label="Started">{formatDateTime(result.startedAt)}</Item>
      <Item label="Finished">
        {formatDateTime(result.finishedAt)} ({formatDuration(result.durationMs)})
      </Item>
      {ci && (
        <Item label="CI">
          {ci.repository ?? "–"}
          <div className={sub}>
            {ci.ref && `${ci.ref} · `}
            {ci.sha && <code title={ci.sha}>{shortHash(ci.sha)}</code>}
            {ci.runId && ` · run ${ci.runId}${ci.runAttempt ? ` (attempt ${ci.runAttempt})` : ""}`}
          </div>
        </Item>
      )}
    </dl>
  );
}

interface ItemProps {
  label: string;
  children: ReactNode;
}

/** 定義リストの1項目 */
function Item({ label, children }: ItemProps) {
  return (
    <div className={css({ minWidth: "0" })}>
      <dt className={eyebrow}>{label}</dt>
      <dd className={css({ mt: "0.5" })}>{children}</dd>
    </div>
  );
}
