import { Link, type ErrorComponentProps } from "@tanstack/react-router";
import { Message } from "./Message";

/** 想定外のデータで描画に失敗したときも、ページ全体を真っ白にせず理由を出す */
export function RouteError({ error }: ErrorComponentProps) {
  return (
    <Message title="Something went wrong while rendering this page">
      <p>
        <code>{error instanceof Error ? error.message : String(error)}</code>
      </p>
      <p>
        <Link to="/">Back to all runs</Link>
      </p>
    </Message>
  );
}
