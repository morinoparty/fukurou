"""ログファイルの「ある時点から後」だけを増分で読むウィンドウ。

1 つのサーバーセッションで複数のテストを走らせると、サーバーとクライアントのログは伸び続ける。
テストごとにファイル全体を読み直すと、前のテストの行（"issued server command" など）に一致して
偽の成功になるうえ O(n²) にもなるため、テストの開始時に mark() でバイトオフセットを記録し、
read() は前回読んだ位置から増分だけを読んでテスト内で蓄積する。

行番号も一緒に数えておき、テストの終了時に line_range() で result.json の logRanges にする。

parallel ブロックではサーバーログのウィンドウを複数のレーン（スレッド）が同時に read() するため、
読み足しとバッファの更新はロックで直列にする。
"""

from dataclasses import dataclass
import os
from pathlib import Path
import threading

from fukurou.result.model import LogRange
from fukurou.runner.process import ANSI_ESCAPE


@dataclass
class _Position:
    """ファイル内の位置。offset バイト目までに newlines 個の改行がある。"""

    offset: int = 0
    newlines: int = 0


class LogWindow:
    """1 つのログファイルに対する読み取りウィンドウ。"""

    def __init__(self, path: Path):
        self.path = path
        # ファイルの識別子。クライアントの再起動で latest.log が別ファイルに置き換わったら数え直す
        self._inode: int | None = None
        # 読み終えた位置（改行までしか消費しないので、行の途中で切れることはない）
        self._read = _Position()
        # 直近の mark() の位置。line_range() の from はここから決まる
        self._mark = _Position()
        # mark() 以降に読んだテキスト
        self._buffer = ""
        # 複数のスレッドから同時に読まれても、位置とバッファの対応が崩れないようにする
        self._lock = threading.Lock()

    def mark(self) -> None:
        """ここから後をウィンドウにする。ファイルが無ければ先頭から。"""
        with self._lock:
            self._catch_up()
            self._mark = _Position(self._read.offset, self._read.newlines)
            self._buffer = ""

    def read(self) -> str:
        """mark() から現在までのテキストを返す（ANSI のカラーコードは除く）。"""
        with self._lock:
            self._catch_up()
            return self._buffer

    def skip(self) -> None:
        """ここまでに書かれた行を read() の対象から外す。line_range() の始点（mark()）は動かさない。

        ハーネスのリセットのコマンドの応答は logRanges には残したいが、テストの wait_for_log / assert_no_log が
        それに一致してはいけない。リセットの後にこれを呼び、照合の対象をリセットより後の行だけにする。
        """
        with self._lock:
            self._catch_up()
            self._buffer = ""

    def line_range(self) -> LogRange | None:
        """mark() から現在までに書かれた行の範囲（1 始まり、両端含む）。1 行も無ければ None。"""
        with self._lock:
            self._catch_up()
            first = self._mark.newlines + 1
            last = self._read.newlines
        if last < first:
            return None
        return LogRange(from_line=first, to=last)

    def _catch_up(self) -> None:
        """前回の位置から最後の改行までを読み、バッファと行数に足す。呼び出し側が _lock を持つ。"""
        try:
            with self.path.open("rb") as file:
                stat = os.fstat(file.fileno())
                if self._inode is not None and (stat.st_ino != self._inode or stat.st_size < self._read.offset):
                    # 別のファイルに置き換わった（クライアントの再起動で latest.log がローテートした等）
                    self._reset()
                self._inode = stat.st_ino
                file.seek(self._read.offset)
                data = file.read()
        except FileNotFoundError:
            return
        # 行の途中（書き込み中の行やマルチバイト文字の途中）は次回に回す
        cut = data.rfind(b"\n")
        if cut < 0:
            return
        chunk = data[: cut + 1]
        self._read = _Position(self._read.offset + len(chunk), self._read.newlines + chunk.count(b"\n"))
        self._buffer += ANSI_ESCAPE.sub("", chunk.decode("utf-8", errors="replace"))

    def _reset(self) -> None:
        self._read = _Position()
        self._mark = _Position()
        self._buffer = ""
