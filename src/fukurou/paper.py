"""Paper のダウンロード API（fill v3）。"""

from pydantic import BaseModel, Field, TypeAdapter, ValidationError

from fukurou.errors import InvalidInputError
from fukurou.net import NetworkError, NotFoundError, fetch_json, fetch_model

PAPER_PROJECT_URL = "https://fill.papermc.io/v3/projects/paper"
STABLE_CHANNEL = "STABLE"
# サーバー本体の jar。1.20 など古いビルドには Mojang マッピング版（server:mojang）もあるが使わない
SERVER_DOWNLOAD_KEY = "server:default"


class PaperProject(BaseModel):
    """Paper のプロジェクト情報。versions は {"1.21": ["1.21.11", ...]} のようにグループ化されている。"""

    versions: dict[str, list[str]]

    def version_ids(self) -> set[str]:
        """グループを平らにして、Paper にビルドがある全バージョンの集合を返す。"""
        return {version for group in self.versions.values() for version in group}


class Checksums(BaseModel):
    sha256: str


class PaperDownload(BaseModel):
    name: str
    checksums: Checksums
    url: str


class PaperBuild(BaseModel):
    """Paper の1ビルド。チャンネルは STABLE / BETA / ALPHA のいずれか。"""

    id: int
    channel: str
    downloads: dict[str, PaperDownload] = Field(default_factory=dict)

    def server_download(self) -> PaperDownload:
        try:
            return self.downloads[SERVER_DOWNLOAD_KEY]
        except KeyError:
            raise InvalidInputError(f"Paper build {self.id} has no server jar") from None


# ビルド一覧の応答は配列なので、モデルではなく TypeAdapter で検証する
PAPER_BUILD_LIST = TypeAdapter(list[PaperBuild])


def fetch_project() -> PaperProject:
    return fetch_model(PAPER_PROJECT_URL, PaperProject)


def latest_channel(version: str) -> str:
    """指定バージョンの Paper 最新ビルドのチャンネルを返す。"""
    return fetch_model(f"{PAPER_PROJECT_URL}/versions/{version}/builds/latest", PaperBuild).channel


def resolve_build(version: str, build: int | None = None) -> PaperBuild:
    """サーバーに使う Paper のビルドを決める。

    build を指定した場合はそのビルド、無ければ最新の STABLE ビルドを使う。
    明示的に指定されたバージョンは STABLE が無いこともあるため、その場合は最新ビルドにする。
    """
    base = f"{PAPER_PROJECT_URL}/versions/{version}/builds"
    try:
        if build is not None:
            return fetch_model(f"{base}/{build}", PaperBuild)
        stable = _fetch_builds(f"{base}?channel={STABLE_CHANNEL}")
        return stable[0] if stable else fetch_model(f"{base}/latest", PaperBuild)
    except NotFoundError as error:
        target = f"build {build} for {version}" if build is not None else f"builds for {version}"
        raise InvalidInputError(f"Paper has no {target}") from error


def _fetch_builds(url: str) -> list[PaperBuild]:
    """ビルド一覧（新しい順）を取得する。"""
    try:
        return PAPER_BUILD_LIST.validate_python(fetch_json(url))
    except ValidationError as error:
        raise NetworkError(f"unexpected response from {url}: {error}") from error
