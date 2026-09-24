"""ゲーム内テストのシナリオ（JSON / YAML）を pydantic のモデルとして定義する。

外部プロセスに依存しない純粋なパッケージにしておき、サーバーやクライアントを起動する前に
シナリオの誤りを検出できるようにしている。
"""

from fukurou.scenario.common import Wait
from fukurou.scenario.loader import ScenarioSource, load_scenario, parse_document
from fukurou.scenario.model import FAILURE_SCREENSHOT, PlayerSpec, Scenario, ScenarioError, parse_scenario
from fukurou.scenario.player_actions import (
    Chat,
    PlayerAction,
    PlayerAssertNoLog,
    PlayerWaitForLog,
    PressKey,
    Screenshot,
    TypeText,
)
from fukurou.scenario.server_actions import ServerAction, ServerAssertNoLog, ServerCommand, ServerWaitForLog

__all__ = [
    "FAILURE_SCREENSHOT",
    "Chat",
    "PlayerAction",
    "PlayerAssertNoLog",
    "PlayerSpec",
    "PlayerWaitForLog",
    "PressKey",
    "Scenario",
    "ScenarioError",
    "ScenarioSource",
    "Screenshot",
    "ServerAction",
    "ServerAssertNoLog",
    "ServerCommand",
    "ServerWaitForLog",
    "TypeText",
    "Wait",
    "load_scenario",
    "parse_document",
    "parse_scenario",
]
