import { useMemo, useState } from "react";
import { css } from "styled-system/css";
import { Button } from "../chlorophyll";
import { StatusGrid } from "../components/overview/StatusGrid";
import { SummaryHeader } from "../components/overview/SummaryHeader";
import { Warnings } from "../components/overview/Warnings";
import { UnsupportedRunCard } from "../components/UnsupportedRunCard";
import { sortFailuresFirst, supportedResult } from "../lib/runs";
import { useManifest } from "../manifest/useManifest";
import { sectionTitle } from "../styles";

const page = css({ display: "flex", flexDirection: "column", gap: "6" });
const toolbar = css({ display: "flex", flexWrap: "wrap", alignItems: "center", justifyContent: "space-between", gap: "2" });

/** 「failures first」の並び順を覚えておく localStorage のキー */
const FAILURES_FIRST_KEY = "fukurou.failuresFirst";

/** localStorage は file:// やプライベートウィンドウで例外を投げることがあるので、読み書きとも失敗は無視する */
function readFailuresFirst(): boolean {
  try {
    return window.localStorage.getItem(FAILURES_FIRST_KEY) === "1";
  } catch {
    return false;
  }
}

function writeFailuresFirst(value: boolean) {
  try {
    window.localStorage.setItem(FAILURES_FIRST_KEY, value ? "1" : "0");
  } catch {
    // 保存できなくても並び替え自体は効く
  }
}

/** #/ : テスト（行）× バージョン（列）のステータスグリッド */
export function OverviewPage() {
  const manifest = useManifest();
  const { tests, runs } = manifest;
  const [failuresFirst, setFailuresFirst] = useState(readFailuresFirst);
  const ordered = useMemo(() => (failuresFirst ? sortFailuresFirst(tests) : tests), [tests, failuresFirst]);
  // 読めない run（result 無し / v1）は列としては出すが、理由をカードでも示す
  const unsupported = runs.filter((run) => !supportedResult(run));

  const toggle = () => {
    const next = !failuresFirst;
    setFailuresFirst(next);
    writeFailuresFirst(next);
  };

  return (
    <div className={page}>
      <SummaryHeader manifest={manifest} />
      <Warnings warnings={manifest.warnings} />

      {runs.length === 0 ? (
        <p className={css({ color: "fg.muted" })}>This report contains no runs.</p>
      ) : (
        <section>
          <div className={toolbar}>
            <h2 className={sectionTitle}>Tests × versions</h2>
            <Button
              type="button"
              size="sm"
              intent={failuresFirst ? "primary" : "secondary"}
              aria-pressed={failuresFirst}
              onClick={toggle}
            >
              Failures first
            </Button>
          </div>
          {tests.length === 0 ? (
            <p className={css({ mt: "3", color: "fg.muted" })}>No tests were recorded in any run.</p>
          ) : (
            <div className={css({ mt: "3" })}>
              <StatusGrid tests={ordered} runs={runs} />
            </div>
          )}
        </section>
      )}

      {unsupported.length > 0 && (
        <section className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
          <h2 className={sectionTitle}>Versions without a readable result</h2>
          {unsupported.map((run) => (
            <UnsupportedRunCard key={run.id} run={run} />
          ))}
        </section>
      )}
    </div>
  );
}
