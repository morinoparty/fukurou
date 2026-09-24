"""テスト対象のプラグイン jar を探し、plugin.yml の名前やクラスファイルの版を読み取る。"""

from dataclasses import dataclass
import glob
from pathlib import Path
import re
import zipfile
import zlib

import yaml

from fukurou.errors import InvalidInputError
from fukurou.net import sha256_file

# Paper プラグインの記述ファイルを優先し、無ければ Bukkit 形式を読む
DESCRIPTOR_FILES = ("paper-plugin.yml", "plugin.yml")
CLASS_FILE_MAGIC = b"\xca\xfe\xba\xbe"
# クラスファイルの major 番号から Java の版を求める差分（Java 8 = 52）
CLASS_MAJOR_OFFSET = 44
# マルチリリース jar の版別クラスは実行環境に合わせて選ばれるため、必要な Java の判定に含めない
MULTI_RELEASE_PREFIX = "META-INF/versions/"
PATTERN_SEPARATOR = re.compile(r"[\n,]")


@dataclass(frozen=True)
class PluginJar:
    """サーバーの plugins/ に入れる1つの jar と、そこから読み取った情報。"""

    path: Path
    sha256: str
    name: str | None
    version: str | None
    class_file_major: int | None
    # plugin.yml の prefix。設定されているとサーバーログの [..] がこの値になる
    log_prefix: str | None = None

    @classmethod
    def inspect(cls, path: Path) -> "PluginJar":
        """jar を開いて記述ファイルとクラスファイルの版を読み取る。"""
        try:
            with zipfile.ZipFile(path) as jar:
                descriptor = read_descriptor(jar)
                major = max_class_major(jar)
        except yaml.YAMLError as error:
            raise InvalidInputError(f"{path}: invalid plugin descriptor: {error}") from error
        except (zipfile.BadZipFile, OSError, NotImplementedError, RuntimeError, zlib.error) as error:
            # NotImplementedError は未対応の圧縮方式、RuntimeError は暗号化された項目で送出される
            raise InvalidInputError(f"{path} is not a readable jar file: {error}") from error
        return cls(
            path=path,
            sha256=sha256_file(path),
            name=_optional_str(descriptor.get("name")),
            version=_optional_str(descriptor.get("version")),
            class_file_major=major,
            log_prefix=_optional_str(descriptor.get("prefix")),
        )

    @property
    def required_java(self) -> int | None:
        """クラスファイルを読み込むのに必要な Java の major 番号。"""
        return None if self.class_file_major is None else self.class_file_major - CLASS_MAJOR_OFFSET


def split_patterns(text: str) -> list[str]:
    """改行またはカンマ区切りのグロブ指定を分割する。空文字列ならプラグイン無し。"""
    return [pattern.strip() for pattern in PATTERN_SEPARATOR.split(text) if pattern.strip()]


def resolve_plugin_paths(plugins_dir: Path, patterns_text: str) -> list[Path]:
    """plugins_dir からの相対グロブで jar を探す。どれにも一致しないパターンは誤りとして扱う。"""
    if not plugins_dir.is_dir():
        raise InvalidInputError(f"plugins directory {plugins_dir} does not exist")
    paths: list[Path] = []
    for pattern in split_patterns(patterns_text):
        # root_dir を使うと相対パターンは plugins_dir 基準、絶対パターンはそのまま解決される
        matches = sorted(glob.glob(pattern, root_dir=plugins_dir, recursive=True))
        files = [plugins_dir / match for match in matches if (plugins_dir / match).is_file()]
        if not files:
            raise InvalidInputError(
                f'plugin pattern {pattern!r} matched no files in {plugins_dir}; pass --plugins "" to run without plugins'
            )
        # 複数のパターンが同じ jar に一致しても1回だけ入れる
        paths.extend(path for path in files if path not in paths)
    return paths


def inspect_plugins(plugins_dir: Path, patterns_text: str) -> list[PluginJar]:
    return [PluginJar.inspect(path) for path in resolve_plugin_paths(plugins_dir, patterns_text)]


def read_descriptor(jar: zipfile.ZipFile) -> dict:
    """paper-plugin.yml または plugin.yml を読む。どちらも無ければ空の dict。YAML が壊れていれば yaml.YAMLError。"""
    names = set(jar.namelist())
    for descriptor in DESCRIPTOR_FILES:
        if descriptor in names:
            # BaseLoader で読み、version: 1.10 が 1.1 のような数値に変わらないよう文字列のまま扱う
            data = yaml.load(jar.read(descriptor).decode("utf-8", errors="replace"), Loader=yaml.BaseLoader)
            return data if isinstance(data, dict) else {}
    return {}


def max_class_major(jar: zipfile.ZipFile) -> int | None:
    """jar 内のクラスファイルの major 番号（バイト 6〜7）の最大値。クラスが無ければ None。"""
    majors = []
    for entry in jar.infolist():
        if not entry.filename.endswith(".class") or entry.filename.startswith(MULTI_RELEASE_PREFIX):
            continue
        with jar.open(entry) as file:
            header = file.read(8)
        if len(header) == 8 and header[:4] == CLASS_FILE_MAGIC:
            majors.append(int.from_bytes(header[6:8], "big"))
    return max(majors, default=None)


def required_java(minecraft_java: int, plugins: list[PluginJar]) -> int:
    """サーバーに使う Java の major 番号。Mojang の要求とプラグインのクラスファイルの版の大きい方。"""
    return max([minecraft_java, *(plugin.required_java for plugin in plugins if plugin.required_java is not None)])


def _optional_str(value: object) -> str | None:
    return value if isinstance(value, str) and value else None
