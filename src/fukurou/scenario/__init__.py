"""ゲーム内テストのシナリオ（JSON / YAML）とスイートを pydantic のモデルとして定義する。

外部プロセスに依存しない純粋なパッケージにしておき、サーバーやクライアントを起動する前に
シナリオやスイートの誤りを検出できるようにしている。
"""

from fukurou.scenario.blocks import Parallel, Repeat
from fukurou.scenario.common import Wait
from fukurou.scenario.discovery import PlannedStep, Selection, SuiteError, TestSpec, discover_tests, inspect_tests
from fukurou.scenario.expansion import MAX_LANES, MAX_PLANNED_STEPS, ParallelPosition, RepeatPosition
from fukurou.scenario.loader import ScenarioSource, load_scenario, parse_document
from fukurou.scenario.model import (
    FAILURE_SCREENSHOT,
    Isolation,
    PlayerSpec,
    Scenario,
    ScenarioError,
    Step,
    parse_scenario,
)
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
from fukurou.scenario.suite import ArenaSpec, ResetSpec, Suite

__all__ = [
    "FAILURE_SCREENSHOT",
    "MAX_LANES",
    "MAX_PLANNED_STEPS",
    "ArenaSpec",
    "Chat",
    "Isolation",
    "Parallel",
    "ParallelPosition",
    "PlannedStep",
    "PlayerAction",
    "PlayerAssertNoLog",
    "PlayerSpec",
    "PlayerWaitForLog",
    "PressKey",
    "Repeat",
    "RepeatPosition",
    "ResetSpec",
    "Scenario",
    "ScenarioError",
    "ScenarioSource",
    "Screenshot",
    "Selection",
    "ServerAction",
    "ServerAssertNoLog",
    "ServerCommand",
    "ServerWaitForLog",
    "Step",
    "Suite",
    "SuiteError",
    "TestSpec",
    "TypeText",
    "Wait",
    "discover_tests",
    "inspect_tests",
    "load_scenario",
    "parse_document",
    "parse_scenario",
]
