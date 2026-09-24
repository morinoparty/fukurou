import type { PluginInfo } from "../../contract";
import { javaFromClassFileMajor, shortHash } from "../../lib/format";

interface PluginsTableProps {
  plugins: PluginInfo[];
}

/** サーバーに入れたプラグインの一覧。有効化できたかどうかを一目で分かるようにする */
export function PluginsTable({ plugins }: PluginsTableProps) {
  if (plugins.length === 0) return <p className="text-sm text-zinc-500">No plugins were installed.</p>;

  return (
    <div className="overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
      <table className="w-full min-w-[40rem] text-left text-sm">
        <thead className="bg-zinc-100 text-xs uppercase tracking-wide text-zinc-500 dark:bg-zinc-900">
          <tr>
            <th className="px-3 py-2">Plugin</th>
            <th className="px-3 py-2">Role</th>
            <th className="px-3 py-2">File</th>
            <th className="px-3 py-2">Needs Java</th>
            <th className="px-3 py-2">Enabled</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-200 bg-white dark:divide-zinc-800 dark:bg-zinc-950">
          {plugins.map((plugin) => (
            <tr key={`${plugin.file}:${plugin.sha256}`} className="align-top">
              <td className="px-3 py-2">
                <span className="font-medium">{plugin.name ?? plugin.file}</span>
                {plugin.version && <span className="ml-1 text-zinc-500">{plugin.version}</span>}
                {plugin.source && <div className="break-all text-xs text-zinc-500">{plugin.source}</div>}
              </td>
              <td className="px-3 py-2">{plugin.role}</td>
              <td className="px-3 py-2">
                <div className="break-all">{plugin.file}</div>
                <code className="text-xs text-zinc-500" title={plugin.sha256}>
                  sha256 {shortHash(plugin.sha256, 12)}
                </code>
              </td>
              <td className="px-3 py-2 tabular-nums">{javaFromClassFileMajor(plugin.classFileMajor) ?? "–"}</td>
              <td className="px-3 py-2">{enabledLabel(plugin.enabled)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** enabled は確認前に失敗すると null になるので、3通りで表示する */
function enabledLabel(enabled: boolean | null) {
  if (enabled === true) return <span className="text-emerald-700 dark:text-emerald-400">yes</span>;
  if (enabled === false) return <span className="font-medium text-red-700 dark:text-red-400">no</span>;
  return <span className="text-zinc-500">unknown</span>;
}
