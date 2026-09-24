import { Link } from "@tanstack/react-router";
import { Message } from "./Message";

/** 存在しないハッシュパスを開いたときの表示 */
export function NotFound() {
  return (
    <Message title="Page not found">
      <p>
        <Link to="/">Back to all runs</Link>
      </p>
    </Message>
  );
}
