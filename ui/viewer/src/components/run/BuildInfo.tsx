import type { ReactNode } from "react";
import { css } from "styled-system/css";
import type { ResultV2 } from "../../contract";
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
  result: ResultV2;
}

/** Minecraft / Java / fukurou / スイート / 選択条件 / CI の情報を定義リストで並べる */
export function BuildInfo({ result }: BuildInfoProps) {
  const { minecraft, java, fukurou, suite, selection, ci } = result;
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
      <Item label="Suite">
        {suite ? (
          <>
            {/* スイート無し（シナリオファイル直接指定）でも isolation などの既定値は記録されるので下の行は常に出す */}
            {suite.source ?? <span className={css({ color: "fg.muted" })}>none (scenario files given directly)</span>}
            <div className={sub}>
              isolation {suite.isolation} · settle {suite.settle}s · {suite.gamemode} ·{" "}
              {suite.arena ? `arena ${suite.arena.size}×${suite.arena.height}` : "no arena reset"}
              {suite.sha256 && (
                <>
                  {" · "}
                  <Hint content={<code className={css({ wordBreak: "break-all" })}>{suite.sha256}</code>}>
                    <code className={hash} tabIndex={0}>
                      sha256 {shortHash(suite.sha256, 12)}
                    </code>
                  </Hint>
                </>
              )}
            </div>
          </>
        ) : (
          // テストの発見前に失敗した run では設定自体が記録されていない
          <span className={css({ color: "fg.muted" })}>–</span>
        )}
      </Item>
      <Item label="Selection">{selectionText(selection)}</Item>
      <Item label="Players">
        {result.players.length === 0
          ? "–"
          : result.players.map((player) => `${player.name}${player.joined ? "" : " (did not join)"}`).join(", ")}
      </Item>
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

/** --test / --tag / --isolation / --fail-fast の記録を 1 行にする */
function selectionText(selection: ResultV2["selection"] | undefined): string {
  if (!selection) return "–";
  const parts: string[] = [];
  if (selection.tests?.length) parts.push(`tests: ${selection.tests.join(", ")}`);
  if (selection.tags?.length) parts.push(`tags: ${selection.tags.join(", ")}`);
  if (selection.isolation) parts.push(`isolation forced to ${selection.isolation}`);
  if (selection.failFast) parts.push("fail-fast");
  return parts.length > 0 ? parts.join(" · ") : "whole suite";
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
