"""シナリオのステップを、result.json の一覧に表示する短い説明へ変換する。"""

from fukurou.scenario import (
    Chat,
    PlayerAssertNoLog,
    PlayerWaitForLog,
    PressKey,
    Screenshot,
    ServerAssertNoLog,
    ServerCommand,
    ServerWaitForLog,
    TypeText,
    Wait,
)


def step_label(step) -> str:
    """一覧表示用の短い説明（コマンド・チャット本文・スクリーンショット名など）。"""
    if isinstance(step, ServerCommand):
        return step.command
    if isinstance(step, (Chat, TypeText)):
        return step.text
    if isinstance(step, PressKey):
        return step.key
    if isinstance(step, Screenshot):
        return step.name
    if isinstance(step, (ServerWaitForLog, PlayerWaitForLog, ServerAssertNoLog, PlayerAssertNoLog)):
        return step.pattern
    if isinstance(step, Wait):
        return f"{step.seconds:g}s"
    return step.action


def step_target(step) -> str | None:
    """"server" / プレイヤー名 / None（wait などの共通アクション）。"""
    return getattr(step, "on", None)
