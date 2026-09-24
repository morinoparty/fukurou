import type { ManifestRun, StepResult } from "../../contract";
import { assetUrl } from "../../lib/assets";
import { formatDuration } from "../../lib/format";
import { useLightbox } from "../lightbox/LightboxContext";
import { StatusBadge } from "../StatusBadge";

interface StepTimelineProps {
  run: ManifestRun;
  steps: StepResult[];
}

/** シナリオのステップを実行順に並べた表。失敗したステップは行ごと強調する */
export function StepTimeline({ run, steps }: StepTimelineProps) {
  const openLightbox = useLightbox();
  // screenshot アクションの行から、そのステップで撮った画像を開く
  const openStepScreenshot = (step: StepResult, path: string) =>
    openLightbox({ src: assetUrl(run, path), caption: `${run.id} / step ${step.index}: ${step.label}` });
  if (steps.length === 0) return <p className="text-sm text-zinc-500">No steps were recorded.</p>;

  return (
    <div className="overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
      <table className="w-full min-w-[40rem] text-left text-sm">
        <thead className="bg-zinc-100 text-xs uppercase tracking-wide text-zinc-500 dark:bg-zinc-900">
          <tr>
            <th className="px-3 py-2">#</th>
            <th className="px-3 py-2">On</th>
            <th className="px-3 py-2">Action</th>
            <th className="px-3 py-2">Label</th>
            <th className="px-3 py-2">Status</th>
            <th className="px-3 py-2 text-right">Duration</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-200 bg-white dark:divide-zinc-800 dark:bg-zinc-950">
          {steps.map((step) => {
            const screenshot = step.screenshot;
            return (
              <tr
                key={step.index}
                id={`step-${step.index}`}
                className={`align-top ${step.status === "failed" ? "bg-red-50 dark:bg-red-500/10" : ""} ${step.status === "skipped" ? "text-zinc-400 dark:text-zinc-600" : ""}`}
              >
                <td className="px-3 py-2 tabular-nums text-zinc-500">{step.index}</td>
                <td className="px-3 py-2">{step.on ?? "–"}</td>
                <td className="px-3 py-2">
                  <code>{step.action}</code>
                </td>
                <td className="px-3 py-2">
                  <div className="break-words">{step.label}</div>
                  {screenshot && (
                    <button
                      type="button"
                      className="text-xs text-sky-700 underline-offset-2 hover:underline dark:text-sky-400"
                      onClick={() => openStepScreenshot(step, screenshot)}
                    >
                      View screenshot
                    </button>
                  )}
                  {step.error && (
                    <pre className="mt-1 whitespace-pre-wrap break-words text-xs text-red-700 dark:text-red-400">
                      {step.error}
                    </pre>
                  )}
                </td>
                <td className="px-3 py-2">
                  <StatusBadge status={step.status} />
                </td>
                <td className="px-3 py-2 text-right tabular-nums">{formatDuration(step.durationMs)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
