"""サーバーに対するアクション（"on": "server"）。"""

from typing import Annotated, Literal

from pydantic import Field, PositiveFloat, field_validator

from fukurou.scenario.common import ActionModel, LogPatternFields, NonEmptyStr


class ServerAction(ActionModel):
    """サーバー向けアクションの基底クラス。

    on を省略すると共通アクションとして扱われるため、既定値は持たせず必須にする。
    """

    on: Literal["server"]


class ServerCommand(ServerAction):
    """RCON でサーバーのコンソールコマンドを実行する（先頭の / は不要）。"""

    action: Literal["command"] = "command"
    command: NonEmptyStr

    @field_validator("command")
    @classmethod
    def _strip_slash(cls, command: str) -> str:
        return command.removeprefix("/")


class ServerWaitForLog(ServerAction, LogPatternFields):
    """サーバーのログに正規表現が現れるまで待つ。タイムアウトしたらテスト失敗にする。"""

    action: Literal["wait_for_log"] = "wait_for_log"
    timeout: PositiveFloat = 60.0


class ServerAssertNoLog(ServerAction, LogPatternFields):
    """サーバーのログに正規表現が現れていないことを確認する。"""

    action: Literal["assert_no_log"] = "assert_no_log"


# action の値で、どのサーバーアクションとして検証するかを決める
AnyServerAction = Annotated[
    ServerCommand | ServerWaitForLog | ServerAssertNoLog,
    Field(discriminator="action"),
]
