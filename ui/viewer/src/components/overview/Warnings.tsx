interface WarningsProps {
  warnings: string[];
}

/** manifest を作るときに見つかった問題（result.json が無い artifact など） */
export function Warnings({ warnings }: WarningsProps) {
  if (warnings.length === 0) return null;
  return (
    <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200">
      <p className="font-medium">Warnings</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5">
        {warnings.map((warning, index) => (
          <li key={index}>{warning}</li>
        ))}
      </ul>
    </div>
  );
}
