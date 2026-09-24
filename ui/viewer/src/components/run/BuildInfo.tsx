import type { ReactNode } from "react";
import type { ResultV1 } from "../../contract";
import { formatDateTime, formatDuration, shortHash } from "../../lib/format";

interface BuildInfoProps {
  result: ResultV1;
}

/** Minecraft / Java / fukurou / シナリオ / CI の情報を定義リストで並べる */
export function BuildInfo({ result }: BuildInfoProps) {
  const { minecraft, java, fukurou, scenario, ci } = result;
  return (
    <dl className="grid gap-x-6 gap-y-3 rounded-lg border border-zinc-200 bg-white p-4 text-sm sm:grid-cols-2 lg:grid-cols-3 dark:border-zinc-800 dark:bg-zinc-900">
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
          <div className="break-all text-xs text-zinc-500">
            {scenario.source} · <code title={scenario.sha256}>sha256 {shortHash(scenario.sha256, 12)}</code>
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
          <div className="break-all text-xs text-zinc-500">
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
    <div className="min-w-0">
      <dt className="text-xs uppercase tracking-wide text-zinc-500">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  );
}
