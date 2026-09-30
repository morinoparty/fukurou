"""system_deps.SystemDepsWaiter（バックグラウンドの apt-get の完了待ち）を確認するテスト。"""

import pytest

from fukurou.runner.system_deps import EXIT_CODE_FILE, LOG_FILE, STATUS_ENV, SystemDepsError, SystemDepsWaiter


class FakeClock:
    """sleep で進む時計。待ちのテストを実時間で待たないようにする。"""

    def __init__(self):
        self.now = 0.0
        self.on_sleep = None

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds
        if self.on_sleep is not None:
            self.on_sleep(self.now)


def waiter(status_dir, clock: FakeClock, timeout: float = 10.0) -> SystemDepsWaiter:
    return SystemDepsWaiter(status_dir, timeout=timeout, poll=1.0, sleep=clock.sleep, clock=clock)


def test_no_status_dir_does_not_wait():
    # 手元での実行や skip-system-deps では環境変数が無いか空になる
    assert SystemDepsWaiter.from_env({}).status_dir is None
    assert SystemDepsWaiter.from_env({STATUS_ENV: ""}).status_dir is None
    SystemDepsWaiter.from_env({}).wait()


def test_waits_until_the_exit_code_appears(tmp_path, capsys):
    (tmp_path / LOG_FILE).write_text("Setting up xvfb ...\n")
    clock = FakeClock()
    # 3 秒後にインストールが終わる
    clock.on_sleep = lambda now: now >= 3 and (tmp_path / EXIT_CODE_FILE).write_text("0\n")
    deps = waiter(tmp_path, clock)
    deps.wait()
    assert clock.now == 3
    # 成功時のログはアクションの後続のステップが表示するので、ここでは出さない
    assert "::group::" not in capsys.readouterr().out
    # 2 回目（再起動・fresh-server）は待たない
    deps.wait()
    assert clock.now == 3


def test_failed_install_raises_with_the_log(tmp_path, capsys):
    (tmp_path / LOG_FILE).write_text("E: Unable to locate package xvfb\n")
    (tmp_path / EXIT_CODE_FILE).write_text("100\n")
    with pytest.raises(SystemDepsError, match="exit code 100"):
        waiter(tmp_path, FakeClock()).wait()
    out = capsys.readouterr().out
    assert out.startswith("::group::") and "E: Unable to locate package xvfb\n::endgroup::" in out


def test_timeout_raises_instead_of_hanging(tmp_path, capsys):
    (tmp_path / LOG_FILE).write_text("Get:1 http://archive.ubuntu.com ...\n")
    clock = FakeClock()
    with pytest.raises(SystemDepsError, match="within 10 seconds"):
        waiter(tmp_path, clock, timeout=10.0).wait()
    assert clock.now <= 11
    assert "Get:1 http://archive.ubuntu.com" in capsys.readouterr().out
