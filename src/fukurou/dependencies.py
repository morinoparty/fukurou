"""テスト対象と一緒にサーバーへ入れる依存プラグインの指定と、そのダウンロード。

指定は YAML のリストで、各要素は次のどちらか:
  - {url: https://..., sha256: <省略可>}
  - {github: owner/repo, tag: <リリースのタグ>, asset: <アセットのファイル名>}
"""

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
from typing import Annotated, Any
from urllib.parse import unquote, urlparse

from pydantic import BaseModel, ConfigDict, Discriminator, Field, Tag, TypeAdapter, ValidationError
import yaml

from fukurou.errors import InvalidInputError
from fukurou.net import cached_download, download, fetch_json
from fukurou.yaml_loader import load_yaml

GITHUB_API_URL = "https://api.github.com"
# ファイル名として安全な文字だけを残す（キャッシュのディレクトリ名に使う）
UNSAFE_PATH_CHARS = re.compile(r"[^A-Za-z0-9._-]")


class DependencyModel(BaseModel):
    """依存指定の共通設定。タイプミスに気付けるよう未知のキーはエラーにする。"""

    model_config = ConfigDict(extra="forbid", frozen=True)


class UrlDependency(DependencyModel):
    """URL から直接ダウンロードする jar。sha256 を書けば内容を検証する。"""

    url: Annotated[str, Field(pattern=r"^https?://")]
    sha256: Annotated[str, Field(pattern=r"^[0-9a-fA-F]{64}$")] | None = None

    @property
    def source(self) -> str:
        return self.url

    @property
    def file_name(self) -> str:
        """URL のパスの末尾をファイル名にする。

        Paper は拡張子が .jar のファイルしか読み込まないため、.jar で終わらない場合は URL から決まる名前にする。
        """
        name = UNSAFE_PATH_CHARS.sub("_", unquote(PurePosixPath(urlparse(self.url).path).name))
        return name if name.endswith(".jar") and name != ".jar" else f"dependency-{_short_hash(self.url)}.jar"


class GithubDependency(DependencyModel):
    """GitHub のリリースに添付された jar。"""

    github: Annotated[str, Field(pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")]
    tag: Annotated[str, Field(min_length=1)]
    asset: Annotated[str, Field(min_length=1, pattern=r"^[^/\\]+$")]

    @property
    def source(self) -> str:
        return f"github:{self.github}@{self.tag}/{self.asset}"

    @property
    def file_name(self) -> str:
        return self.asset


def _dependency_kind(value: Any) -> str:
    """github キーの有無で、どちらの形式として検証するかを決める。"""
    if isinstance(value, dict):
        return "github" if "github" in value else "url"
    return "github" if isinstance(value, GithubDependency) else "url"


Dependency = Annotated[
    Annotated[UrlDependency, Tag("url")] | Annotated[GithubDependency, Tag("github")],
    Discriminator(_dependency_kind),
]
DEPENDENCY_LIST = TypeAdapter(list[Dependency])


@dataclass(frozen=True)
class DownloadedDependency:
    """キャッシュに保存した依存プラグインの jar と、その出どころ。"""

    path: Path
    source: str


def parse_dependencies(text: str | None) -> list[UrlDependency | GithubDependency]:
    """YAML（JSON も可）の依存指定を検証する。空なら依存無し。"""
    if not text or not text.strip():
        return []
    try:
        data = load_yaml(text)
    except yaml.YAMLError as error:
        raise InvalidInputError(f"dependencies is not valid YAML: {error}") from error
    if data is None:
        return []
    if not isinstance(data, list):
        raise InvalidInputError("dependencies must be a YAML list of {url, sha256?} or {github, tag, asset}")
    try:
        return DEPENDENCY_LIST.validate_python(data)
    except ValidationError as error:
        raise InvalidInputError(f"invalid dependencies: {error}") from error


def fetch_dependency(dependency: UrlDependency | GithubDependency, cache_dir: Path) -> DownloadedDependency:
    """依存プラグインをキャッシュへダウンロードし、保存先を返す。"""
    if isinstance(dependency, GithubDependency):
        path = _fetch_github_asset(dependency, cache_dir / "github")
    else:
        path = _fetch_url(dependency, cache_dir / "url")
    return DownloadedDependency(path=path, source=dependency.source)


def _fetch_url(dependency: UrlDependency, cache_dir: Path) -> Path:
    """sha256 があれば内容で決まるキャッシュを使い、無ければ毎回ダウンロードし直す（内容が変わりうるため）。"""
    if dependency.sha256:
        destination = cache_dir / dependency.sha256.lower() / dependency.file_name
        return cached_download(dependency.url, destination, dependency.sha256)
    destination = cache_dir / _short_hash(dependency.url) / dependency.file_name
    return download(dependency.url, destination)


def _fetch_github_asset(dependency: GithubDependency, cache_dir: Path) -> Path:
    """リリースのアセットを取得する。

    dev-build のように同じタグで更新され続けるリリースもあるため、
    アセットの ID と更新日時をキャッシュのキーにする。
    """
    token = os.environ.get("GITHUB_TOKEN") or None
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    release = fetch_json(f"{GITHUB_API_URL}/repos/{dependency.github}/releases/tags/{dependency.tag}", headers, token)
    assets = release.get("assets", []) if isinstance(release, dict) else []
    asset = next((item for item in assets if item.get("name") == dependency.asset), None)
    if asset is None:
        names = ", ".join(item.get("name", "?") for item in assets) or "none"
        raise InvalidInputError(
            f"{dependency.github}@{dependency.tag} has no asset named {dependency.asset!r} (assets: {names})"
        )
    key = UNSAFE_PATH_CHARS.sub("_", f"{asset.get('id')}-{asset.get('updated_at')}")
    destination = cache_dir / key / dependency.asset
    if destination.is_file():
        return destination
    # API の URL に octet-stream を要求すると、非公開リポジトリでもトークンでダウンロードできる
    return download(asset["url"], destination, headers={"Accept": "application/octet-stream"}, token=token)


def _short_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
