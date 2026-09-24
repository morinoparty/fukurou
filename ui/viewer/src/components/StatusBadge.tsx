import { Badge, type BadgeProps } from "../chlorophyll";
import { css } from "styled-system/css";
import type { RunStatus, StepStatus } from "../contract";

/** バッジで表せる状態。unsupported は表示できない run 用 */
export type BadgeStatus = RunStatus | StepStatus | "unsupported";

// Chlorophyll の Badge は status（success / warning / error / info）で colorPalette を切り替える。
// passed は mori（success）、failed は red（error）、インフラ失敗の error は yellow（warning）に対応させる
const BADGE_STATUS: Partial<Record<BadgeStatus, NonNullable<BadgeProps["status"]>>> = {
  passed: "success",
  failed: "error",
  error: "warning",
};

// status に対応が無い状態（skipped / unsupported / 未知の値）は中立の gray で出す
const neutral = css({ colorPalette: "gray" });

interface StatusBadgeProps {
  status: BadgeStatus;
  size?: BadgeProps["size"];
}

/** passed / failed / error などの状態を示す小さなラベル。色だけに頼らないよう文字でも状態を書く */
export function StatusBadge({ status, size = "sm" }: StatusBadgeProps) {
  const badgeStatus = BADGE_STATUS[status];
  return (
    <Badge
      // 見出しに置く大きいバッジはページの地色（colorPalette.bg）に溶けないよう枠線付きにする
      variant={size === "md" ? "surface" : "subtle"}
      size={size}
      dot
      status={badgeStatus}
      className={badgeStatus ? undefined : neutral}
      data-status={status}
    >
      {status}
    </Badge>
  );
}
