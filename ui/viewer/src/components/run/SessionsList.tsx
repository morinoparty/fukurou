import { Link } from "@tanstack/react-router";
import { css } from "styled-system/css";
import { Button } from "../../chlorophyll";
import type { ManifestRun, ResultV2 } from "../../contract";
import { formatDateTime, formatDuration } from "../../lib/format";
import { findLogIndex, logLabels } from "../../lib/logs";
import { flatLogs } from "../../lib/runs";
import { buttonLink, codeBlockStyle, panelStyle } from "../../styles";

const card = css(panelStyle, { p: "3", display: "flex", flexDirection: "column", gap: "2" });
const head = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" });
const kind = css({ px: "1.5", borderRadius: "xs", bg: "bg.muted", color: "fg.muted", fontSize: "xs" });
const meta = css({ fontSize: "xs", color: "fg.muted" });
const failure = css(codeBlockStyle, { color: "fg.error" });
const logsRow = css({ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "2" });

interface SessionsListProps {
  run: ManifestRun;
  result: ResultV2;
}

/** ミリ秒差。finishedAt が無ければ null */
function sessionDuration(startedAt: string, finishedAt: string | null): number | null {
  if (!finishedAt) return null;
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  return Number.isFinite(ms) ? ms : null;
}

/**
 * サーバーセッションの一覧。通常は initial の 1 つだけで、fresh-server のテストがあるとその数だけ増える。
 * それぞれのログ（サーバー、クライアント、クラッシュレポート）へログビューアのリンクを出す
 */
export function SessionsList({ run, result }: SessionsListProps) {
  const logs = flatLogs(result);
  const labels = logLabels(logs);
  if (result.sessions.length === 0) return <p className={css({ color: "fg.muted" })}>No server session was started.</p>;

  return (
    <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
      {result.sessions.map((session) => {
        const duration = sessionDuration(session.startedAt, session.finishedAt);
        return (
          <div key={session.index} className={card}>
            <div className={head}>
              <span className={css({ fontWeight: "semibold", color: "colorPalette.fg" })}>Session {session.index}</span>
              <span className={kind}>{session.kind}</span>
              <span className={meta}>
                {formatDateTime(session.startedAt)}
                {duration !== null && ` · ${formatDuration(duration)}`}
                {session.finishedAt === null && " · did not finish"}
              </span>
            </div>
            <p className={meta}>
              Players: {session.players.length > 0 ? session.players.join(", ") : "none"} · Tests:{" "}
              {session.tests.length > 0
                ? session.tests.map((testId, index) => (
                    <span key={testId}>
                      {index > 0 && ", "}
                      <Link to="/runs/$runId/tests/$testId" params={{ runId: run.id, testId }}>
                        {testId}
                      </Link>
                    </span>
                  ))
                : "none"}
            </p>
            {session.failure && <pre className={failure}>{session.failure}</pre>}
            {session.logs.length > 0 && (
              <div className={logsRow}>
                {session.logs.map((log) => {
                  // このセッションの同じパスのログを flatLogs の中で探し、その添字でログビューアへ飛ぶ
                  const index = logs.findIndex((candidate) => candidate.session === session.index && candidate.path === log.path);
                  const fallback = findLogIndex(logs, log.kind, log.player ?? undefined, session.index);
                  const logIndex = index >= 0 ? index : fallback;
                  if (logIndex < 0) return null;
                  return (
                    <Button key={log.path} asChild size="sm" intent="secondary" className={buttonLink}>
                      <Link
                        to="/runs/$runId/logs/$logIndex"
                        params={{ runId: run.id, logIndex: String(logIndex) }}
                        title={log.path}
                      >
                        {labels[logIndex]}
                      </Link>
                    </Button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
