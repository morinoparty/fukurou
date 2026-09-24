"""ハーネスの打ち切り（stop イベント）を、プレイヤー操作の待ちが協調的に見ることのテスト。"""

from pathlib import Path
import threading
import time

import pytest

from fukurou.runner.cancellation import StepCancelled, pause
from fukurou.runner.player_session import PlayerSession, PlayerSessionError
from fukurou.runner import x11_input
from fukurou.runner.x11_input import InputError, MinecraftWindow


def stop_event(set_after: float | None = None) -> threading.Event:
    """立っている（または少し後に立つ）stop イベント。"""
    event = threading.Event()
    if set_after is None:
        event.set()
    else:
        threading.Timer(set_after, event.set).start()
    return event


def test_pause_sleeps_without_a_stop_and_is_cut_short_by_one():
    started = time.monotonic()
    pause(0.05)
    assert time.monotonic() - started >= 0.04
    started = time.monotonic()
    with pytest.raises(StepCancelled):
        pause(30, stop_event(set_after=0.05))
    assert time.monotonic() - started < 5


def test_window_wait_returns_at_the_stop_instead_of_its_timeout(monkeypatch):
    # ウィンドウが決して現れないディスプレイ
    monkeypatch.setattr(x11_input, "_xdotool", lambda *args, **kwargs: "")
    started = time.monotonic()
    with pytest.raises(StepCancelled):
        MinecraftWindow.wait_for(":99", timeout=900, stop=stop_event())
    assert time.monotonic() - started < 5
    # stop 無しの呼び出しは従来どおり上限時間まで探してから諦める
    with pytest.raises(InputError, match="did not appear"):
        MinecraftWindow.wait_for(":99", timeout=0.05)


class _Client:
    """screenshots_dir だけを持つ偽のクライアント。"""

    def __init__(self, root: Path):
        self.screenshots_dir = root


def test_screenshot_wait_returns_at_the_stop_instead_of_its_timeout(tmp_path):
    session = PlayerSession.__new__(PlayerSession)
    session.name = "Alice"
    session.client = _Client(tmp_path)
    started = time.monotonic()
    with pytest.raises(StepCancelled):
        session._wait_for_new_png(set(), timeout=15.0, stop=stop_event(set_after=0.05))
    assert time.monotonic() - started < 5
    # stop 無しでは上限時間まで PNG を待ってから失敗する
    with pytest.raises(PlayerSessionError, match="no new screenshot"):
        session._wait_for_new_png(set(), timeout=0.05)
