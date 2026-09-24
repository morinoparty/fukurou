"""Paper のダウンロード API（fill v3）。"""

from pydantic import BaseModel, Field, TypeAdapter, ValidationError

from fukurou.errors import InvalidInputError
from fukurou.net import NetworkError, NotFoundError, fetch_json, fetch_model

PAPER_PROJECT_URL = "https://fill.papermc.io/v3/projects/paper"
# Paper のビルドのチャンネル。安定している順に並べる
BUILD_CHANNELS = ("STABLE", "BETA", "ALPHA")
# --paper-channel で指定できる値（許容する最も不安定なチャンネル）。既定は stable
PAPER_CHANNELS = ("stable", "beta", "alpha")
DEFAULT_PAPER_CHANNEL = "stable"
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


def accepted_channels(threshold: str) -> list[str]:
    """--paper-channel の値から、使ってよいビルドのチャンネル（安定している順）を返す。

    threshold は許容する最も不安定なチャンネルで、alpha なら ALPHA/BETA/STABLE のすべてを許す。
    """
    if threshold not in PAPER_CHANNELS:
        raise InvalidInputError(f"unknown Paper channel {threshold!r}; expected one of {', '.join(PAPER_CHANNELS)}")
    return list(BUILD_CHANNELS[: PAPER_CHANNELS.index(threshold) + 1])


def channel_accepted(channel: str, threshold: str) -> bool:
    """ビルドのチャンネル（"STABLE" など）が threshold で許されるかを返す。未知のチャンネルは許さない。"""
    return channel.upper() in accepted_channels(threshold)


def fetch_project() -> PaperProject:
    return fetch_model(PAPER_PROJECT_URL, PaperProject)


def latest_channel(version: str) -> str:
    """指定バージョンの Paper 最新ビルドのチャンネルを返す。"""
    return fetch_model(f"{PAPER_PROJECT_URL}/versions/{version}/builds/latest", PaperBuild).channel


def has_accepted_build(version: str, paper_channel: str = DEFAULT_PAPER_CHANNEL) -> bool:
    """指定バージョンに、paper_channel で許されるチャンネルのビルドが 1 つでもあるかを返す。

    多くの場合は最新ビルドのチャンネルだけで決まる（1 回の問い合わせで済む）。最新ビルドが許されなくても
    古いビルドが許されるチャンネルにあり得るため、その場合は resolve_build と同じ絞り込みで一覧を確かめる。
    """
    if channel_accepted(latest_channel(version), paper_channel):
        return True
    return pick_build(_fetch_builds(_accepted_builds_url(version, paper_channel)), paper_channel) is not None


def resolve_build(version: str, build: int | None = None, paper_channel: str = DEFAULT_PAPER_CHANNEL) -> PaperBuild:
    """サーバーに使う Paper のビルドを決める。

    build を指定した場合はそのビルドをチャンネルに関係なく使う（しきい値を下回るかは呼び出し側で警告する）。
    指定が無ければ、paper_channel で許されるチャンネルのうち最も新しいビルドを使う。
    """
    base = f"{PAPER_PROJECT_URL}/versions/{version}/builds"
    try:
        if build is not None:
            return fetch_model(f"{base}/{build}", PaperBuild)
        builds = _fetch_builds(_accepted_builds_url(version, paper_channel))
    except NotFoundError as error:
        target = f"build {build} for {version}" if build is not None else f"builds for {version}"
        raise InvalidInputError(f"Paper has no {target}") from error
    picked = pick_build(builds, paper_channel)
    if picked is None:
        raise InvalidInputError(no_accepted_build_message(version, paper_channel))
    return picked


def pick_build(builds: list[PaperBuild], paper_channel: str) -> PaperBuild | None:
    """ビルド一覧から、paper_channel で許されるチャンネルの最も新しいビルドを選ぶ。無ければ None。

    API の絞り込みに頼り切らず、ここでもチャンネルと id を確かめて選ぶ。
    """
    accepted = [candidate for candidate in builds if channel_accepted(candidate.channel, paper_channel)]
    return max(accepted, key=lambda candidate: candidate.id, default=None)


def no_accepted_build_message(version: str, paper_channel: str) -> str:
    """しきい値を満たすビルドが無いときのエラーメッセージ。緩める方法（--paper-channel）を示す。"""
    channels = "/".join(accepted_channels(paper_channel))
    message = f"Paper has no {channels} build for {version} (--paper-channel {paper_channel})"
    # まだ緩められる場合だけ、より不安定なチャンネルを許す方法を示す
    looser = PAPER_CHANNELS[PAPER_CHANNELS.index(paper_channel) + 1 :]
    if looser:
        message += f"; use --paper-channel {' or '.join(looser)} (the paper-channel input) to accept less stable builds"
    return message


def _accepted_builds_url(version: str, paper_channel: str) -> str:
    """paper_channel で許されるチャンネルに絞ったビルド一覧の URL を返す。"""
    # channel は繰り返し指定でき、指定したいずれかのチャンネルのビルドが新しい順に返る
    query = "&".join(f"channel={channel}" for channel in accepted_channels(paper_channel))
    return f"{PAPER_PROJECT_URL}/versions/{version}/builds?{query}"


def _fetch_builds(url: str) -> list[PaperBuild]:
    """ビルド一覧（新しい順）を取得する。"""
    try:
        return PAPER_BUILD_LIST.validate_python(fetch_json(url))
    except ValidationError as error:
        raise NetworkError(f"unexpected response from {url}: {error}") from error
