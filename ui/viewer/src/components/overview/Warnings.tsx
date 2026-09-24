import { css } from "styled-system/css";

const box = css({
  bg: "bg.warning",
  color: "fg.warning",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.warning",
  borderRadius: "panel",
  p: "3",
});

interface WarningsProps {
  warnings: string[];
}

/** manifest を作るときに見つかった問題（result.json が無い artifact など） */
export function Warnings({ warnings }: WarningsProps) {
  if (warnings.length === 0) return null;
  return (
    <div className={box} role="status">
      <p className={css({ fontWeight: "semibold" })}>Warnings</p>
      <ul className={css({ mt: "1", pl: "5", listStyleType: "disc", display: "flex", flexDirection: "column", gap: "0.5" })}>
        {warnings.map((warning, index) => (
          <li key={index}>{warning}</li>
        ))}
      </ul>
    </div>
  );
}
