#!/usr/bin/env python3
"""`pnpm dev` 用のサンプルサイト（manifest.js、ログ、スクリーンショット）を生成する。

docs/design/v2-multi-test.md §5 の manifest v2 / result v2 の形に従い、ビューアの全ページと
全状態（passed / failed / error / skipped / not run / result 無し / v1 の artifact）が試せるデータを作る。
ログは logRanges と行番号が一致するように、テストごとに行を追加しながら組み立てる。

    python3 ui/viewer/dev/generate.py   # ui/viewer/dev/ を書き換える

標準ライブラリだけで動く。出力はコミットする（dev/ は pnpm dev の publicDir で、ビルドには含まれない）。
"""

from __future__ import annotations

import json
import shutil
import struct
import zlib
from pathlib import Path

DEV = Path(__file__).resolve().parent
RUNS = DEV / "runs"

PLAYERS = ["Alice", "Bob"]
SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

# テストの定義（スイート順）。players を省略したものはスイートの 2 人
TESTS = [
    {"id": "stamp-thinking-face", "tags": ["stamps"], "use": ["arena", "front-view"], "isolation": "reset"},
    {"id": "stamp-sleeping-face", "tags": ["stamps"], "use": ["arena", "front-view"], "isolation": "reset"},
    {"id": "stamp-legacy-format", "tags": ["stamps", "legacy"], "use": ["arena"], "isolation": "reset", "versions": "1.21.6-1.21.9", "players": ["Alice"]},
    {"id": "inventory-hud", "tags": ["hud"], "use": [], "isolation": "reset", "players": ["Bob"]},
    {"id": "fresh-world-join", "tags": ["slow"], "use": ["arena"], "isolation": "fresh-server"},
]

# 1 テストの「本体」のステップ（fixture の後）。screenshot は (on, name)
TEST_STEPS = {
    "stamp-thinking-face": [
        ("Alice", "chat", "/st :thinking-face:"),
        ("server", "wait_for_log", "Alice issued server command: /st :thinking-face:"),
        (None, "wait", "1s"),
        ("Alice", "screenshot", "after-stamp"),
        ("Bob", "screenshot", "after-stamp"),
        ("Alice", "assert_no_log", "[CHAT].*(Unknown command|Stamp not found)"),
    ],
    "stamp-sleeping-face": [
        ("Alice", "chat", "/st :sleeping-face:"),
        ("server", "wait_for_log", "Alice issued server command: /st :sleeping-face:"),
        (None, "wait", "1s"),
        ("Bob", "screenshot", "after-stamp"),
        ("Alice", "screenshot", "after-stamp"),
        ("Alice", "assert_no_log", "[CHAT].*(Unknown command|Stamp not found)"),
    ],
    "stamp-legacy-format": [
        ("Alice", "chat", "/stamp thinking_face"),
        ("server", "wait_for_log", "Alice issued server command: /stamp thinking_face"),
        ("Alice", "screenshot", "legacy"),
    ],
    "inventory-hud": [
        ("Bob", "press_key", "e"),
        (None, "wait", "1s"),
        ("Bob", "screenshot", "inventory"),
        ("Bob", "press_key", "Escape"),
    ],
    "fresh-world-join": [
        ("server", "command", "time set noon"),
        ("Alice", "screenshot", "spawn"),
        ("Bob", "screenshot", "spawn"),
    ],
}

FIXTURES = {
    "arena": [
        ("server", "command", "fill 0 -60 0 0 -50 0 minecraft:stone"),
        ("server", "command", "fill 0 -60 8 0 -50 8 minecraft:stone"),
        ("server", "command", "tp Alice 0.5 -49 0.5 0 30"),
        ("server", "command", "tp Bob 0.5 -49 8.5 180 -15"),
        (None, "wait", "2s"),
    ],
    "front-view": [("Alice", "press_key", "F5"), ("Alice", "press_key", "F5")],
}

# 各 run のシナリオ。outcome: テスト id → (status, 失敗するステップの添字（本体の中）, 失敗の段階)
RUNS_SPEC = [
    {
        "version": "1.21.6",
        "build": 30,
        "java": 21,
        "outcomes": {
            "stamp-thinking-face": ("passed", None, None),
            "stamp-sleeping-face": ("passed", None, None),
            "stamp-legacy-format": ("passed", None, None),
            "inventory-hud": ("passed", None, None),
            "fresh-world-join": ("passed", None, None),
        },
        # fresh-server の前に Alice を再起動した（dirty）ので、セッション 1 に Alice.2.log とクラッシュレポートがある
        "relaunch": "Alice",
    },
    {
        "version": "1.21.10",
        "build": 60,
        "java": 25,
        "outcomes": {
            "stamp-thinking-face": ("passed", None, None),
            # サーバーがこのテストの途中で死んだ。以降は skipped、run.failure.phase = server
            "stamp-sleeping-face": ("error", 1, "timeout"),
            "stamp-legacy-format": ("skipped", None, "versions"),
            "fresh-world-join": ("skipped", None, "server-died"),
        },
        "run_failure": {"phase": "server", "message": "the server stopped responding to RCON during stamp-sleeping-face (exit code 137)"},
        # inventory-hud はこの run では選択されなかった（--tag stamps,slow）→ グリッドでは not run
        "selection": {"tests": [], "tags": ["stamps", "slow"], "isolation": None, "failFast": False},
    },
    {
        "version": "1.21.11",
        "build": 130,
        # Paper の ALPHA ビルドで試した run（--paper-channel alpha）。ビューアでは "alpha" のバッジが付く
        "channel": "ALPHA",
        "java": 25,
        "outcomes": {
            "stamp-thinking-face": ("passed", None, None),
            "stamp-sleeping-face": ("failed", 1, "scenario"),
            "stamp-legacy-format": ("skipped", None, "versions"),
            "inventory-hud": ("error", 2, "client"),
            "fresh-world-join": ("passed", None, None),
        },
    },
]


def png(width: int, height: int, rgb: tuple[int, int, int], stripe: tuple[int, int, int]) -> bytes:
    """単色に斜めの縞を入れた小さな PNG を作る（画像ライブラリ無しで）。"""
    rows = []
    for y in range(height):
        row = bytearray([0])
        for x in range(width):
            row.extend(stripe if (x + y) % 12 < 3 else rgb)
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")


# テスト × プレイヤーごとに色を変えて、比較ページで区別できるようにする
COLORS = {
    "stamp-thinking-face": (88, 140, 90),
    "stamp-sleeping-face": (74, 110, 150),
    "stamp-legacy-format": (150, 120, 70),
    "inventory-hud": (120, 90, 150),
    "fresh-world-join": (90, 150, 150),
}
FAILURE_COLOR = (170, 70, 70)


class Log:
    """行番号を数えながら組み立てるログファイル。"""

    def __init__(self) -> None:
        self.lines: list[str] = []

    def add(self, *lines: str) -> None:
        self.lines.extend(lines)

    @property
    def count(self) -> int:
        return len(self.lines)

    def text(self) -> str:
        return "\n".join(self.lines) + "\n"


def stamp(seconds: int) -> str:
    return f"[03:{seconds // 60:02d}:{seconds % 60:02d}]"


def server_boot(log: Log, version: str, build: int, players: list[str]) -> int:
    """サーバー起動からプレイヤー参加までのログ。経過秒を返す。"""
    t = 0
    log.add(
        f"{stamp(t)} [ServerMain/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, name=PROD]",
        f"{stamp(t)} [ServerMain/INFO]: Loaded 1370 recipes",
        f"{stamp(t + 1)} [Server thread/INFO]: Starting minecraft server version {version}",
        f"{stamp(t + 1)} [Server thread/INFO]: This server is running Paper version {version}-{build}-main@abcdef0 (Git: abcdef0)",
        f"{stamp(t + 2)} [Server thread/WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!",
        f"{stamp(t + 2)} [Server thread/WARN]: The server will make no attempt to authenticate usernames.",
        f"{stamp(t + 3)} [Server thread/INFO]: [ProtocolLib] Loading server plugin ProtocolLib v5.4.0",
        f"{stamp(t + 3)} [Server thread/INFO]: [MineStamp] Loading server plugin MineStamp v1.4.0",
        f"{stamp(t + 4)} [Server thread/INFO]: Preparing level \"world\"",
    )
    for i in range(12):
        log.add(f"{stamp(t + 5 + i)} [Server thread/INFO]: Preparing spawn area: {min(100, i * 9)}%")
    t += 18
    log.add(
        f"{stamp(t)} [Server thread/INFO]: Time elapsed: 14032 ms",
        f"{stamp(t)} [Server thread/INFO]: [ProtocolLib] Enabling ProtocolLib v5.4.0",
        f"{stamp(t)} [Server thread/INFO]: [MineStamp] Enabling MineStamp v1.4.0",
        f"{stamp(t)} [Server thread/INFO]: [MineStamp] Loaded 42 stamps from storage",
        f"{stamp(t + 1)} [Server thread/INFO]: Done (19.512s)! For help, type \"help\"",
        f"{stamp(t + 1)} [Server thread/INFO]: RCON running on 0.0.0.0:25575",
    )
    for player in players:
        t += 25
        log.add(
            f"{stamp(t)} [User Authenticator #1/INFO]: UUID of player {player} is 00000000-0000-0000-0000-000000000000",
            f"{stamp(t)} [Server thread/INFO]: {player}[/127.0.0.1:50000] logged in with entity id 123 at ([world]0.5, -60.0, 0.5)",
            f"{stamp(t)} [Server thread/INFO]: {player} joined the game",
        )
    return t


def client_boot(log: Log, player: str, version: str) -> int:
    t = 0
    log.add(
        f"{stamp(t)} [Datafixer Bootstrap/INFO]: Building unoptimized datafixer",
        f"{stamp(t + 2)} [Render thread/INFO]: Environment: Environment[sessionHost=https://sessionserver.mojang.com, name=PROD]",
        f"{stamp(t + 2)} [Render thread/INFO]: Setting user: {player}",
        f"{stamp(t + 3)} [Render thread/INFO]: Backend library: LWJGL version 3.3.3",
        f"{stamp(t + 3)} [Render thread/WARN]: Failed to load render backend, falling back to software rendering (llvmpipe)",
        f"{stamp(t + 8)} [Render thread/INFO]: Reloading ResourceManager: vanilla",
        f"{stamp(t + 14)} [Render thread/INFO]: OpenAL initialized on device OpenAL Soft",
        f"{stamp(t + 16)} [Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas",
        f"{stamp(t + 20)} [Render thread/INFO]: Connecting to 127.0.0.1, 25565",
        f"{stamp(t + 22)} [Render thread/INFO]: [System] [CHAT] {player} joined the game",
        f"{stamp(t + 22)} [Render thread/INFO]: Loaded 4 advancements",
    )
    return t + 22


def reset_lines(server: Log, t: int, players: list[str]) -> int:
    """ハーネスのリセットのコマンド列（サーバー側の記録）。"""
    for p in players:
        server.add(f"{stamp(t)} [Server thread/INFO]: [Rcon: Set {p}'s game mode to Survival Mode]", f"{stamp(t)} [Server thread/INFO]: [Rcon: Teleported {p} to 0.5, -60.0, -8.5]")
    server.add(
        f"{stamp(t)} [Server thread/INFO]: [Rcon: Successfully filled 24576 blocks]",
        f"{stamp(t + 1)} [Server thread/INFO]: [Rcon: Successfully filled 1024 blocks]",
        f"{stamp(t + 1)} [Server thread/INFO]: [Rcon: Successfully filled 2048 blocks]",
        f"{stamp(t + 1)} [Server thread/INFO]: [Rcon: Set the time to 6000]",
        f"{stamp(t + 1)} [Server thread/INFO]: [Rcon: Set the weather to clear]",
    )
    for p in players:
        server.add(f"{stamp(t + 2)} [Server thread/INFO]: [Rcon: Cleared the inventory of {p}]", f"{stamp(t + 2)} [Server thread/INFO]: [Rcon: Set {p}'s experience to 0 points]")
    return t + 5


def build_run(spec: dict) -> tuple[dict, dict[str, bytes]]:
    """1 run 分の result.json と、ログ・画像のファイル（artifact 内のパス → 内容）を作る。"""
    version = spec["version"]
    run_id = f"paper-{version}"
    files: dict[str, bytes] = {}
    outcomes: dict = spec["outcomes"]
    selection = spec.get("selection") or {"tests": [], "tags": [], "isolation": None, "failFast": False}

    # 選択されたテスト（outcomes に無いテストはこの run では選択されなかった）を実行順（reset → fresh-server）に並べる
    selected = [t for t in TESTS if t["id"] in outcomes]
    ordered = [t for t in selected if t["isolation"] == "reset"] + [t for t in selected if t["isolation"] == "fresh-server"]

    harness = Log()
    harness.add(
        f"2026-09-24 03:00:00,000 INFO fukurou.cli: fukurou 2.0.0 run --minecraft-version {version} --suite game-test/fukurou.yml",
        "2026-09-24 03:00:00,120 INFO fukurou.scenario.discovery: discovered %d tests" % len(selected),
        "2026-09-24 03:00:00,130 INFO fukurou.result.recorder: wrote result.json (all tests not run)",
        f"2026-09-24 03:00:03,000 INFO fukurou.server.paper: resolved Paper {version} build {spec['build']}",
        "2026-09-24 03:00:48,000 INFO fukurou.server.process: server is ready (RCON up)",
    )

    sessions: list[dict] = []
    tests: list[dict] = []
    order = 0
    run_failure = spec.get("run_failure")
    session_index = -1
    server: Log | None = None
    clients: dict[str, Log] = {}
    # プレイヤー → このセッションで latest.log を保存するファイル名（再起動後は <player>.2.log）
    client_file: dict[str, str] = {}
    t = 0

    def open_session(kind: str) -> None:
        nonlocal session_index, server, clients, client_file, t
        session_index += 1
        server = Log()
        clients = {p: Log() for p in PLAYERS}
        client_file = {p: f"{p}.log" for p in PLAYERS}
        t = server_boot(server, version, spec["build"], PLAYERS)
        for p in PLAYERS:
            client_boot(clients[p], p, version)
        sessions.append(
            {
                "index": session_index,
                "kind": kind,
                "startedAt": f"2026-09-24T03:0{session_index + 1}:00Z",
                "finishedAt": None,
                "players": list(PLAYERS),
                "tests": [],
                "logs": [{"kind": "server", "path": f"logs/sessions/{session_index}/server.log", "player": None}]
                + [{"kind": "client", "path": f"logs/sessions/{session_index}/clients/{p}.log", "player": p} for p in PLAYERS],
                "failure": None,
            }
        )
        harness.add(f"2026-09-24 03:0{session_index + 1}:00,000 INFO fukurou.run.session: session {session_index} ({kind}) started, joined {', '.join(PLAYERS)}")

    def close_session() -> None:
        assert server is not None
        sessions[-1]["finishedAt"] = f"2026-09-24T03:0{session_index + 1}:59Z"
        files[f"logs/sessions/{session_index}/server.log"] = server.text().encode()
        for p, log in clients.items():
            files[f"logs/sessions/{session_index}/clients/{client_file[p]}"] = log.text().encode()

    open_session("initial")
    for test in ordered:
        tid = test["id"]
        status, fail_at, phase = outcomes[tid]
        players = test.get("players", PLAYERS)
        record = {
            "id": tid,
            "name": tid,
            "order": order,
            "source": f"file:game-test/scenarios/{tid}.json",
            "sha256": SHA,
            "tags": test["tags"],
            "isolation": test["isolation"],
            "timeout": 600,
            "versions": test.get("versions"),
            "session": None,
            "status": status,
            "skipReason": None,
            "players": [{"name": p, "op": p == "Alice"} for p in players],
            "reset": None,
            "steps": [],
            "failure": None,
            "screenshots": [],
            "logRanges": None,
            "startedAt": None,
            "durationMs": None,
        }
        order += 1
        if status == "skipped":
            record["skipReason"] = {
                "versions": f"versions: {test.get('versions')} does not include {version}",
                "server-died": f"server died during stamp-sleeping-face",
            }[phase]
            tests.append(record)
            continue

        assert server is not None
        if test["isolation"] == "fresh-server":
            # fresh-server の切り替えの前に、dirty になったクライアントの再起動（クラッシュレポート付き）を 1 回入れる
            if spec.get("relaunch"):
                p = spec["relaunch"]
                crashed = clients[p]
                crashed.add(
                    f"{stamp(t)} [Render thread/ERROR]: Unreported exception thrown!",
                    "java.lang.IllegalStateException: Rendering screen while another screen is open",
                    "\tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1234)",
                    "\tat net.minecraft.client.Minecraft.run(Minecraft.java:800)",
                )
                crash_path = f"crash-reports/{p}/crash-2026-09-24_03.01.58-client.txt"
                files[crash_path] = (
                    "---- Minecraft Crash Report ----\n// Sample crash report for the dev site\n\n"
                    "Time: 2026-09-24 03:01:58\nDescription: Unexpected error\n\n"
                    "java.lang.IllegalStateException: Rendering screen while another screen is open\n"
                    "\tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1234)\n"
                    "\tat net.minecraft.client.Minecraft.run(Minecraft.java:800)\n"
                    "Caused by: java.lang.NullPointerException: screen\n"
                    "\tat net.minecraft.client.gui.screens.Screen.render(Screen.java:99)\n"
                    "\t... 4 more\n\n-- System Details --\n\tMinecraft Version: " + version + "\n"
                ).encode()
                sessions[-1]["logs"].append({"kind": "crash", "path": crash_path, "player": p})
                # 死んだクライアントの latest.log は <player>.log として回収済み。再起動後の latest.log は <player>.2.log
                files[f"logs/sessions/{session_index}/clients/{p}.log"] = crashed.text().encode()
                relaunched = Log()
                client_boot(relaunched, p, version)
                clients[p] = relaunched
                client_file[p] = f"{p}.2.log"
                sessions[-1]["logs"].append({"kind": "client", "path": f"logs/sessions/{session_index}/clients/{p}.2.log", "player": p})
                harness.add(f"2026-09-24 03:01:58,000 WARN fukurou.run.session: client {p} died; relaunching (attempt 1)")
            close_session()
            open_session("fresh-server")

        assert server is not None
        record["session"] = session_index
        sessions[-1]["tests"].append(tid)
        record["startedAt"] = f"2026-09-24T03:0{session_index + 1}:{10 + order * 5:02d}Z"
        # ログウィンドウの開始位置（この行の次からがこのテスト）
        marks = {"server": server.count, **{p: clients[p].count for p in PLAYERS}}
        t = reset_lines(server, t, players)
        record["reset"] = {"durationMs": 3400, "error": None}
        harness.add(f"2026-09-24 03:0{session_index + 1}:{10 + order * 5:02d},000 INFO fukurou.run.suite_run: test {tid}: reset done in 3.4s")

        # ステップ列: fixtures → 本体
        planned: list[tuple[str, str | None, tuple]] = []
        for name in test["use"]:
            planned += [("fixture", name, step) for step in FIXTURES[name]]
        body_start = len(planned)
        planned += [("test", None, step) for step in TEST_STEPS[tid]]
        failing_index = None if fail_at is None else body_start + fail_at
        duration = 0
        for index, (phase_name, fixture, (on, action, label)) in enumerate(planned):
            step = {"index": index, "phase": phase_name, "fixture": fixture, "on": on, "action": action, "label": label, "status": "passed", "durationMs": 20, "error": None, "screenshot": None}
            # fixture がこのテストに居ないプレイヤーを対象にしていればスキップ
            if on not in (None, "server") and on not in players:
                step["status"] = "skipped"
                step["error"] = f"player {on} is not in this test"
                step["durationMs"] = None
            elif failing_index is not None and index > failing_index:
                step["status"] = "skipped"
                step["durationMs"] = None
            elif failing_index is not None and index == failing_index:
                step["status"] = "failed"
                step["durationMs"] = 10004
                messages = {
                    "scenario": f"server log did not match '{label}' within 10s",
                    "timeout": "the server stopped responding: RCON connection reset by peer",
                    "client": f"client {on} exited with code 134 (SIGABRT) while pressing {label}",
                }
                step["error"] = messages[phase]
                record["failure"] = {"phase": phase, "message": messages[phase], "stepIndex": index}
                for p in players:
                    if p not in clients:
                        continue
                    path = f"tests/{tid}/screenshots/{p}/failure.png"
                    files[path] = png(96, 54, FAILURE_COLOR, (200, 120, 120))
                    record["screenshots"].append({"player": p, "name": "failure", "path": path, "width": 96, "height": 54, "stepIndex": index})
                    clients[p].add(f"{stamp(t)} [Render thread/INFO]: Saved screenshot as failure.png")
                if phase == "client":
                    clients[on].add(f"{stamp(t)} [Render thread/FATAL]: The game crashed: SIGABRT")
                if phase == "timeout":
                    server.add(f"{stamp(t)} [Server thread/ERROR]: Encountered an unexpected exception", "java.lang.OutOfMemoryError: Java heap space", "\tat dev.nikomaru.minestamp.render.StampRenderer.render(StampRenderer.java:88)")
            else:
                if action == "command":
                    server.add(f"{stamp(t)} [Server thread/INFO]: [Rcon: {label.split(' ')[0]} ok]")
                elif action == "chat":
                    server.add(f"{stamp(t)} [Server thread/INFO]: {on} issued server command: {label}")
                    for p in players:
                        clients[p].add(f"{stamp(t)} [Render thread/INFO]: [System] [CHAT] <{on}> {label}")
                    server.add(f"{stamp(t)} [Server thread/INFO]: [MineStamp] {on} sent stamp {label.split(' ')[-1]}")
                elif action == "wait_for_log":
                    step["durationMs"] = 350
                elif action == "wait":
                    step["durationMs"] = int(label[:-1]) * 1000
                    t += int(label[:-1])
                elif action == "screenshot":
                    step["durationMs"] = 1900
                    path = f"tests/{tid}/screenshots/{on}/{label}.png"
                    base = COLORS[tid]
                    shade = tuple(min(255, c + (40 if on == "Bob" else 0)) for c in base)
                    files[path] = png(96, 54, shade, (230, 230, 200))
                    record["screenshots"].append({"player": on, "name": label, "path": path, "width": 96, "height": 54, "stepIndex": index})
                    clients[on].add(f"{stamp(t)} [Render thread/INFO]: Saved screenshot as {label}.png")
                elif action == "press_key":
                    clients[on].add(f"{stamp(t)} [Render thread/INFO]: [fukurou] key {label}")
                t += 1
            duration += step["durationMs"] or 0
            record["steps"].append(step)
        record["durationMs"] = duration + 3400
        # ログの範囲（1 始まり両端含み）。参加プレイヤーのクライアントログだけ
        ranges = {}
        if server.count > marks["server"]:
            ranges[f"logs/sessions/{session_index}/server.log"] = {"from": marks["server"] + 1, "to": server.count}
        for p in players:
            if clients[p].count > marks[p]:
                ranges[f"logs/sessions/{session_index}/clients/{client_file[p]}"] = {"from": marks[p] + 1, "to": clients[p].count}
        record["logRanges"] = ranges
        harness.add(f"2026-09-24 03:0{session_index + 1}:{12 + order * 5:02d},000 {'INFO' if status == 'passed' else 'ERROR'} fukurou.run.suite_run: test {tid}: {status}")
        tests.append(record)
        if run_failure and status == "error":
            sessions[-1]["failure"] = run_failure["message"]
            server.add(f"{stamp(t + 1)} [Server thread/ERROR]: Exception while executing RCON command", "java.net.SocketException: Connection reset")
            harness.add(f"2026-09-24 03:0{session_index + 1}:30,000 ERROR fukurou.run.suite_run: server died during {tid}; skipping the remaining tests")

    close_session()
    files["logs/harness.log"] = harness.text().encode()

    counts = {"total": len(tests), **{s: sum(1 for r in tests if r["status"] == s) for s in ("passed", "failed", "error", "skipped")}}
    run_status = "error" if run_failure else ("failed" if counts["failed"] or counts["error"] else "passed")
    result = {
        "schemaVersion": 2,
        "id": run_id,
        "status": run_status,
        "fukurou": {"version": "2.0.0", "portablemc": "5.0.4"},
        "minecraft": {"version": version, "server": "paper", "build": spec["build"], "channel": spec.get("channel", "STABLE")},
        "java": {"server": spec["java"]},
        "plugins": [
            {"file": "MineStamp-abc1234-all.jar", "sha256": SHA, "name": "MineStamp", "version": "1.4.0", "role": "under-test", "source": None, "classFileMajor": 69, "enabled": True},
            {"file": "ProtocolLib.jar", "sha256": SHA[::-1], "name": "ProtocolLib", "version": "5.4.0", "role": "dependency", "source": "github:dmulloy2/ProtocolLib@dev-build/ProtocolLib.jar", "classFileMajor": 61, "enabled": True},
        ],
        "suite": {"source": "file:game-test/fukurou.yml", "sha256": SHA, "isolation": "reset", "settle": 3, "gamemode": "survival", "arena": {"size": 32, "height": 24}},
        "selection": selection,
        "players": [{"name": p, "joined": True} for p in PLAYERS],
        "summary": counts,
        "sessions": sessions,
        "tests": tests,
        "failure": run_failure,
        "logs": [{"kind": "harness", "path": "logs/harness.log", "player": None}],
        "startedAt": "2026-09-24T03:00:00Z",
        "finishedAt": "2026-09-24T03:05:30Z",
        "durationMs": 330000,
        "ci": {"repository": "morinoparty/MineStamp", "sha": "0123456789abcdef0123456789abcdef01234567", "ref": "refs/pull/1/merge", "runId": "123", "runAttempt": "1", "serverUrl": "https://github.com"},
    }
    return result, files


def main() -> None:
    if RUNS.exists():
        shutil.rmtree(RUNS)
    runs: list[dict] = []
    results: list[dict] = []
    for spec in RUNS_SPEC:
        result, files = build_run(spec)
        results.append(result)
        run_dir = RUNS / result["id"]
        for path, content in files.items():
            target = run_dir / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        (run_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        runs.append({"id": result["id"], "artifact": f"fukurou-{result['id']}", "base": f"runs/{result['id']}/", "status": result["status"], "result": result})

    # v1 の artifact（ビューアは読まない → unsupported カード、cells には現れない）
    v1 = {"schemaVersion": 1, "id": "paper-1.21.9", "status": "passed", "minecraft": {"version": "1.21.9", "server": "paper", "build": 40, "channel": "STABLE"}, "steps": [], "screenshots": [], "logs": []}
    runs.append({"id": "paper-1.21.9", "artifact": "fukurou-paper-1.21.9", "base": "runs/paper-1.21.9/", "status": "error", "result": v1})
    # result.json が無い artifact（ジョブがキャンセルされた）。build_manifest.py はコピーした harness.log を run.logs に載せる
    cancelled_log = RUNS / "paper-26.1" / "logs" / "harness.log"
    cancelled_log.parent.mkdir(parents=True, exist_ok=True)
    cancelled_log.write_text(
        "2026-09-24 03:00:00,001 INFO fukurou.run.suite_run: fukurou 2.0.0, Minecraft 26.1\n"
        "2026-09-24 03:00:04,200 INFO fukurou.run.suite_run: downloading Paper 26.1\n"
    )
    runs.append({
        "id": "paper-26.1", "artifact": "fukurou-paper-26.1", "base": "runs/paper-26.1/", "status": "error", "result": None,
        "logs": [{"kind": "harness", "path": "logs/harness.log", "player": None}],
    })
    runs.sort(key=lambda run: [int(part) for part in run["id"].split("-")[1].split(".")])

    # manifest.tests: 最初に現れた run の実行順で和集合
    tests: list[dict] = []
    rank = {"error": 0, "failed": 1, "passed": 2, "skipped": 3}
    for result in results:
        for test in result["tests"]:
            entry = next((t for t in tests if t["id"] == test["id"]), None)
            if entry is None:
                entry = {"id": test["id"], "name": test["name"], "tags": list(test["tags"]), "players": [], "shots": [], "status": "skipped", "cells": {}}
                tests.append(entry)
            for p in test["players"]:
                if p["name"] not in entry["players"]:
                    entry["players"].append(p["name"])
            for shot in test["screenshots"]:
                if shot["name"] != "failure" and shot["name"] not in entry["shots"]:
                    entry["shots"].append(shot["name"])
            entry["cells"][result["id"]] = test["status"]
            if rank[test["status"]] < rank[entry["status"]]:
                entry["status"] = test["status"]
    counts = {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}
    for entry in tests:
        for status in entry["cells"].values():
            counts["total"] += 1
            counts[status] += 1
    manifest = {
        "schemaVersion": 2,
        "generator": {"name": "fukurou-ui", "version": "2.0.0"},
        "generatedAt": "2026-09-24T03:10:00Z",
        "title": "MineStamp abc1234 (dev sample)",
        "ci": {"repository": "morinoparty/MineStamp", "sha": "0123456789abcdef0123456789abcdef01234567", "runId": "123", "runUrl": "https://github.com/morinoparty/MineStamp/actions/runs/123"},
        "summary": {
            "runs": {"total": len(runs), **{s: sum(1 for r in runs if r["status"] == s) for s in ("passed", "failed", "error")}},
            "tests": counts,
        },
        "players": list(PLAYERS),
        "tests": tests,
        "runs": runs,
        "warnings": [
            "fukurou-paper-1.21.9: unsupported result schemaVersion 1",
            "fukurou-paper-26.1: result.json missing (job cancelled?)",
        ],
    }
    (DEV / "manifest.js").write_text(
        "// Sample data for `pnpm dev` (served from ui/viewer/dev/). Generated by dev/generate.py; not part of the build.\n"
        "window.__FUKUROU_MANIFEST__ = " + json.dumps(manifest, indent=2) + ";\n"
    )
    print(f"wrote {DEV / 'manifest.js'} with {len(runs)} runs and {len(tests)} tests")


if __name__ == "__main__":
    main()
