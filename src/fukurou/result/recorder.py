"""実行の進行に合わせて result.json の内容を組み立て、アトミックに書き出す。"""

from datetime import UTC, datetime
import os
from pathlib import Path
import time

from fukurou import __version__
from fukurou.result.model import (
    Failure,
    FailurePhase,
    FukurouInfo,
    JavaInfo,
    LogInfo,
    MinecraftInfo,
    PlayerInfo,
    PluginInfo,
    ResultV1,
    RunStatus,
    ScenarioInfo,
    ScreenshotInfo,
    StepResult,
)
from fukurou.result.ci import ci_from_env
from fukurou.result.steps import step_label, step_target
from fukurou.runner.portablemc import PORTABLEMC_VERSION

SERVER_KIND = "paper"


def utc_timestamp(moment: datetime) -> str:
    """契約どおり秒精度の UTC（末尾 Z）で表す。"""
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class ResultRecorder:
    """1回の実行の結果を記録する。途中で失敗しても、その時点までの内容で result.json を書ける。"""

    def __init__(self, minecraft_version: str, env: dict[str, str] | None = None):
        self.started_at = datetime.now(UTC)
        self._started_monotonic = time.monotonic()
        self.minecraft = MinecraftInfo(version=minecraft_version, server=SERVER_KIND)
        self.java = JavaInfo()
        self.scenario: ScenarioInfo | None = None
        self.plugins: list[PluginInfo] = []
        self.players: list[PlayerInfo] = []
        self.steps: list[StepResult] = []
        self.failure: Failure | None = None
        self.screenshots: list[ScreenshotInfo] = []
        self.logs: list[LogInfo] = []
        self.ci = ci_from_env(os.environ if env is None else env)

    @property
    def status(self) -> RunStatus:
        """失敗が無ければ passed、シナリオのステップが失敗したら failed、それ以外なら error。

        シナリオ中でもステップに結び付かない失敗（ジョブのキャンセルやタイムアウトによる中断）は、
        プラグインの失敗と区別するため error とする。
        """
        if self.failure is None:
            return "passed"
        step_failed = self.failure.phase == "scenario" and self.failure.step_index is not None
        return "failed" if step_failed else "error"

    def set_steps(self, steps: list) -> None:
        """全ステップを skipped として登録しておき、実行した分だけ結果で置き換える。"""
        self.steps = [
            StepResult(index=index, on=step_target(step), action=step.action, label=step_label(step), status="skipped")
            for index, step in enumerate(steps)
        ]

    def set_players(self, players: list) -> None:
        self.players = [PlayerInfo(name=player.name, op=player.op) for player in players]

    def mark_joined(self, name: str) -> None:
        for player in self.players:
            if player.name == name:
                player.joined = True

    def set_plugin_enabled(self, enabled: dict[str, bool]) -> None:
        """組み込みのプラグイン確認の結果を、名前の分かるプラグインに反映する。"""
        for plugin in self.plugins:
            if plugin.name in enabled:
                plugin.enabled = enabled[plugin.name]

    def step_passed(self, index: int, duration_ms: int, screenshot: str | None = None) -> None:
        self._update_step(index, status="passed", duration_ms=duration_ms, screenshot=screenshot)

    def step_failed(self, index: int, duration_ms: int, error: str) -> None:
        self._update_step(index, status="failed", duration_ms=duration_ms, error=error)
        self.fail("scenario", error, step_index=index)

    def add_screenshot(self, info: ScreenshotInfo) -> None:
        self.screenshots.append(info)

    def fail(self, phase: FailurePhase, message: str, step_index: int | None = None) -> None:
        """最初の失敗だけを記録する。後続の失敗は最初の失敗の結果であることが多いため。"""
        if self.failure is None:
            self.failure = Failure(phase=phase, message=message, step_index=step_index)

    def build(self) -> ResultV1:
        """現時点の内容から ResultV1 を作る。"""
        finished_at = datetime.now(UTC)
        return ResultV1(
            id=f"{SERVER_KIND}-{self.minecraft.version}",
            status=self.status,
            fukurou=FukurouInfo(version=__version__, portablemc=PORTABLEMC_VERSION),
            minecraft=self.minecraft,
            java=self.java,
            scenario=self.scenario,
            plugins=self.plugins,
            players=self.players,
            steps=self.steps,
            failure=self.failure,
            screenshots=self.screenshots,
            logs=self.logs,
            started_at=utc_timestamp(self.started_at),
            finished_at=utc_timestamp(finished_at),
            duration_ms=int((time.monotonic() - self._started_monotonic) * 1000),
            ci=self.ci,
        )

    def write(self, path: Path) -> ResultV1:
        """result.json を書く。途中で中断されても壊れたファイルが残らないよう、一時ファイルから置き換える。"""
        result = self.build()
        write_result(result, path)
        return result

    def _update_step(self, index: int, **changes) -> None:
        self.steps[index] = self.steps[index].model_copy(update=changes)


def write_result(result: ResultV1, path: Path) -> None:
    """ResultV1 を camelCase の JSON としてアトミックに書き出す。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(result.model_dump_json(by_alias=True, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)
