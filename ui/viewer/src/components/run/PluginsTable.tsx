import { css } from "styled-system/css";
import { Table } from "../../chlorophyll";
import type { PluginInfo } from "../../contract";
import { javaFromClassFileMajor, shortHash } from "../../lib/format";
import { Hint } from "../Hint";

const sub = css({ fontSize: "xs", color: "fg.muted", wordBreak: "break-all" });
const hash = css({ fontSize: "xs", color: "fg.muted", cursor: "help", borderRadius: "item" });

interface PluginsTableProps {
  plugins: PluginInfo[];
}

/** サーバーに入れたプラグインの一覧（Chlorophyll の Table）。有効化できたかどうかを一目で分かるようにする */
export function PluginsTable({ plugins }: PluginsTableProps) {
  if (plugins.length === 0) return <p className={css({ color: "fg.muted" })}>No plugins were installed.</p>;

  return (
    <Table.Root size="sm" scrollAreaLabel="Plugins">
      <Table.Header>
        <Table.Row>
          <Table.Head>Plugin</Table.Head>
          <Table.Head>Role</Table.Head>
          <Table.Head>File</Table.Head>
          <Table.Head>Needs Java</Table.Head>
          <Table.Head>Enabled</Table.Head>
        </Table.Row>
      </Table.Header>
      <Table.Body>
        {plugins.map((plugin) => (
          <Table.Row key={`${plugin.file}:${plugin.sha256}`}>
            <Table.Cell className={css({ minWidth: "40" })}>
              <span className={css({ fontWeight: "semibold" })}>{plugin.name ?? plugin.file}</span>
              {plugin.version && <span className={css({ ml: "1", color: "fg.muted" })}>{plugin.version}</span>}
              {plugin.source && <div className={sub}>{plugin.source}</div>}
            </Table.Cell>
            <Table.Cell>{plugin.role}</Table.Cell>
            <Table.Cell className={css({ minWidth: "40" })}>
              <div className={css({ wordBreak: "break-all" })}>{plugin.file}</div>
              {/* sha256 は先頭だけ表示し、全文はツールチップで見せる */}
              <Hint content={<code className={css({ wordBreak: "break-all" })}>{plugin.sha256}</code>}>
                <code className={hash} tabIndex={0}>
                  sha256 {shortHash(plugin.sha256, 12)}
                </code>
              </Hint>
            </Table.Cell>
            <Table.Cell className={css({ fontVariantNumeric: "tabular-nums" })}>
              {javaFromClassFileMajor(plugin.classFileMajor) ?? "–"}
            </Table.Cell>
            <Table.Cell>
              <EnabledLabel enabled={plugin.enabled} />
            </Table.Cell>
          </Table.Row>
        ))}
      </Table.Body>
    </Table.Root>
  );
}

/** enabled は確認前に失敗すると null になるので、3通りで表示する */
function EnabledLabel({ enabled }: { enabled: boolean | null }) {
  if (enabled === true) return <span className={css({ color: "fg.success", fontWeight: "semibold" })}>yes</span>;
  if (enabled === false) return <span className={css({ color: "fg.error", fontWeight: "semibold" })}>no</span>;
  return <span className={css({ color: "fg.muted" })}>unknown</span>;
}
