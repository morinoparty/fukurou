import { Portal, Tooltip } from "../chlorophyll";
import type { ReactElement, ReactNode } from "react";

interface HintProps {
  /** 吹き出しに出す内容 */
  content: ReactNode;
  /** 吹き出しを出す対象。asChild で包むので、フォーカスできる要素か tabIndex 付きの要素にする */
  children: ReactElement;
}

/**
 * 省略した文字列（sha256、長い失敗メッセージ）の全文をホバー・フォーカスで見せるツールチップ。
 * Chlorophyll の Tooltip は自動で Portal しないので、クリップされないよう body 直下に出す。
 */
export function Hint({ content, children }: HintProps) {
  return (
    <Tooltip.Root openDelay={300} closeDelay={100}>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Portal>
        <Tooltip.Positioner>
          <Tooltip.Content>{content}</Tooltip.Content>
        </Tooltip.Positioner>
      </Portal>
    </Tooltip.Root>
  );
}
