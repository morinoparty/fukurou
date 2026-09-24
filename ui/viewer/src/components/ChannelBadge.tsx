import { Badge } from "../chlorophyll";
import { css } from "styled-system/css";
import type { ManifestRun } from "../contract";
import { supportedResult } from "../lib/runs";

// STABLE 以外のビルドだと分かれば十分なので、状態のバッジ（dot 付き）と紛れない中立の枠線だけで見せる
const channelStyle = css({ colorPalette: "gray", color: "fg.muted", textTransform: "lowercase" });

interface ChannelBadgeProps {
  /** result.minecraft.channel（STABLE / BETA / ALPHA、古い結果では null） */
  channel: string | null | undefined;
}

/** Paper のビルドが STABLE でないときだけ "alpha" / "beta" の小さなラベルを出す。STABLE と不明なら何も出さない */
export function ChannelBadge({ channel }: ChannelBadgeProps) {
  if (!channel || channel.toUpperCase() === "STABLE") return null;
  return (
    <Badge variant="outline" size="sm" className={channelStyle} title={`Paper ${channel.toUpperCase()} build`}>
      {channel.toLowerCase()}
    </Badge>
  );
}

/** run の result から channel を取り出して ChannelBadge を出す。result が読めない run では何も出さない */
export function RunChannelBadge({ run }: { run: ManifestRun }) {
  return <ChannelBadge channel={supportedResult(run)?.minecraft.channel} />;
}
