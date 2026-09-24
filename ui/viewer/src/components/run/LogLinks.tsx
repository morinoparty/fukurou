import type { LogInfo, ManifestRun } from "../../contract";
import { assetUrl } from "../../lib/assets";

interface LogLinksProps {
  run: ManifestRun;
  logs: LogInfo[];
}

/** ログファイルへのリンク。サイトを include-logs 無しで作った場合はリンク先が存在しない */
export function LogLinks({ run, logs }: LogLinksProps) {
  if (logs.length === 0) return <p className="text-sm text-zinc-500">No logs were recorded.</p>;
  return (
    <div>
      <ul className="space-y-1 text-sm">
        {logs.map((log) => (
          <li key={log.path}>
            <a href={assetUrl(run, log.path)} target="_blank" rel="noreferrer">
              {log.kind}
              {log.player && ` (${log.player})`}
            </a>
            <span className="ml-2 break-all text-xs text-zinc-500">{log.path}</span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-xs text-zinc-500">
        Logs are only published when the site was built with logs included; otherwise download the <code>{run.artifact}</code>{" "}
        artifact.
      </p>
    </div>
  );
}
