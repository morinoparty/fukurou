"""ログファイルの増分読み取りウィンドウ（LogWindow）のテスト。"""

import os

from fukurou.result.model import LogRange
from fukurou.runner.log_window import LogWindow


def append(path, text: str) -> None:
    with path.open("ab") as file:
        file.write(text.encode("utf-8"))


def test_window_reads_only_lines_after_the_mark_and_accumulates(tmp_path):
    path = tmp_path / "latest.log"
    append(path, "startup 1\nAlice issued server command: /st a\n")
    window = LogWindow(path)
    window.mark()
    assert window.read() == ""
    assert window.line_range() is None
    append(path, "Alice issued server command: /st b\n")
    assert window.read() == "Alice issued server command: /st b\n"
    append(path, "\x1b[32mcolored\x1b[0m line\n")
    # 増分を読み足してテスト内では蓄積し、ANSI は取り除く
    assert window.read() == "Alice issued server command: /st b\ncolored line\n"
    assert window.line_range() == LogRange(from_line=3, to=4)
    # 次のテストの mark で前の行は見えなくなる
    window.mark()
    assert window.read() == ""
    assert window.line_range() is None
    append(path, "line 5\n")
    assert window.line_range() == LogRange(from_line=5, to=5)


def test_skip_hides_lines_from_read_but_keeps_them_in_the_range(tmp_path):
    path = tmp_path / "server.log"
    append(path, "startup\n")
    window = LogWindow(path)
    window.mark()
    # ハーネスのリセットの応答: logRanges には残すが、照合には使わない
    append(path, "[Rcon: Teleported Alice to 0.5, -60.0, -8.5]\n")
    window.skip()
    assert window.read() == ""
    assert window.line_range() == LogRange(from_line=2, to=2)
    append(path, "Alice issued server command: /st a\n")
    assert window.read() == "Alice issued server command: /st a\n"
    assert window.line_range() == LogRange(from_line=2, to=3)


def test_partial_last_line_is_left_for_the_next_read(tmp_path):
    path = tmp_path / "server.log"
    window = LogWindow(path)
    window.mark()
    append(path, "complete\npartial")
    assert window.read() == "complete\n"
    assert window.line_range() == LogRange(from_line=1, to=1)
    append(path, " line\n")
    assert window.read() == "complete\npartial line\n"
    assert window.line_range() == LogRange(from_line=1, to=2)


def test_missing_file_is_empty_until_it_appears(tmp_path):
    path = tmp_path / "logs" / "latest.log"
    window = LogWindow(path)
    window.mark()
    assert window.read() == ""
    path.parent.mkdir()
    append(path, "first\n")
    assert window.read() == "first\n"
    assert window.line_range() == LogRange(from_line=1, to=1)


def test_replaced_file_is_counted_from_its_start(tmp_path):
    path = tmp_path / "latest.log"
    append(path, "old 1\nold 2\nold 3\n")
    window = LogWindow(path)
    window.mark()
    # クライアントの再起動: latest.log がローテートされ、新しい（短い）ファイルになる
    os.replace(tmp_path / "latest.log", tmp_path / "2026-09-24-1.log")
    append(path, "new 1\n")
    assert window.read() == "new 1\n"
    assert window.line_range() == LogRange(from_line=1, to=1)
