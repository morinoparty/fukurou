"""fukurou 自身の出力を標準出力と logs/harness.log の両方へ書く。"""

from contextlib import contextmanager
import logging
from pathlib import Path
import sys

ROOT_LOGGER = "fukurou"
CONSOLE_FORMAT = "[fukurou] %(message)s"
# parallel ブロックのレーン（スレッド名 lane-<block>-<lane>）からの行を見分けられるよう、ファイルにはスレッド名も残す
FILE_FORMAT = "%(asctime)s %(levelname)s %(name)s [%(threadName)s]: %(message)s"


@contextmanager
def harness_log(path: Path):
    """実行中だけ fukurou.* のロガーを標準出力とファイルへ向ける。終了時にハンドラーを外して閉じる。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger(ROOT_LOGGER)
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(logging.Formatter(CONSOLE_FORMAT))
    file = logging.FileHandler(path, mode="w", encoding="utf-8")
    file.setFormatter(logging.Formatter(FILE_FORMAT))
    previous_level = logger.level
    logger.setLevel(logging.INFO)
    logger.addHandler(console)
    logger.addHandler(file)
    try:
        yield logger
    finally:
        for handler in (console, file):
            logger.removeHandler(handler)
            handler.close()
        logger.setLevel(previous_level)
