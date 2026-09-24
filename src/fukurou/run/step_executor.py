"""計画した 1 ステップを実行し、結果を TestRecorder に記録する。

逐次のステップと parallel ブロックのレーン（別スレッド）の両方がこれを使うため、
ステップの失敗をどう記録するか（クライアントの死亡 → client、サーバーの死亡 → ServerDied、
期限切れの打ち切り → timeout）の規則はすべてここに集める。
"""

import logging
import threading
import time
from typing import Callable

from fukurou.errors import FukurouError
from fukurou.result.model import TestFailurePhase
from fukurou.result.recorder import TestRecorder
from fukurou.runner.client import ClientDiedError
from fukurou.runner.scenario_runner import CapturedScreenshot, ScenarioRunner, StepCancelled
from fukurou.scenario import PlannedStep, TestSpec
from fukurou.server.process import ServerUnavailableError

logger = logging.getLogger(__name__)

# 撮ったスクリーンショットを記録し、artifact 内の相対パスを返す
ScreenshotRecorder = Callable[[TestRecorder, CapturedScreenshot, int], str]


class ServerDied(FukurouError):
    """テストの途中でサーバーが死んだ（RCON 不通またはプロセス終了）。run の失敗（phase server）にする。"""


class StepExecutor:
    """テスト 1 件のステップを 1 つずつ実行して記録する。複数のスレッドから同時に呼んでよい。"""

    def __init__(self, test: TestSpec, recorder: TestRecorder, runner: ScenarioRunner, record_screenshot: ScreenshotRecorder):
        self.test = test
        self.recorder = recorder
        self.runner = runner
        self.record_screenshot = record_screenshot
        # 打ち切られたステップ（StepCancelled）をどう記録するか。既定はテストの期限切れ
        self._cancel_phase: TestFailurePhase | None = "timeout"
        self._cancel_message: str | None = None
        self._lock = threading.Lock()

    @property
    def stop(self) -> threading.Event:
        """立てると待ちのあるステップが打ち切られる（ScenarioRunner と共有）。"""
        return self.runner.stop

    def cancel(self, phase: TestFailurePhase | None, message: str) -> None:
        """待っているステップを打ち切る。打ち切られたステップは message を理由に failed（phase）で記録される。"""
        with self._lock:
            self._cancel_phase = phase
            self._cancel_message = message
        self.stop.set()

    def timeout_message(self, index: int | None = None) -> str:
        where = "" if index is None else f" during step {index}"
        return f"the test exceeded its timeout of {self.test.timeout:g}s{where}"

    def run(self, index: int, planned: PlannedStep) -> bool:
        """1 ステップを実行して記録する。続けてよければ True、失敗して止めるべきなら False を返す。

        サーバーが死んでいれば ServerDied を送出する（記録は済ませてから）。
        """
        recorder = self.recorder
        if planned.skip_reason is not None:
            recorder.step_skipped(index, planned.skip_reason)
            return True
        logger.info("step %d (%s%s): %r", index, planned.phase, _position(planned), planned.step)
        recorder.step_started(index)
        started = time.monotonic()
        try:
            captured = self.runner.run_step(planned.step)
        except StepCancelled:
            # ハーネスが打ち切った（テストの期限切れなど）。ステップ自身の失敗ではないので死亡の判定はしない
            with self._lock:
                phase, message = self._cancel_phase, self._cancel_message or self.timeout_message(index)
            logger.error("step %d cancelled: %s", index, message)
            recorder.step_failed(index, _elapsed_ms(started), message, phase=phase)
            return False
        except ClientDiedError as error:
            logger.error("step %d: %s", index, error)
            recorder.step_failed(index, _elapsed_ms(started), str(error), phase="client")
            return False
        except ServerUnavailableError as error:
            recorder.step_failed(index, _elapsed_ms(started), str(error))
            raise ServerDied(str(error)) from error
        except KeyboardInterrupt:
            raise
        except Exception as error:  # noqa: BLE001 - ステップの失敗はどの例外でもテストの失敗として記録する
            if not isinstance(error, FukurouError):
                logger.exception("step %d raised an unexpected error", index)
            message = str(error) if isinstance(error, FukurouError) else f"{type(error).__name__}: {error}"
            # 入力や撮影の失敗（xdotool の失敗、スクリーンショットが出ない）は、クライアントが死んだ結果のことがある。
            # その場合はプラグインの失敗ではなくクライアントの死亡（error / client）として記録する。
            # 参加者の誰かが死んでいれば（別のレーンのプレイヤーでも）そう扱う: run_step も全員の生存を先に確かめる
            died = _dead_client(self.runner)
            if died is not None:
                logger.error("step %d: %s (after: %s)", index, died, message)
                recorder.step_failed(index, _elapsed_ms(started), str(died), phase="client")
                return False
            logger.error("step %d failed: %s", index, message)
            recorder.step_failed(index, _elapsed_ms(started), message)
            return False
        screenshot = self.record_screenshot(recorder, captured, index) if captured is not None else None
        recorder.step_passed(index, _elapsed_ms(started), screenshot=screenshot)
        return True


def _position(planned: PlannedStep) -> str:
    """ログ用のブロック内の位置（", parallel 1 lane 0, repeat 2/3" のような形）。"""
    parts = []
    if planned.parallel is not None:
        parts.append(f"parallel {planned.parallel.block} lane {planned.parallel.lane}")
    parts.extend(f"repeat {r.iteration}/{r.of}" for r in planned.repeat)
    return "".join(f", {part}" for part in parts)


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _dead_client(runner: ScenarioRunner) -> ClientDiedError | None:
    """テストの参加プレイヤーのクライアントが死んでいればその例外、全員生きていれば None。"""
    try:
        runner.check_players_alive()
    except ClientDiedError as error:
        return error
    return None
