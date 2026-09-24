"""サーバーを起動する Java の決定と、その版の確認。"""

import os
from pathlib import Path
import re
import shutil
import subprocess

from fukurou.errors import InvalidInputError

# java -XshowSettings:properties の出力にある仕様バージョン（例: "25"、Java 8 は "1.8"）
SPEC_VERSION = re.compile(r"java\.specification\.version\s*=\s*(\d+)(?:\.(\d+))?")


def default_java() -> Path:
    """JAVA_HOME の java、無ければ PATH 上の java を返す。"""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "java"
        if candidate.is_file():
            return candidate
    found = shutil.which("java")
    if found is None:
        raise InvalidInputError("Java was not found; set JAVA_HOME, put java on PATH or pass --java")
    return Path(found)


def java_major(java: Path) -> int | None:
    """java を実行して major 番号を調べる。判定できなければ None。"""
    if not java.is_file():
        raise InvalidInputError(f"Java executable {java} does not exist")
    try:
        completed = subprocess.run(
            [str(java), "-XshowSettings:properties", "-version"],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise InvalidInputError(f"could not run {java}: {error}") from error
    return parse_java_major(completed.stdout + completed.stderr)


def parse_java_major(output: str) -> int | None:
    """-XshowSettings:properties の出力から major 番号を取り出す。1.8 は 8 として扱う。"""
    match = SPEC_VERSION.search(output)
    if match is None:
        return None
    major, minor = match.groups()
    return int(minor) if major == "1" and minor else int(major)
