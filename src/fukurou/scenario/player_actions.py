"""プレイヤー（クライアント）に対するアクション（"on": "<プレイヤー名>"）。"""

from typing import Annotated, Literal

from pydantic import Field, PositiveFloat, field_validator

from fukurou.scenario.common import ActionModel, LogPatternFields, NonEmptyStr, PlayerName

# よく使われる別名を X11 の keysym 名へ寄せる（"Enter" と書いても動くようにする）
KEY_ALIASES = {
    "Enter": "Return",
    "Esc": "Escape",
    "Space": "space",
}


# 複数のプレイヤーを並べた on。展開時にプレイヤーごとの parallel になる（同じ名前を 2 回書くと同じ画面を奪い合う）
PlayerNames = Annotated[list[PlayerName], Field(min_length=1, json_schema_extra={"uniqueItems": True})]
# スクリーンショット名（= ファイル名）の規則 ^[A-Za-z0-9][A-Za-z0-9_.-]*$ に、repeat の中で使う
# "${i}" のようなプレースホルダーを許したもの。パス区切り等は含められない。
# トークンの中身は検証しない: 変数名の誤り（"${I}" など）は他のフィールドと同じく展開時に
# "unknown placeholder" として報告する（正規表現の不一致より読みやすい）
_TOKEN = r"\$\{[^}]*\}"
SCREENSHOT_NAME_TEMPLATE = rf"^(?:[A-Za-z0-9]|{_TOKEN})(?:[A-Za-z0-9_.-]|{_TOKEN})*$"


class PlayerAction(ActionModel):
    """プレイヤー向けアクションの基底クラス。on は scenario の players に宣言した名前（またはその一覧）。

    一覧の on は計画（discovery）の時点でプレイヤーごとのステップに展開されるため、ランナーに渡る
    ステップの on は常に 1 人の名前になる。
    """

    on: PlayerName | PlayerNames

    @field_validator("on")
    @classmethod
    def _normalize_targets(cls, on: str | list[str]) -> str | list[str]:
        if isinstance(on, str):
            return on
        if len(set(on)) != len(on):
            raise ValueError("the players in on must be unique")
        # 1 人だけの一覧は、その名前を直接書いた場合と同じに扱う
        return on[0] if len(on) == 1 else on

    @property
    def targets(self) -> list[str]:
        """対象のプレイヤー名の一覧（1 人なら要素 1 つ）。"""
        return [self.on] if isinstance(self.on, str) else list(self.on)


class PressKey(PlayerAction):
    """キーを1回押して離す。key は X11 の keysym 名（例: F5, t, Return）。"""

    action: Literal["press_key"] = "press_key"
    key: NonEmptyStr

    @field_validator("key")
    @classmethod
    def _resolve_alias(cls, key: str) -> str:
        return KEY_ALIASES.get(key, key)


class TypeText(PlayerAction):
    """文字列をキーボード入力する。チャット欄を開いた後などに使う。"""

    action: Literal["type_text"] = "type_text"
    text: NonEmptyStr


class Chat(PlayerAction):
    """T でチャット欄を開き、文字列を入力して Enter で送信する。コマンドも送れる。"""

    action: Literal["chat"] = "chat"
    text: NonEmptyStr


class PlayerWaitForLog(PlayerAction, LogPatternFields):
    """クライアントのログ（チャットは [CHAT] 付き）に正規表現が現れるまで待つ。"""

    action: Literal["wait_for_log"] = "wait_for_log"
    timeout: PositiveFloat = 60.0


class PlayerAssertNoLog(PlayerAction, LogPatternFields):
    """クライアントのログに正規表現が現れていないことを確認する。"""

    action: Literal["assert_no_log"] = "assert_no_log"


class Screenshot(PlayerAction):
    """バニラの F2 でスクリーンショットを撮り、<プレイヤー名>/<name>.png として保存する。"""

    action: Literal["screenshot"] = "screenshot"
    # ファイル名にそのまま使うため、パス区切り等を含められないようにする。
    # repeat の中の "shot-${i}" も書けるよう、プレースホルダーを含む形も受け付ける。
    # 展開後の名前は常にプレースホルダー無しの規則を満たす（未知・repeat の外のプレースホルダーは展開時に弾く）
    name: Annotated[str, Field(pattern=SCREENSHOT_NAME_TEMPLATE)]


# action の値で、どのプレイヤーアクションとして検証するかを決める
AnyPlayerAction = Annotated[
    PressKey | TypeText | Chat | PlayerWaitForLog | PlayerAssertNoLog | Screenshot,
    Field(discriminator="action"),
]
