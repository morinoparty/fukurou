"""SuiteRun のライフサイクルのテスト。ゲーム側（サーバー・クライアント・ネットワーク）は偽物に差し替える。

偽物はファイルでつながる: クライアントの起動でサーバーログに "joined the game" が書かれ、chat でサーバーログと
クライアントログに行が増える。SuiteRun 側の LogWindow やログの回収は本物のまま動く。
"""

import json
from pathlib import Path
import threading
import time

import pytest

from fukurou.errors import InvalidInputError
from fukurou.result.model import ResultV2
from fukurou.run.isolation import PARKING_SPOT
from fukurou.run.options import RunOptions
from fukurou.run.suite_run import PaperJar, ResolvedVersion, SuiteRun
from fukurou.runner.cancellation import pause
from fukurou.runner.client import ClientDiedError
from fukurou.runner.x11_input import InputError
from fukurou.scenario import Selection
from fukurou.server.process import ServerUnavailableError

RELEASES = ["1.21.11", "1.21.10", "1.21.9", "1.21.6"]
VERSION = "1.21.11"
# fixture の Bob の F5 は press_key なので、失敗したテストの後は Bob が dirty になる
SUITE = """\
scenarios: [scenarios/*.json]
players: [{name: Alice, op: true}, {name: Bob}]
settle: 0
fixtures:
  arena:
    - {on: server, action: command, command: "fill 0 -60 0 0 -50 0 minecraft:stone"}
    - {on: Bob, action: press_key, key: F5}
beforeEach:
  - {on: server, action: command, command: "say next test"}
"""
WAIT = {"action": "wait", "seconds": 0.01}


# --- 偽物のゲーム ------------------------------------------------------------------


class FakeWorld:
    """偽のサーバーとクライアントをつなぐ。テストからの細工（死亡・中断・リセットの失敗）もここに置く。"""

    def __init__(self, root: Path):
        self.root = root
        self.server: "FakeServer | None" = None
        self.servers: list["FakeServer"] = []
        self.players: dict[str, "FakePlayer"] = {}
        # (プレイヤー名, 起動回数) がここにあれば、その起動は参加できずに死ぬ
        self.join_failures: set[tuple[str, int]] = set()
        # True なら fill が "Too many blocks" を返す
        self.reset_error = False
        # ここにある名前のプレイヤーへの tp / gamemode 等は "No player was found" を返す（サーバーから切断されている）
        self.disconnected: set[str] = set()
        # True なら失敗時の failure.png の撮影で KeyboardInterrupt（SIGTERM / Ctrl+C）が起きる
        self.interrupt_failure_shot = False
        # chat のたびに result.json を読んで残す（テストごとに書き直されていることの確認用）
        self.result_path: Path | None = None
        self.snapshots: list[dict] = []
        # 撮影にかける秒数と、(プレイヤー, 開始, 終了) の記録。parallel のレーンが本当に重なって走ったかの確認用
        self.screenshot_delay = 0.0
        self.screenshot_times: list[tuple[str, float, float]] = []
        # True なら撮影の待ちが stop イベントを見る（本物の PlayerSession と同じ）。False なら止められない待ち
        # （xdotool の中のような、猶予を過ぎても戻らないレーンの再現用）
        self.cooperative_screenshots = False


class FakeServer:
    """RCON の代わりにコマンドを記録し、それらしい応答を返す偽のサーバー。"""

    def __init__(self, log_path: Path, server_dir: Path, world: FakeWorld):
        self.log_path = log_path
        self.server_dir = server_dir
        self.world = world
        self.port = 25565
        self.running = False
        self.commands: list[str] = []

    def managed_properties(self, max_players: int) -> dict[str, str]:
        self.max_players = max_players
        return {"server-port": str(self.port), "max-players": str(max_players)}

    def start(self, timeout: float) -> None:
        self.running = True
        self.log("Done (1.0s)! For help, type \"help\"")

    def log(self, line: str) -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        with self.log_path.open("a", encoding="utf-8") as file:
            file.write(f"[12:00:00 INFO]: {line}\n")

    def command(self, command: str) -> str:
        if not self.running:
            raise ServerUnavailableError("server is not running (ConnectionRefusedError)")
        self.commands.append(command)
        self.log(f"rcon: {command}")
        words = command.split()
        if words[0] in ("tp", "gamemode", "clear", "op", "deop") and set(words[1:]) & self.world.disconnected:
            return "No player was found"
        if command.startswith("fill"):
            return "Too many blocks in the specified area (40000 > 32768)" if self.world.reset_error else "Successfully filled 100 blocks"
        if command.startswith("kill"):
            return "No entity was found"
        if command.startswith("deop"):
            return "Nothing changed. The player is not an operator"
        if command.startswith("effect clear"):
            return "Target either has no effects to remove, or has effects that cannot be removed"
        return f"ok: {command}"

    def check_alive(self) -> None:
        if not self.running:
            raise ServerUnavailableError("server exited with code 1")

    def is_running(self) -> bool:
        return self.running

    def stop(self) -> None:
        if self.running:
            self.log("Stopping server")
        self.running = False

    def die(self) -> None:
        self.running = False
        self.log("java.lang.OutOfMemoryError")


class FakePlayer:
    """Xvfb とクライアントの代わりにログファイルだけを書く偽のプレイヤー。"""

    def __init__(self, name: str, world: FakeWorld):
        self.name = name
        self.world = world
        client_dir = world.root / "clients" / name
        self.latest_log = client_dir / "logs" / "latest.log"
        self.launch_log = client_dir / "launch.log"
        self.crash_reports_dir = client_dir / "crash-reports"
        self.window_timeout = 900.0
        self.launches = 0
        self.running = False
        self.alive = False
        self.installed = False
        self.perspective = 0
        self.keys: list[str] = []
        self.normalized = 0

    def install(self, timeout: float) -> None:
        self.installed = True

    def start(self, server_port: int) -> None:
        self.launches += 1
        self.running = True
        self.alive = (self.name, self.launches) not in self.world.join_failures
        # 起動し直せばサーバーへ参加し直す
        self.world.disconnected.discard(self.name)
        self.perspective = 0
        # 本物と同じく起動時に latest.log がローテートする
        self.latest_log.parent.mkdir(parents=True, exist_ok=True)
        if self.latest_log.exists():
            self.latest_log.rename(self.latest_log.with_name(f"launch-{self.launches - 1}.log"))
        self.log(f"Connecting to 127.0.0.1, {server_port}")
        if self.alive:
            self.world.server.log(f"{self.name} joined the game")
        else:
            self.launch_log.parent.mkdir(parents=True, exist_ok=True)
            self.launch_log.write_text("crashed\n", encoding="utf-8")

    def log(self, line: str) -> None:
        with self.latest_log.open("a", encoding="utf-8") as file:
            file.write(f"[12:00:00] [Render thread/INFO]: {line}\n")

    def press_key(self, keysym: str, stop=None) -> None:
        self.check_alive()
        self.keys.append(keysym)
        if keysym == "F5":
            self.perspective += 1

    def type_text(self, text: str, stop=None) -> None:
        self.check_alive()

    def chat(self, text: str, stop=None) -> None:
        self.check_alive()
        if self.world.result_path is not None and self.world.result_path.exists():
            self.world.snapshots.append(json.loads(self.world.result_path.read_text(encoding="utf-8")))
        # テストからの細工: 特別なテキストでクライアントやサーバーを殺す・中断する
        if text == "die":
            self.alive = False
            return
        if text == "die-loudly":
            # 入力の途中で死ぬ: 本物では xdotool の失敗（InputError / RuntimeError）として現れ、ClientDiedError にはならない
            self.alive = False
            raise RuntimeError("xdotool key failed: window does not exist")
        if text == "stuck":
            # T でチャット欄を開いた後に入力が失敗した（チャット欄が開いたまま残る）
            raise InputError("xdotool type failed")
        if text == "disconnect":
            # サーバーから切断される（キック等）。プロセスは生きたまま
            self.world.disconnected.add(self.name)
            return
        if text == "server-die":
            self.world.server.die()
            return
        if text == "interrupt":
            raise KeyboardInterrupt
        if text.startswith("/"):
            self.world.server.log(f"{self.name} issued server command: {text}")
        self.log(f"[CHAT] <{self.name}> {text}")

    def normalize_view(self) -> None:
        self.normalized += 1
        self.perspective = 0

    def take_screenshot(self, destination: Path, stop=None) -> tuple[int, int]:
        self.check_alive()
        if self.world.interrupt_failure_shot and destination.name == "failure.png":
            raise KeyboardInterrupt
        started = time.monotonic()
        if self.world.cooperative_screenshots:
            pause(self.world.screenshot_delay, stop)
        else:
            time.sleep(self.world.screenshot_delay)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"\x89PNG fake")
        self.world.screenshot_times.append((self.name, started, time.monotonic()))
        return 1280, 720

    def check_alive(self) -> None:
        if not self.alive:
            raise ClientDiedError(f"{self.name}: client exited with code 1")

    def stop(self) -> None:
        self.running = False


class FakePlatform:
    """ネットワークに触らない Platform。"""

    def __init__(self, world: FakeWorld):
        self.world = world
        self.resolve_error: Exception | None = None

    def resolve(self, spec: str) -> ResolvedVersion:
        if self.resolve_error is not None:
            raise self.resolve_error
        return ResolvedVersion(version=VERSION, releases=RELEASES, minecraft_java=21)

    def java_major(self, java: Path) -> int | None:
        return 21

    def fetch_dependency(self, dependency, cache_dir: Path):
        raise AssertionError("dependencies are not used in these tests")

    def paper(self, version: str, build: int | None, cache_dir: Path) -> PaperJar:
        cache_dir.mkdir(parents=True, exist_ok=True)
        jar = cache_dir / "paper.jar"
        jar.write_bytes(b"jar")
        return PaperJar(path=jar, build=130, channel="STABLE")

    def new_server(self, java: Path, jar: Path, server_dir: Path, log_path: Path, bundler_dir: Path) -> FakeServer:
        server = FakeServer(log_path, server_dir, self.world)
        self.world.server = server
        self.world.servers.append(server)
        return server

    def new_player(self, name: str, version: str) -> FakePlayer:
        player = FakePlayer(name, self.world)
        self.world.players[name] = player
        return player


# --- 補助 -------------------------------------------------------------------------


@pytest.fixture
def world(tmp_path) -> FakeWorld:
    return FakeWorld(tmp_path / "work")


def write_suite(tmp_path: Path, scenarios: dict[str, dict], suite: str | None = SUITE) -> Selection:
    """game-test/ にスイートとシナリオを書き、それを指す Selection を返す。"""
    directory = tmp_path / "game-test"
    (directory / "scenarios").mkdir(parents=True)
    for test_id, data in scenarios.items():
        (directory / "scenarios" / f"{test_id}.json").write_text(json.dumps(data), encoding="utf-8")
    if suite is None:
        return Selection(scenario_globs=[str(directory / "scenarios" / "*.json")])
    (directory / "fukurou.yml").write_text(suite, encoding="utf-8")
    return Selection(suite=directory / "fukurou.yml")


def make_options(tmp_path: Path, selection: Selection, **overrides) -> RunOptions:
    fields = dict(
        minecraft_version=VERSION,
        selection=selection,
        fail_fast=False,
        accept_eula=True,
        plugins_dir=tmp_path,
        plugins="",
        dependencies="",
        server_properties="",
        server_files=None,
        server_build=None,
        paper_channel="stable",
        java=tmp_path / "java",
        client_java=None,
        work_dir=tmp_path / "work",
        out_dir=tmp_path / "out",
    )
    return RunOptions(**(fields | overrides))


def run(tmp_path: Path, world: FakeWorld, scenarios: dict[str, dict], suite: str | None = SUITE, platform=None, **overrides):
    """スイートを実行し、(終了コード, result.json の内容) を返す。"""
    selection = write_suite(tmp_path, scenarios, suite)
    world.result_path = tmp_path / "out" / "result.json"
    code = SuiteRun(make_options(tmp_path, selection, **overrides), platform=platform or FakePlatform(world)).execute()
    return code, read_result(tmp_path)


def read_result(tmp_path: Path) -> dict:
    data = json.loads((tmp_path / "out" / "result.json").read_text(encoding="utf-8"))
    # 書いたものは必ず契約に合う
    ResultV2.model_validate(data)
    return data


def statuses(result: dict) -> dict[str, str]:
    return {test["id"]: test["status"] for test in result["tests"]}


def by_id(result: dict, test_id: str) -> dict:
    return next(test for test in result["tests"] if test["id"] == test_id)


def check_log_ranges(tmp_path: Path, result: dict, test: dict) -> None:
    """logRanges のキーは回収したログで、行番号はそのファイルの範囲内にある（test_contract と同じ規則）。"""
    log_paths = {log["path"] for session in result["sessions"] for log in session["logs"]}
    assert test["logRanges"], test["id"]
    for path, log_range in test["logRanges"].items():
        assert path in log_paths, path
        lines = (tmp_path / "out" / path).read_text(encoding="utf-8").splitlines()
        assert 1 <= log_range["from"] <= log_range["to"] <= len(lines), (path, log_range, len(lines))


def chat(player: str, text: str) -> dict:
    return {"on": player, "action": "chat", "text": text}


def wait_for(text: str, timeout: float = 1) -> dict:
    return {"on": "server", "action": "wait_for_log", "pattern": text, "timeout": timeout}


def shot(player: str, name: str) -> dict:
    return {"on": player, "action": "screenshot", "name": name}


# --- 入力の誤り（ネットワークより前に exit 2）-----------------------------------------


def test_run_without_eula_writes_an_error_result(tmp_path, world):
    code, result = run(tmp_path, world, {"a": {"steps": [WAIT]}}, accept_eula=False)
    assert code == 2
    assert result["status"] == "error"
    assert result["failure"]["phase"] == "setup" and "Minecraft EULA" in result["failure"]["message"]
    assert result["id"] == f"paper-{VERSION}" and result["tests"] == [] and result["suite"] is None
    assert result["logs"] == [{"kind": "harness", "path": "logs/harness.log", "player": None}]
    assert "Minecraft EULA" in (tmp_path / "out" / "logs" / "harness.log").read_text()
    assert world.servers == []


def test_run_with_an_invalid_scenario_exits_2(tmp_path, world):
    code, result = run(tmp_path, world, {"broken": {"steps": []}})
    assert code == 2
    assert "broken: invalid scenario" in result["failure"]["message"]


def test_run_accepts_only_a_single_version(tmp_path, world):
    code, result = run(tmp_path, world, {"a": {"steps": [WAIT]}}, minecraft_version="1.21.6-")
    assert code == 2
    assert "single Minecraft version" in result["failure"]["message"]
    # 発見までは済んでいるのでスタブは書かれている
    assert statuses(result) == {"a": "skipped"}


def test_run_refuses_to_clean_directories_it_did_not_create(tmp_path, world):
    (tmp_path / "out" / "logs").mkdir(parents=True)
    (tmp_path / "out" / "logs" / "mine.log").write_text("keep")
    code, result = run(tmp_path, world, {"a": {"steps": [WAIT]}})
    assert code == 2
    assert (tmp_path / "out" / "logs" / "mine.log").read_text() == "keep"
    assert "refusing to delete logs" in result["failure"]["message"]


def test_stub_result_is_written_right_after_discovery(tmp_path, world):
    platform = FakePlatform(world)
    platform.resolve_error = InvalidInputError("no such version")
    scenarios = {"a": {"steps": [WAIT]}, "b": {"players": [{"name": "Carol"}], "steps": [WAIT]}}
    code, result = run(tmp_path, world, scenarios, platform=platform)
    assert code == 2
    assert result["failure"] == {"phase": "setup", "message": "no such version"}
    assert [(t["status"], t["skipReason"], t["session"]) for t in result["tests"]] == [("skipped", "not run", None)] * 2
    assert result["suite"]["source"] == f"file:{(tmp_path / 'game-test' / 'fukurou.yml').as_posix()}"
    assert result["suite"]["arena"] == {"size": 32, "height": 24} and result["suite"]["settle"] == 0
    # 和集合はスイートの宣言順、テストにしか居ないプレイヤーはその後
    assert [player["name"] for player in result["players"]] == ["Alice", "Bob", "Carol"]
    assert result["sessions"] == [] and result["summary"]["skipped"] == 2


# --- スイートの実行 -----------------------------------------------------------------


@pytest.fixture
def full_suite() -> dict[str, dict]:
    """passed / failed / versions で skipped / 1 人だけ / fresh-server を 1 つずつ含むスイート。"""
    return {
        "a-pass": {
            "tags": ["stamps"],
            "use": ["arena"],
            "steps": [
                chat("Alice", "/st :a:"),
                wait_for("Alice issued server command: /st :a:"),
                shot("Alice", "after"),
                shot("Bob", "after"),
                {"on": "Alice", "action": "assert_no_log", "pattern": "Unknown command"},
            ],
        },
        "b-fail": {
            "use": ["arena"],
            "steps": [
                {"on": "Alice", "action": "press_key", "key": "e"},
                chat("Alice", "/st :b:"),
                wait_for("never appears", timeout=0.1),
                shot("Alice", "after"),
            ],
        },
        "c-versions": {"versions": "1.21.6-1.21.10", "steps": [WAIT]},
        "d-fresh": {"isolation": "fresh-server", "players": [{"name": "Alice"}], "steps": [WAIT, shot("Alice", "fresh")]},
        "e-alone": {"use": ["arena"], "players": [{"name": "Alice", "op": True}], "steps": [chat("Alice", "/st :e:"), shot("Alice", "alone")]},
    }


def test_a_suite_runs_in_one_session_with_a_fresh_server_at_the_end(tmp_path, world, full_suite):
    code, result = run(tmp_path, world, full_suite)
    assert code == 1
    assert result["status"] == "failed" and result["failure"] is None
    assert statuses(result) == {"a-pass": "passed", "b-fail": "failed", "c-versions": "skipped", "e-alone": "passed", "d-fresh": "passed"}
    assert result["summary"] == {"total": 5, "passed": 3, "failed": 1, "error": 0, "skipped": 1}
    assert [test["order"] for test in result["tests"]] == [0, 1, 2, 3, 4]
    assert result["minecraft"] == {"version": VERSION, "server": "paper", "build": 130, "channel": "STABLE"}
    assert result["java"] == {"server": 21}
    assert result["selection"] == {"tests": [], "tags": [], "isolation": None, "failFast": False}
    assert result["players"] == [{"name": "Alice", "joined": True}, {"name": "Bob", "joined": True}]

    # セッション: 0 は initial で 3 テスト、1 は fresh-server で d-fresh だけ。全員が両方に参加する
    initial, fresh = result["sessions"]
    assert (initial["index"], initial["kind"], initial["players"], initial["tests"]) == (0, "initial", ["Alice", "Bob"], ["a-pass", "b-fail", "e-alone"])
    assert (fresh["index"], fresh["kind"], fresh["players"], fresh["tests"]) == (1, "fresh-server", ["Alice", "Bob"], ["d-fresh"])
    assert initial["finishedAt"] is not None and fresh["failure"] is None
    # b-fail で Alice（press_key）と Bob（fixture の F5）が dirty になり、e-alone の前に Alice だけ再起動される
    assert [log["path"] for log in initial["logs"]] == [
        "logs/sessions/0/clients/Alice.log",
        "logs/sessions/0/server.log",
        "logs/sessions/0/clients/Alice.2.log",
        "logs/sessions/0/clients/Bob.log",
    ]
    assert [log["path"] for log in fresh["logs"]] == [
        "logs/sessions/1/server.log",
        "logs/sessions/1/clients/Alice.log",
        "logs/sessions/1/clients/Bob.log",
    ]
    for log in initial["logs"] + fresh["logs"] + result["logs"]:
        assert (tmp_path / "out" / log["path"]).is_file(), log["path"]
    assert world.players["Alice"].launches == 3 and world.players["Bob"].launches == 2
    assert len(world.servers) == 2 and not any(server.running for server in world.servers)
    assert world.servers[0].max_players == 2

    # a-pass: beforeEach → fixture（Bob の F5 も実行）→ テスト。スクリーンショットは tests/<id>/ の下
    a = by_id(result, "a-pass")
    assert a["session"] == 0 and a["reset"]["error"] is None and a["reset"]["durationMs"] is not None
    assert [(s["phase"], s["fixture"], s["status"]) for s in a["steps"]] == [
        ("beforeEach", None, "passed"),
        ("fixture", "arena", "passed"),
        ("fixture", "arena", "passed"),
        *[("test", None, "passed")] * 5,
    ]
    assert [(s["player"], s["name"], s["path"], s["stepIndex"]) for s in a["screenshots"]] == [
        ("Alice", "after", "tests/a-pass/screenshots/Alice/after.png", 5),
        ("Bob", "after", "tests/a-pass/screenshots/Bob/after.png", 6),
    ]
    assert a["steps"][5]["screenshot"] == "tests/a-pass/screenshots/Alice/after.png"
    assert (tmp_path / "out" / "tests" / "a-pass" / "screenshots" / "Bob" / "after.png").is_file()
    assert a["players"] == [{"name": "Alice", "op": True}, {"name": "Bob", "op": False}]
    # Bob のクライアントログにはこのテストの間に行が増えないので、キーに現れない
    assert set(a["logRanges"]) == {"logs/sessions/0/server.log", "logs/sessions/0/clients/Alice.log"}
    check_log_ranges(tmp_path, result, a)

    # b-fail: wait_for_log で失敗、残りは skipped、両プレイヤーの failure.png
    b = by_id(result, "b-fail")
    assert b["failure"]["phase"] == "scenario" and b["failure"]["stepIndex"] == 5
    assert "did not match 'never appears'" in b["failure"]["message"]
    assert [s["status"] for s in b["steps"]] == ["passed"] * 5 + ["failed", "skipped"]
    assert sorted((s["player"], s["name"]) for s in b["screenshots"]) == [("Alice", "failure"), ("Bob", "failure")]
    assert all(s["stepIndex"] == 5 for s in b["screenshots"])
    check_log_ranges(tmp_path, result, b)
    # 2 つ目の /st は 1 つ目の行に一致しない（ウィンドウがテストごとに進む）
    assert b["logRanges"]["logs/sessions/0/server.log"]["from"] > a["logRanges"]["logs/sessions/0/server.log"]["to"]

    # c-versions: 走らず、理由付き
    c = by_id(result, "c-versions")
    assert c["skipReason"] == f"versions: 1.21.6-1.21.10 does not include {VERSION}"
    assert c["session"] is None and c["reset"] is None and c["steps"] == [] and c["logRanges"] is None

    # e-alone: Bob は駐機、fixture の Bob の F5 は理由付きで skipped、Alice の再起動後のログが範囲になる
    e = by_id(result, "e-alone")
    assert e["steps"][2] == {**e["steps"][2], "status": "skipped", "error": "player Bob is not in this test", "on": "Bob"}
    assert set(e["logRanges"]) == {"logs/sessions/0/server.log", "logs/sessions/0/clients/Alice.2.log"}
    check_log_ranges(tmp_path, result, e)
    commands = world.servers[0].commands
    assert "tp Bob 0.5 -60 200.5" in commands and commands.count("op Alice") == 3 and commands.count("deop Bob") == 2
    # リセットは退避（gamemode → tp）→ fill の順で、コマンドの前に走る
    first_reset = commands.index("gamemode survival Alice")
    assert commands[first_reset + 1].startswith("tp Alice ") and commands[first_reset + 4].startswith("fill -16 -60 -16")
    assert commands.index("say next test") > first_reset
    # 視点の正規化は参加プレイヤーにだけ行う（Alice: a, b, e, d / Bob: a, b）
    assert world.players["Alice"].normalized == 4 and world.players["Bob"].normalized == 2

    # d-fresh: 新しいセッションで実行され、スクリーンショットも新しいセッションのログ範囲も持つ
    d = by_id(result, "d-fresh")
    assert d["session"] == 1 and d["screenshots"][0]["path"] == "tests/d-fresh/screenshots/Alice/fresh.png"
    assert set(d["logRanges"]) == {"logs/sessions/1/server.log"}
    check_log_ranges(tmp_path, result, d)

    # result.json はテストごとに書き直される: b-fail の chat の時点で a-pass は passed、b-fail は not run
    during_b = next(s for s in world.snapshots if statuses(s)["a-pass"] == "passed")
    assert statuses(during_b)["b-fail"] == "skipped" and by_id(during_b, "b-fail")["skipReason"] == "not run"
    assert result["startedAt"] <= result["finishedAt"] and result["durationMs"] >= 0


def test_scenario_files_without_a_suite_use_default_reset_settings(tmp_path, world):
    scenario = {"players": [{"name": "Alice", "op": True}], "steps": [chat("Alice", "/hi"), shot("Alice", "hi")]}
    code, result = run(tmp_path, world, {"smoke": scenario}, suite=None)
    assert code == 0 and result["status"] == "passed"
    assert result["suite"] == {"source": None, "sha256": None, "isolation": "reset", "settle": 2, "gamemode": "survival", "arena": {"size": 32, "height": 24}}
    assert by_id(result, "smoke")["steps"][0]["phase"] == "test"
    # スイートが無くてもリセットは走る
    assert "fill -16 -60 -16 15 -37 15 minecraft:air" in world.servers[0].commands


def test_a_first_fresh_server_test_reuses_the_untouched_initial_session(tmp_path, world):
    code, result = run(tmp_path, world, {"only": {"isolation": "fresh-server", "steps": [WAIT]}})
    assert code == 0
    assert len(result["sessions"]) == 1 and result["sessions"][0]["kind"] == "initial"
    assert by_id(result, "only")["session"] == 0 and len(world.servers) == 1


def test_log_matching_starts_after_the_reset_but_log_ranges_include_it(tmp_path, world):
    """リセットの RCON の echo（tp Alice など）に wait_for_log が一致してはいけない。logRanges には残る。"""
    scenarios = {
        "a": {"steps": [wait_for("rcon: tp Alice", timeout=0.2)]},
        "b": {"steps": [{"on": "server", "action": "assert_no_log", "pattern": "rcon: gamemode"}]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert statuses(result) == {"a": "failed", "b": "passed"}
    a = by_id(result, "a")
    assert a["failure"]["phase"] == "scenario" and "did not match" in a["failure"]["message"]
    # logRanges はリセットの最初のコマンドから始まる
    for test in (a, by_id(result, "b")):
        log_range = test["logRanges"]["logs/sessions/0/server.log"]
        lines = (tmp_path / "out" / "logs" / "sessions" / "0" / "server.log").read_text(encoding="utf-8").splitlines()
        assert "rcon: gamemode survival Alice" in lines[log_range["from"] - 1]
        check_log_ranges(tmp_path, result, test)


def test_a_failing_command_step_fails_the_test(tmp_path, world):
    """fixture の fill が黙って失敗すると以後のステップが違うワールドで走るので、エラーの応答はステップの失敗にする。"""
    scenarios = {"a": {"use": ["arena"], "steps": [shot("Alice", "after")]}}
    # リセットの fill は通し、fixture の fill だけ失敗させる
    real_command = FakeServer.command

    def command(self, text):
        self.world.reset_error = text.startswith("fill 0 -60 0")
        return real_command(self, text)

    FakeServer.command = command
    try:
        code, result = run(tmp_path, world, scenarios)
    finally:
        FakeServer.command = real_command
    assert code == 1
    a = by_id(result, "a")
    assert a["status"] == "failed" and a["failure"]["phase"] == "fixture" and a["reset"]["error"] is None
    assert a["failure"]["message"].startswith("command failed: Too many blocks")
    assert [s["status"] for s in a["steps"]] == ["passed", "failed", "skipped", "skipped"]


# --- インフラの失敗 -----------------------------------------------------------------


def test_a_reset_error_makes_the_test_an_error(tmp_path, world):
    world.reset_error = True
    code, result = run(tmp_path, world, {"a": {"steps": [chat("Alice", "/x")]}})
    assert code == 1 and result["status"] == "failed" and result["failure"] is None
    a = by_id(result, "a")
    assert a["status"] == "error" and a["failure"]["phase"] == "reset"
    assert a["reset"]["error"].startswith("fill -16 -60 -16 15 -37 15 minecraft:air: Too many blocks")
    assert [s["status"] for s in a["steps"]] == ["skipped", "skipped"]
    # リセットで失敗したので、それより後のコマンド（time set noon）は送られない
    assert "time set noon" not in world.servers[0].commands
    assert a["logRanges"]["logs/sessions/0/server.log"]["to"] >= a["logRanges"]["logs/sessions/0/server.log"]["from"]


def test_a_dead_client_is_an_error_and_is_relaunched_for_the_next_test(tmp_path, world):
    scenarios = {
        "a": {"steps": [chat("Alice", "die"), shot("Alice", "never")]},
        "b": {"steps": [chat("Alice", "/x"), shot("Alice", "ok")]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert statuses(result) == {"a": "error", "b": "passed"}
    a = by_id(result, "a")
    assert a["failure"]["phase"] == "client" and a["failure"]["stepIndex"] == 2
    assert "Alice: client exited with code 1" in a["failure"]["message"]
    assert [s["status"] for s in a["steps"]] == ["passed", "passed", "failed"]
    assert a["steps"][2]["error"] == a["failure"]["message"]
    # 死んだクライアントの failure.png は撮れないが、Bob のは撮れる
    assert [s["player"] for s in a["screenshots"]] == ["Bob"]
    assert world.players["Alice"].launches == 2
    assert "logs/sessions/0/clients/Alice.2.log" in by_id(result, "b")["logRanges"]
    assert result["failure"] is None and result["status"] == "failed"


def test_a_client_that_dies_inside_an_input_step_is_a_client_error(tmp_path, world):
    """xdotool の失敗として現れた死亡も、プラグインの失敗（scenario）ではなくクライアントの死亡（client）にする。"""
    scenarios = {
        "a": {"steps": [chat("Alice", "die-loudly"), WAIT]},
        "b": {"steps": [chat("Alice", "/x")]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert statuses(result) == {"a": "error", "b": "passed"}
    a = by_id(result, "a")
    assert a["failure"]["phase"] == "client" and a["failure"]["stepIndex"] == 1
    assert "Alice: client exited with code 1" in a["failure"]["message"]
    assert world.players["Alice"].launches == 2


def test_a_chat_that_fails_midway_leaves_the_player_dirty(tmp_path, world):
    """T でチャット欄を開いた後に失敗した chat は画面を残すので、次のテストの前にそのプレイヤーを起動し直す。"""
    scenarios = {
        "a": {"steps": [chat("Alice", "stuck")]},
        "b": {"steps": [chat("Alice", "/x")]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert statuses(result) == {"a": "failed", "b": "passed"}
    assert by_id(result, "a")["failure"]["message"] == "xdotool type failed"
    assert world.players["Alice"].launches == 2 and world.players["Bob"].launches == 1


def test_a_disconnected_player_fails_the_reset_and_is_relaunched(tmp_path, world):
    """プロセスは生きていてもサーバーに居ないプレイヤー: リセットが "No player was found" で失敗し、次のテストの前に起動し直す。"""
    scenarios = {
        "a": {"steps": [chat("Bob", "disconnect")]},
        "b": {"steps": [chat("Alice", "/x")]},
        # Bob が参加しない間は駐機の tp が "No player was found" でも失敗にしない
        "c": {"players": [{"name": "Alice", "op": True}], "steps": [chat("Alice", "/y")]},
        "d": {"steps": [chat("Bob", "/z")]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert statuses(result) == {"a": "passed", "b": "error", "c": "passed", "d": "passed"}
    b = by_id(result, "b")
    assert b["failure"]["phase"] == "reset" and b["reset"]["error"] == "gamemode survival Bob: No player was found"
    assert [s["status"] for s in b["steps"]] == ["skipped", "skipped"]
    # Bob は次に参加する d の前に起動し直される。Alice はそのまま
    assert world.players["Alice"].launches == 1 and world.players["Bob"].launches == 2
    assert f"tp Bob {PARKING_SPOT}" in world.servers[0].commands
    assert "logs/sessions/0/clients/Bob.2.log" in by_id(result, "d")["logRanges"]


def test_an_interrupt_during_the_failure_screenshots_abandons_the_test(tmp_path, world):
    world.interrupt_failure_shot = True
    scenarios = {"a": {"steps": [wait_for("never", timeout=0.1)]}, "b": {"steps": [WAIT]}}
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert result["failure"]["phase"] == "interrupted"
    assert statuses(result) == {"a": "skipped", "b": "skipped"}
    a = by_id(result, "a")
    assert (a["skipReason"], a["session"], a["logRanges"], a["durationMs"]) == ("interrupted", None, None, None)
    assert result["sessions"][0]["tests"] == []


def test_a_failed_relaunch_stops_the_run(tmp_path, world):
    world.join_failures.add(("Alice", 2))
    scenarios = {
        "a": {"steps": [chat("Alice", "die")]},
        "b": {"steps": [chat("Alice", "/x")]},
        "c": {"players": [{"name": "Bob"}], "steps": [WAIT]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert result["status"] == "error"
    assert result["failure"]["phase"] == "client-join" and result["failure"]["message"].startswith("client relaunch failed: Alice")
    assert statuses(result) == {"a": "passed", "b": "skipped", "c": "skipped"}
    assert by_id(result, "b")["skipReason"] == by_id(result, "c")["skipReason"] == "client relaunch failed: Alice"
    # 1 回しか試さない。死んだ起動のログも回収され、クライアントは止められる
    assert world.players["Alice"].launches == 2 and not world.players["Alice"].running
    session = result["sessions"][0]
    assert session["finishedAt"] is not None
    assert "logs/sessions/0/clients/Alice.2.log" in [log["path"] for log in session["logs"]]


def test_a_dead_server_fails_the_run_and_skips_the_rest(tmp_path, world):
    scenarios = {
        "a": {"steps": [WAIT]},
        "b": {"steps": [chat("Alice", "server-die"), {"on": "server", "action": "command", "command": "list"}, WAIT]},
        "c": {"steps": [WAIT]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert result["status"] == "error"
    assert result["failure"]["phase"] == "server" and result["failure"]["message"].startswith("server died during b")
    assert statuses(result) == {"a": "passed", "b": "failed", "c": "skipped"}
    b = by_id(result, "b")
    assert [s["status"] for s in b["steps"]] == ["passed", "passed", "failed", "skipped"]
    assert b["failure"]["phase"] == "scenario" and "server is not running" in b["failure"]["message"]
    assert by_id(result, "c")["skipReason"] == "server died during b"
    session = result["sessions"][0]
    assert session["failure"] is not None and session["tests"] == ["a", "b"]
    assert [log["kind"] for log in session["logs"]] == ["server", "client", "client"]


def test_a_server_that_dies_after_the_last_step_still_fails_the_run(tmp_path, world):
    scenarios = {"a": {"steps": [chat("Alice", "server-die")]}, "b": {"steps": [WAIT]}}
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    # a は全ステップが passed だが、終了時点でサーバーが死んでいるので run は error、b は走らない
    assert statuses(result) == {"a": "passed", "b": "skipped"}
    assert result["failure"]["phase"] == "server" and by_id(result, "b")["skipReason"] == "server died during a"


def test_a_soft_timeout_is_checked_between_steps(tmp_path, world):
    scenarios = {"slow": {"timeout": 0.05, "steps": [{"action": "wait", "seconds": 0.1}, WAIT, WAIT]}}
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    slow = by_id(result, "slow")
    assert slow["status"] == "error" and slow["failure"]["phase"] == "timeout" and slow["failure"]["stepIndex"] is None
    assert [s["status"] for s in slow["steps"]] == ["passed", "passed", "skipped", "skipped"]


def test_fail_fast_skips_the_remaining_tests(tmp_path, world):
    scenarios = {
        "a": {"steps": [wait_for("never", timeout=0.1)]},
        "b": {"steps": [WAIT]},
        "c": {"isolation": "fresh-server", "steps": [WAIT]},
    }
    code, result = run(tmp_path, world, scenarios, fail_fast=True)
    assert code == 1
    assert statuses(result) == {"a": "failed", "b": "skipped", "c": "skipped"}
    assert by_id(result, "b")["skipReason"] == by_id(result, "c")["skipReason"] == "fail-fast"
    assert result["selection"]["failFast"] is True and result["status"] == "failed"
    assert len(world.servers) == 1


def test_an_interrupt_mid_suite_still_writes_the_result_and_collects_logs(tmp_path, world):
    scenarios = {
        "a": {"steps": [chat("Alice", "/x"), shot("Alice", "x")]},
        "b": {"steps": [chat("Alice", "/y"), chat("Alice", "interrupt"), WAIT]},
        "c": {"steps": [WAIT]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1
    assert result["status"] == "error"
    assert result["failure"] == {"phase": "interrupted", "message": "interrupted during server"}
    assert statuses(result) == {"a": "passed", "b": "skipped", "c": "skipped"}
    b = by_id(result, "b")
    assert (b["skipReason"], b["session"], b["steps"], b["reset"]) == ("interrupted", None, [], None)
    assert by_id(result, "c")["skipReason"] == "not run"
    # 後片付けは行われ、ログも回収される。走り切れなかった b はセッションの実行済み一覧にも載らない
    session = result["sessions"][0]
    assert session["finishedAt"] is not None and len(session["logs"]) == 3 and session["tests"] == ["a"]
    assert not any(server.running for server in world.servers)
    assert not world.players["Alice"].running


def test_a_server_start_failure_is_a_run_error(tmp_path, world):
    class BrokenPlatform(FakePlatform):
        def new_server(self, *args, **kwargs):
            server = super().new_server(*args, **kwargs)
            server.start = lambda timeout: (_ for _ in ()).throw(ServerUnavailableError("server exited with code 1 before it was ready"))
            return server

    code, result = run(tmp_path, world, {"a": {"steps": [WAIT]}}, platform=BrokenPlatform(world))
    assert code == 1
    assert result["failure"]["phase"] == "server-start"
    assert statuses(result) == {"a": "skipped"} and by_id(result, "a")["skipReason"] == "not run"
    assert result["sessions"][0]["finishedAt"] is not None


# --- parallel / repeat --------------------------------------------------------------


def parallel(*steps: dict) -> dict:
    return {"action": "parallel", "steps": list(steps)}


def repeat(times: int, *steps: dict, **fields) -> dict:
    return {"action": "repeat", "times": times, "steps": list(steps), **fields}


def lane_threads() -> list[str]:
    return [thread.name for thread in threading.enumerate() if thread.name.startswith("lane-")]


def test_parallel_screenshots_of_two_players_overlap_in_time(tmp_path, world):
    world.screenshot_delay = 0.3
    scenarios = {"a": {"steps": [{"on": ["Alice", "Bob"], "action": "screenshot", "name": "both"}, WAIT]}}
    code, result = run(tmp_path, world, scenarios)
    assert code == 0 and statuses(result) == {"a": "passed"}
    a = by_id(result, "a")
    # 展開後: beforeEach, Alice の撮影（lane 0）, Bob の撮影（lane 1）, wait
    assert [(s["on"], s["action"], s["parallel"], s["repeat"]) for s in a["steps"]] == [
        ("server", "command", None, None),
        ("Alice", "screenshot", {"block": 0, "lane": 0}, None),
        ("Bob", "screenshot", {"block": 0, "lane": 1}, None),
        (None, "wait", None, None),
    ]
    assert [s["status"] for s in a["steps"]] == ["passed"] * 4
    assert sorted((s["player"], s["name"], s["stepIndex"]) for s in a["screenshots"]) == [("Alice", "both", 1), ("Bob", "both", 2)]
    # 2 人の撮影の時間が重なっている（逐次なら Bob の開始は Alice の終了より後になる）
    (_, alice_start, alice_end), (_, bob_start, bob_end) = sorted(world.screenshot_times)
    assert alice_start < bob_end and bob_start < alice_end
    for step in a["steps"][1:3]:
        assert step["startedAt"] <= step["finishedAt"] and step["durationMs"] >= 250
        assert step["startedAt"].endswith("Z") and len(step["startedAt"]) == len("2026-09-24T03:02:14.120Z")
    assert lane_threads() == []


def test_a_failing_child_fails_the_block_while_its_sibling_completes(tmp_path, world):
    scenarios = {
        "a": {
            "steps": [
                parallel(
                    wait_for("never appears", timeout=0.1),
                    repeat(2, chat("Bob", "/b ${i}"), {"action": "wait", "seconds": 0.2}),
                ),
                shot("Alice", "after"),
            ],
        },
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1 and statuses(result) == {"a": "failed"}
    a = by_id(result, "a")
    # レーン 0 は失敗、レーン 1（repeat）は最後まで走って passed、ブロックの後の撮影は skipped
    assert [(s["status"], s["parallel"], s["repeat"]) for s in a["steps"][1:]] == [
        ("failed", {"block": 0, "lane": 0}, None),
        ("passed", {"block": 0, "lane": 1}, [{"block": 0, "iteration": 1, "of": 2}]),
        ("passed", {"block": 0, "lane": 1}, [{"block": 0, "iteration": 1, "of": 2}]),
        ("passed", {"block": 0, "lane": 1}, [{"block": 0, "iteration": 2, "of": 2}]),
        ("passed", {"block": 0, "lane": 1}, [{"block": 0, "iteration": 2, "of": 2}]),
        ("skipped", None, None),
    ]
    assert a["failure"]["phase"] == "scenario" and a["failure"]["stepIndex"] == 1
    assert "did not match 'never appears'" in a["failure"]["message"]
    assert a["steps"][6]["error"] is None and a["steps"][6]["startedAt"] is None
    # 兄弟は失敗の後も最後まで動いた（chat が 2 回、プレースホルダーは番号に置き換わる）
    bob_log = world.players["Bob"].latest_log.read_text(encoding="utf-8")
    assert "<Bob> /b 1" in bob_log and "<Bob> /b 2" in bob_log
    assert sorted(s["player"] for s in a["screenshots"]) == ["Alice", "Bob"]
    assert lane_threads() == []


def test_wait_for_log_in_two_lanes_shares_the_server_log_window(tmp_path, world):
    scenarios = {
        "a": {
            "steps": [
                parallel(
                    {"action": "wait_for_log", "on": "server", "pattern": "Alice issued server command: /a", "timeout": 2},
                    {"action": "wait_for_log", "on": "server", "pattern": "Bob issued server command: /b", "timeout": 2},
                    repeat(1, {"action": "wait", "seconds": 0.2}, chat("Alice", "/a")),
                    repeat(1, {"action": "wait", "seconds": 0.3}, chat("Bob", "/b")),
                ),
                {"on": "server", "action": "assert_no_log", "pattern": "Unknown command"},
            ],
        },
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 0 and statuses(result) == {"a": "passed"}
    a = by_id(result, "a")
    assert [s["status"] for s in a["steps"]] == ["passed"] * 8
    assert [s["parallel"]["lane"] for s in a["steps"][1:7]] == [0, 1, 2, 2, 3, 3]
    # 待ちのレーンはチャットのレーンより後に終わる（同じウィンドウを別スレッドが読んでいた）
    waits = [s["finishedAt"] for s in a["steps"][1:3]]
    chats = [s["finishedAt"] for s in (a["steps"][4], a["steps"][6])]
    assert max(chats) <= max(waits)


def test_repeat_iterations_are_recorded_with_their_numbers(tmp_path, world):
    scenarios = {
        "a": {
            "steps": [
                repeat(3, chat("Alice", "/st ${i}"), repeat(2, shot("Alice", "shot-${i}-${j}"), **{"as": "j"})),
            ],
        },
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 0 and statuses(result) == {"a": "passed"}
    a = by_id(result, "a")
    steps = a["steps"][1:]
    assert [s["label"] for s in steps] == [
        "/st 1", "shot-1-1", "shot-1-2", "/st 2", "shot-2-1", "shot-2-2", "/st 3", "shot-3-1", "shot-3-2",
    ]
    assert steps[0]["repeat"] == [{"block": 0, "iteration": 1, "of": 3}]
    # 内側の repeat は外側の繰り返しごとに別のブロック番号になる
    assert steps[2]["repeat"] == [{"block": 0, "iteration": 1, "of": 3}, {"block": 1, "iteration": 2, "of": 2}]
    assert steps[7]["repeat"] == [{"block": 0, "iteration": 3, "of": 3}, {"block": 3, "iteration": 1, "of": 2}]
    assert all(s["parallel"] is None and s["status"] == "passed" for s in steps)
    assert "<Alice> /st 3" in world.players["Alice"].latest_log.read_text(encoding="utf-8")
    assert [s["name"] for s in a["screenshots"]] == ["shot-1-1", "shot-1-2", "shot-2-1", "shot-2-2", "shot-3-1", "shot-3-2"]


def test_a_soft_timeout_during_a_parallel_block_stops_the_lanes(tmp_path, world):
    scenarios = {
        "slow": {
            "timeout": 1,
            "steps": [
                parallel(
                    {"action": "wait", "seconds": 30},
                    wait_for("never", timeout=30),
                    repeat(2, {"action": "wait", "seconds": 0.05}),
                ),
                WAIT,
            ],
        },
        "next": {"steps": [WAIT]},
    }
    started = time.monotonic()
    code, result = run(tmp_path, world, scenarios)
    assert time.monotonic() - started < 5
    assert code == 1 and statuses(result) == {"slow": "error", "next": "passed"}
    slow = by_id(result, "slow")
    assert slow["failure"]["phase"] == "timeout" and slow["failure"]["stepIndex"] in (1, 2)
    assert "exceeded its timeout of 1s" in slow["failure"]["message"]
    # 待っていた 2 本のレーンは打ち切られて failed、短い repeat のレーンは終わっていて passed、後続は skipped
    assert [s["status"] for s in slow["steps"]] == ["passed", "failed", "failed", "passed", "passed", "skipped"]
    assert all("exceeded its timeout" in s["error"] for s in slow["steps"][1:3])
    assert slow["durationMs"] < 5000
    # スレッドは残っていない
    assert lane_threads() == []


def test_a_parallel_fixture_skips_the_copies_for_absent_players(tmp_path, world):
    suite = """\
scenarios: [scenarios/*.json]
players: [{name: Alice, op: true}, {name: Bob}]
settle: 0
fixtures:
  greet:
    - action: parallel
      steps:
        - {on: [Alice, Bob], action: chat, text: "/hello"}
        - {on: server, action: command, command: "say hi"}
beforeEach:
  - {on: server, action: command, command: "say next test"}
"""
    scenarios = {
        "both": {"use": ["greet"], "steps": [WAIT]},
        "alone": {"use": ["greet"], "players": [{"name": "Alice", "op": True}], "steps": [WAIT]},
    }
    code, result = run(tmp_path, world, scenarios, suite=suite)
    assert code == 0 and statuses(result) == {"both": "passed", "alone": "passed"}
    both = by_id(result, "both")
    assert [(s["on"], s["status"], s["parallel"]) for s in both["steps"][1:4]] == [
        ("Alice", "passed", {"block": 0, "lane": 0}),
        ("Bob", "passed", {"block": 0, "lane": 1}),
        ("server", "passed", {"block": 0, "lane": 2}),
    ]
    alone = by_id(result, "alone")
    assert [(s["on"], s["status"], s["error"]) for s in alone["steps"][1:4]] == [
        ("Alice", "passed", None),
        ("Bob", "skipped", "player Bob is not in this test"),
        ("server", "passed", None),
    ]
    assert alone["steps"][2]["parallel"] == {"block": 0, "lane": 1} and alone["steps"][2]["startedAt"] is None
    assert "<Alice> /hello" in world.players["Alice"].latest_log.read_text(encoding="utf-8")
    assert world.players["Alice"].latest_log.read_text(encoding="utf-8").count("/hello") == 2
    assert world.players["Bob"].latest_log.read_text(encoding="utf-8").count("/hello") == 1


def test_a_server_death_in_one_lane_cancels_the_waiting_siblings(tmp_path, world):
    scenarios = {
        "a": {
            "steps": [
                parallel(
                    repeat(1, chat("Alice", "server-die"), {"on": "server", "action": "command", "command": "list"}),
                    {"action": "wait", "seconds": 30},
                ),
            ],
        },
        "b": {"steps": [WAIT]},
    }
    started = time.monotonic()
    code, result = run(tmp_path, world, scenarios)
    assert time.monotonic() - started < 5
    assert code == 1 and result["status"] == "error" and result["failure"]["phase"] == "server"
    assert statuses(result) == {"a": "failed", "b": "skipped"}
    a = by_id(result, "a")
    assert [s["status"] for s in a["steps"]] == ["passed", "passed", "failed", "failed"]
    assert a["failure"]["stepIndex"] == 2 and "server is not running" in a["failure"]["message"]
    # 待っていた兄弟は期限切れではなくサーバーの死亡で打ち切られた
    assert a["steps"][3]["error"].startswith("cancelled: server is not running")
    assert lane_threads() == []


def test_an_interrupt_inside_a_lane_abandons_the_test(tmp_path, world):
    scenarios = {
        "a": {"steps": [parallel(chat("Alice", "interrupt"), {"action": "wait", "seconds": 30})]},
        "b": {"steps": [WAIT]},
    }
    started = time.monotonic()
    code, result = run(tmp_path, world, scenarios)
    assert time.monotonic() - started < 5
    assert code == 1 and result["failure"]["phase"] == "interrupted"
    assert statuses(result) == {"a": "skipped", "b": "skipped"}
    assert by_id(result, "a")["skipReason"] == "interrupted" and by_id(result, "a")["steps"] == []
    assert lane_threads() == []


def test_a_real_parallel_result_passes_through_the_site_builder(tmp_path, world):
    """ランナーが実際に書いた parallel / repeat の result.json（タイムアウトを含む）がビューア用のサイトにそのまま載る。

    ビューアのフィクスチャは手書きなので、TestRecorder の出力の形（durationMs が null で finishedAt がある
    打ち切られたレーン、時刻の無い skipped）をここで build_manifest に通して確かめる。
    """
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ui" / "scripts"))
    import build_manifest

    scenarios = {
        "burst": {
            "steps": [
                repeat(2, chat("Alice", "/st ${i}"), {"on": ["Alice", "Bob"], "action": "screenshot", "name": "shot-${i}"}),
            ],
        },
        "slow": {
            "timeout": 1,
            "steps": [parallel({"action": "wait", "seconds": 30}, repeat(2, {"action": "wait", "seconds": 0.05})), WAIT],
        },
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1 and statuses(result) == {"burst": "passed", "slow": "error"}

    # CI で artifact をダウンロードしたときと同じ <artifact 名>/ の配置にする
    artifacts = tmp_path / "artifacts"
    (artifacts / "fukurou-paper-1.21.11").parent.mkdir()
    (tmp_path / "out").rename(artifacts / "fukurou-paper-1.21.11")
    viewer = tmp_path / "viewer"
    viewer.mkdir()
    (viewer / "index.html").write_text("<!doctype html>", encoding="utf-8")
    site = tmp_path / "site"
    argv = ["--artifacts-dir", str(artifacts), "--out", str(site), "--viewer-dir", str(viewer), "--title", "Test"]
    assert build_manifest.main(argv, env={}) == 0

    manifest = json.loads((site / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["warnings"] == []
    (site_run,) = manifest["runs"]
    # サイトに載った result もランナーの契約のまま（ビューアの ResultV2 と同じ形）
    ResultV2.model_validate(site_run["result"])
    assert site_run["result"] == result
    burst = by_id(site_run["result"], "burst")
    assert [(s["on"], s["label"], s["parallel"], [r["iteration"] for r in s["repeat"] or []]) for s in burst["steps"][1:]] == [
        ("Alice", "/st 1", None, [1]),
        ("Alice", "shot-1", {"block": 0, "lane": 0}, [1]),
        ("Bob", "shot-1", {"block": 0, "lane": 1}, [1]),
        ("Alice", "/st 2", None, [2]),
        ("Alice", "shot-2", {"block": 1, "lane": 0}, [2]),
        ("Bob", "shot-2", {"block": 1, "lane": 1}, [2]),
    ]
    for shot_entry in burst["screenshots"]:
        assert (site / site_run["base"] / shot_entry["path"]).is_file()
    slow = by_id(site_run["result"], "slow")
    assert [s["status"] for s in slow["steps"]] == ["passed", "failed", "passed", "passed", "skipped"]
    # 打ち切られたレーンは時刻を持ち（バーを描ける）、走らなかったステップは持たない
    cut = slow["steps"][1]
    assert cut["parallel"] == {"block": 0, "lane": 0} and cut["startedAt"] and cut["finishedAt"]
    assert slow["steps"][4]["startedAt"] is None and slow["steps"][4]["finishedAt"] is None
    assert manifest["summary"]["tests"] == {"total": 2, "passed": 1, "failed": 0, "error": 1, "skipped": 0}


def join_lane_threads(timeout: float = 10.0) -> None:
    """置き去りにしたレーンが終わるまで待つ（止められない待ちの中のレーンは run の終了後も残る）。"""
    for thread in threading.enumerate():
        if thread.name.startswith("lane-"):
            thread.join(timeout)


def test_a_lane_left_behind_after_the_grace_strands_its_player(tmp_path, world, monkeypatch, caplog):
    """猶予を過ぎても戻らないレーンのプレイヤーは、失敗時の撮影で触らず、次のテストの前に起動し直す。"""
    import functools

    from fukurou.run import suite_run
    from fukurou.run.parallel import run_parallel_block

    monkeypatch.setattr(suite_run, "run_parallel_block", functools.partial(run_parallel_block, grace=0.1))
    # 止められない待ち（xdotool の中のような）が期限 + 猶予より長く続く
    world.screenshot_delay = 1.5
    scenarios = {
        "a-slow": {"timeout": 0.3, "steps": [parallel(shot("Bob", "late"), {"action": "wait", "seconds": 30})]},
        "b-next": {"steps": [chat("Bob", "/again"), WAIT]},
    }
    code, result = run(tmp_path, world, scenarios)
    assert code == 1 and statuses(result) == {"a-slow": "error", "b-next": "passed"}
    slow = by_id(result, "a-slow")
    assert slow["failure"]["phase"] == "timeout"
    # 置き去りにした撮影のステップは timeout で記録され、戻っていないので durationMs は無い
    bob_shot = slow["steps"][1]
    assert bob_shot["on"] == "Bob" and bob_shot["status"] == "failed" and bob_shot["durationMs"] is None
    assert "exceeded its timeout" in bob_shot["error"]
    # 失敗時の撮影は Alice だけ（Bob の画面はレーンがまだ使っているかもしれない）
    assert [(s["player"], s["name"]) for s in slow["screenshots"]] == [("Alice", "failure")]
    # Bob は次のテストの前に起動し直され、Alice はそのまま
    assert world.players["Bob"].launches == 2 and world.players["Alice"].launches == 1
    assert "Bob: relaunching the client (a lane of the previous test was still driving the client" in caplog.text
    # レーンが遅れて終わっても、書き出した結果は変わらない（遅れた撮影は載らない）
    join_lane_threads()
    assert lane_threads() == []
    assert [(s["player"], s["name"]) for s in by_id(read_result(tmp_path), "a-slow")["screenshots"]] == [("Alice", "failure")]


def test_a_cooperative_screenshot_lane_returns_at_the_timeout(tmp_path, world):
    """撮影の待ちが stop を見るので、期限切れのレーンは猶予を待たずに戻り、失敗時の撮影も全員分撮れる。"""
    world.cooperative_screenshots = True
    world.screenshot_delay = 1.0
    scenarios = {
        "a-slow": {"timeout": 0.3, "steps": [parallel(shot("Bob", "late"), {"action": "wait", "seconds": 30})]},
        "b-next": {"steps": [chat("Bob", "/again"), WAIT]},
    }
    started = time.monotonic()
    code, result = run(tmp_path, world, scenarios)
    assert time.monotonic() - started < 5
    assert code == 1 and statuses(result) == {"a-slow": "error", "b-next": "passed"}
    slow = by_id(result, "a-slow")
    bob_shot = slow["steps"][1]
    assert bob_shot["status"] == "failed" and "exceeded its timeout" in bob_shot["error"]
    # 撮影の待ち（1 秒）を待ち切らずに戻った
    assert bob_shot["durationMs"] is not None and bob_shot["durationMs"] < 900
    # 失敗時の撮影は stop を渡さないので、立ったままのイベントに打ち切られずに 2 人とも撮れている
    assert sorted((s["player"], s["name"]) for s in slow["screenshots"]) == [("Alice", "failure"), ("Bob", "failure")]
    # レーンは置き去りになっていないので、Bob は起動し直さない
    assert world.players["Bob"].launches == 1
    assert lane_threads() == []


def test_real_platform_applies_the_paper_channel(tmp_path, monkeypatch, caplog):
    """RealPlatform は --paper-channel でバージョンとビルドを絞り、--server-build の決め打ちは警告だけにする。"""
    from fukurou import mojang
    from fukurou.paper import PaperBuild, PaperProject
    from fukurou.run import suite_run

    manifest = mojang.MojangManifest.model_validate({"versions": [{"id": "26.3", "type": "release"}]})
    monkeypatch.setattr(suite_run, "fetch_manifest", lambda: manifest)
    monkeypatch.setattr(suite_run, "fetch_project", lambda: PaperProject(versions={"26.3": ["26.3"]}))
    monkeypatch.setattr(suite_run, "has_accepted_build", lambda version, channel: channel == "alpha")
    monkeypatch.setattr(suite_run, "fetch_java_major", lambda version, manifest: 25)
    download = {"name": "paper.jar", "checksums": {"sha256": "0"}, "url": "u"}
    alpha = PaperBuild.model_validate({"id": 40, "channel": "ALPHA", "downloads": {"server:default": download}})
    monkeypatch.setattr(suite_run, "resolve_build", lambda version, build, channel: alpha)
    monkeypatch.setattr(suite_run, "cached_download", lambda url, path, sha256: path)
    selection = Selection(scenario_text="{}")

    # 既定の stable では ALPHA しかないバージョンを選べない
    stable = suite_run.RealPlatform(make_options(tmp_path, selection, minecraft_version="26.3"), tmp_path)
    with pytest.raises(InvalidInputError, match="--paper-channel"):
        stable.resolve("26.3")
    # alpha なら選べて、記録されるチャンネルは ALPHA
    allowed = suite_run.RealPlatform(make_options(tmp_path, selection, paper_channel="alpha"), tmp_path)
    assert allowed.resolve("26.3").version == "26.3"
    assert allowed.paper("26.3", None, tmp_path).channel == "ALPHA"
    assert "less stable" not in caplog.text
    # --server-build の決め打ちは stable でも使い、しきい値を下回ることを警告する
    pinned = suite_run.RealPlatform(make_options(tmp_path, selection, server_build=40), tmp_path)
    assert pinned.resolve("26.3").version == "26.3"
    assert pinned.paper("26.3", 40, tmp_path).build == 40
    assert "less stable than --paper-channel stable" in caplog.text
