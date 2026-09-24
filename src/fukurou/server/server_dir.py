"""実行ごとにまっさらなサーバーディレクトリを用意する。"""

from dataclasses import dataclass
import logging
from pathlib import Path
import shutil

from fukurou.errors import InvalidInputError
from fukurou.server.properties import merge_properties, parse_properties, render_properties

logger = logging.getLogger(__name__)

PROPERTIES_FILE = "server.properties"


@dataclass(frozen=True)
class ServerDirSpec:
    """サーバーディレクトリに置くものの指定。"""

    plugin_jars: list[Path]
    # 利用者が --server-properties で渡した key=value の行
    properties_text: str
    # fukurou が制御に使うため必ず優先する値（ポート・RCON など）
    managed_properties: dict[str, str]
    server_files: Path | None
    accept_eula: bool


def prepare_server_dir(server_dir: Path, spec: ServerDirSpec) -> None:
    """ワールドや設定が前回の実行に影響しないよう、毎回作り直す。

    server-files を先にコピーし、その後にプラグインと fukurou の設定を書く。
    server-files に server.properties があっても、その値は上書き指定の1つとして合成し、
    ポートや RCON の設定は fukurou の値を使う。
    """
    shutil.rmtree(server_dir, ignore_errors=True)
    server_dir.mkdir(parents=True)
    if spec.server_files is not None:
        copy_server_files(spec.server_files, server_dir)
    copy_plugins(spec.plugin_jars, server_dir / "plugins")
    write_properties(server_dir, spec)
    if spec.accept_eula:
        (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def copy_server_files(source: Path, server_dir: Path) -> None:
    """--server-files のディレクトリの中身をサーバーディレクトリへ重ねる。"""
    if not source.is_dir():
        raise InvalidInputError(f"server files directory {source} does not exist")
    shutil.copytree(source, server_dir, dirs_exist_ok=True)


def copy_plugins(jars: list[Path], plugins_dir: Path) -> None:
    """プラグインの jar を plugins/ にコピーする。同じファイル名は上書きになるため誤りとして扱う。"""
    plugins_dir.mkdir(parents=True, exist_ok=True)
    seen: dict[str, Path] = {}
    for jar in jars:
        if jar.name in seen:
            raise InvalidInputError(f"two plugin jars have the same file name {jar.name}: {seen[jar.name]} and {jar}")
        seen[jar.name] = jar
        shutil.copyfile(jar, plugins_dir / jar.name)


def write_properties(server_dir: Path, spec: ServerDirSpec) -> None:
    """既定値 < server-files の server.properties < --server-properties < 管理キー の順に合成して書く。"""
    path = server_dir / PROPERTIES_FILE
    layers = []
    try:
        if path.is_file():
            layers.append(parse_properties(path.read_text(encoding="utf-8")))
        layers.append(parse_properties(spec.properties_text))
    except ValueError as error:
        raise InvalidInputError(f"invalid server properties: {error}") from error
    properties, ignored = merge_properties(layers, spec.managed_properties)
    for key in ignored:
        logger.warning("server property %s is managed by fukurou; the given value is ignored", key)
    path.write_text(render_properties(properties), encoding="utf-8")
