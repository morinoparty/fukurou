"""ハーネスによる打ち切り（テストの期限切れなど）を、待ちのある処理へ伝えるための型と補助。

ScenarioRunner（ステップの振り分け）と PlayerSession（ウィンドウ探し・撮影の待ち）の両方が使うため、
どちらにも依存しない末端のモジュールに置く。
"""

import threading
import time

from fukurou.errors import FukurouError


class StepCancelled(FukurouError):
    """待ちの途中でハーネスが stop イベントを立てた（テストの期限切れなど）場合に送出する。ステップの失敗ではない。"""


def pause(seconds: float, stop: threading.Event | None = None) -> None:
    """seconds だけ待つ。stop があり、その間に立てば StepCancelled で抜ける。

    parallel のレーンは stop を渡して協調的に止まれるようにし、メインスレッドの処理（リセットの視点の
    正規化・失敗時の撮影）は stop 無しで呼んで、立ったままのイベントに影響されないようにする。
    """
    if stop is None:
        time.sleep(max(seconds, 0))
        return
    if stop.wait(max(seconds, 0)):
        raise StepCancelled("the step was cancelled by the harness")
