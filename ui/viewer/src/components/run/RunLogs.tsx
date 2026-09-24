import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { LogInfo, ManifestRun } from "../../contract";
import { findLogIndex } from "../../lib/logs";
import { buttonLink } from "../../styles";
import { LogTabs } from "../logs/LogTabs";
import { LogView } from "../logs/LogView";

interface RunLogsProps {
  run: ManifestRun;
  logs: LogInfo[];
}

/**
 * run のページの Logs セクション。サーバー・クライアントなどのログをタブで切り替え、その場で読めるようにする。
 * 全画面で読みたいときはログビューアのページへ移る。
 */
export function RunLogs({ run, logs }: RunLogsProps) {
  // 最初はサーバーログ（無ければ先頭のログ）を開く。失敗の原因はまずサーバー側に出ることが多い
  const [selected, setSelected] = useState(() => Math.max(0, findLogIndex(logs, "server")));
  // 念のため範囲外の index はサーバーログ（無ければ先頭）に戻す
  const log = logs[selected] ?? logs[Math.max(0, findLogIndex(logs, "server"))];

  if (logs.length === 0 || !log) return <p className={css({ color: "fg.muted" })}>No logs were recorded.</p>;

  return (
    <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
      <div className={css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" })}>
        <div className={css({ flex: "1 1 auto", minWidth: "0" })}>
          <LogTabs logs={logs} current={logs.indexOf(log)} mode={{ kind: "select", onSelect: setSelected }} label="Logs of this run" />
        </div>
        <Button asChild size="sm" intent="secondary" className={buttonLink}>
          <Link to="/runs/$id/logs/$logIndex" params={{ id: run.id, logIndex: String(logs.indexOf(log)) }}>
            Open in log viewer
          </Link>
        </Button>
      </div>
      <LogView key={log.path} run={run} log={log} compact />
      <p className={css({ fontSize: "xs", color: "fg.muted" })}>
        Logs are only published when the site was built with logs included; otherwise download the <code>{run.artifact}</code>{" "}
        artifact.
      </p>
    </div>
  );
}
