"""クライアントのログから「もう起動できない」状態を見分ける。

クライアントは描画バックエンドを作れなくてもプロセスが終了せず、そのまま止まることがある
（例: 26.3 の renderpearl で OpenGL も Vulkan も作れなかった場合）。プロセスの生死だけを見ていると
参加待ちのタイムアウトまで何分も待ってしまうので、ログの内容で早めに打ち切る。
"""

import re

# 1 行で致命的と分かるもの
FATAL_LINE = re.compile(r"^.*(?:/FATAL\]|Game crashed!|Minecraft has crashed).*$", re.MULTILINE)
# 描画バックエンドの作成失敗（例: "Failed to create backend OpenGL"）
BACKEND_FAILURE = re.compile(r"^.*Failed to create backend (\w+).*$", re.MULTILINE)
# 描画バックエンドの作成成功（例: "Using graphics backend OpenGL" / "Created backend Vulkan"）
BACKEND_SUCCESS = re.compile(r"Using graphics backend|Created (?:graphics )?backend", re.IGNORECASE)
# 26.3 の renderpearl が試すバックエンド。すべて失敗したら描画できない
KNOWN_BACKENDS = frozenset({"OpenGL", "Vulkan"})


def startup_failure(log_text: str) -> str | None:
    """起動を続けられないと分かる行があれば、その行（複数あればまとめたもの）を返す。無ければ None。"""
    fatal = FATAL_LINE.search(log_text)
    if fatal:
        return fatal.group(0).strip()
    failures = BACKEND_FAILURE.findall(log_text)
    # どれか 1 つでも作れていれば、残りのバックエンドの失敗は問題にならない
    if failures and not BACKEND_SUCCESS.search(log_text) and KNOWN_BACKENDS <= set(failures):
        lines = [match.group(0).strip() for match in BACKEND_FAILURE.finditer(log_text)]
        return "no graphics backend could be created: " + " / ".join(lines)
    return None
