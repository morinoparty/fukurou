"""テストに使う Minecraft バージョンを Mojang のバージョンマニフェストと Paper の API から決める。

Mojang のマニフェストでリリースの順序を取り、Paper に STABLE なビルドがあるバージョンを選ぶ。
バージョン番号の大小比較は 1.21.11 と 26.1 のように体系が変わるため行わず、
マニフェストの並び（リリースの新しい順）だけで前後関係を判断する。
"""

from dataclasses import dataclass
from typing import Callable

from fukurou.errors import InvalidInputError
from fukurou.mojang import fetch_manifest
from fukurou.paper import STABLE_CHANNEL, fetch_project, latest_channel

# fukurou が対応する最も古いバージョン。これより古い指定はエラーにする
MIN_SUPPORTED = "1.20"
DEFAULT_MAX_VERSIONS = 16
LATEST = "latest"


class VersionError(InvalidInputError):
    """バージョン指定が不正、または条件に合うバージョンが見つからない場合に送出する。"""


@dataclass(frozen=True)
class VersionSpec:
    """"latest" 以外のバージョン指定を分解したもの。

    単一のバージョンは lower == upper、上限の無い範囲（"1.21.6-"）は upper が None になる。
    """

    lower: str
    upper: str | None

    @property
    def is_single(self) -> bool:
        return self.lower == self.upper


def parse_spec(spec: str) -> VersionSpec:
    """テストの versions: のような、通信せずに判定したい指定を構文だけ検証して分解する。

    "latest" は Paper のチャンネル（通信）が無いと決まらないため受け付けない。
    バージョンがリリースに存在するかは、リリース一覧を持つ spec_includes で確かめる。
    """
    spec = spec.strip()
    if not spec:
        raise VersionError("the version spec is empty")
    if spec == LATEST:
        raise VersionError(f"{LATEST!r} is not allowed here; use a version or a range such as 1.21.6-")
    if "-" not in spec:
        return VersionSpec(lower=spec, upper=spec)
    lower, upper = (part.strip() for part in spec.split("-", 1))
    if not lower:
        raise VersionError("a version range needs a lower bound, such as 1.21.6-")
    if "-" in upper:
        raise VersionError(f"{spec!r} is not a version or a range such as 1.21.6-1.21.11")
    return VersionSpec(lower=lower, upper=upper or None)


def spec_includes(spec: str, version: str, releases: list[str]) -> bool:
    """version が spec（"1.21.11" / "1.21.6-" / "1.21.6-1.21.11"）に含まれるかを返す。

    releases は Mojang のマニフェストのリリース一覧（新しい順）。番号の大小ではなく並び順で判定する
    ため、1.21.11 と 26.1 のように体系が変わっても正しく比べられる。
    """
    parsed = parse_spec(spec)
    if parsed.is_single:
        # 単一の指定は一致するかだけを見ればよく、リリース一覧に無くても判定できる
        return version == parsed.lower
    newest, oldest = range_indices(releases, parsed.lower, parsed.upper)
    ensure_release(releases, version)
    return newest <= releases.index(version) <= oldest


def resolve_versions(spec: str, max_versions: int = DEFAULT_MAX_VERSIONS) -> list[str]:
    """バージョン指定を、テストするバージョンのリスト（古い順）に解決する。

    指定できる形式:
      - "latest": Paper の STABLE ビルドがある最新リリース
      - "1.21.11": そのバージョンのみ
      - "1.20.5-": 1.20.5 以降で Paper の STABLE ビルドがある全リリース
      - "1.20.5-1.21.11": 両端を含む範囲で Paper の STABLE ビルドがある全リリース
    """
    releases = fetch_manifest().release_ids()
    paper_versions = fetch_project().version_ids()
    return select_versions(spec, releases, paper_versions, latest_channel, max_versions)


def resolve_version(spec: str) -> str:
    """単一のバージョン（"1.21.11" など）を確かめて返す。

    result.json の id と action の artifact 名を一致させるため、"latest" や範囲は受け付けない。
    """
    versions = resolve_versions(check_single_version(spec))
    if len(versions) != 1:
        raise VersionError(f"{spec} must resolve to exactly one version, got {', '.join(versions)}")
    return versions[0]


def check_single_version(spec: str) -> str:
    """ネットワークを使わずに、"latest" や範囲ではない単一のバージョン指定かを確かめる。"""
    spec = spec.strip()
    if spec == LATEST or "-" in spec:
        raise VersionError(
            f"expected a single Minecraft version such as 1.21.11, got {spec!r}; "
            "resolve it with `fukurou versions` first"
        )
    return spec


def select_versions(
    spec: str,
    releases: list[str],
    paper_versions: set[str],
    channel_of: Callable[[str], str],
    max_versions: int = DEFAULT_MAX_VERSIONS,
) -> list[str]:
    """取得済みのデータからバージョンを選ぶ。ネットワークに依存しないため単体テストできる。"""
    spec = spec.strip()
    if not spec:
        raise VersionError("the version spec is empty")
    if spec == LATEST:
        return [select_latest(releases, paper_versions, channel_of)]
    if spec not in releases and spec in paper_versions:
        # 1.21.11-rc3 のようなプレリリースは "-" を含むため、範囲と解釈する前に弾く
        raise VersionError(f"{spec} is not a Minecraft release; pre-releases and snapshots are not supported")
    if "-" not in spec:
        return [select_single(releases, paper_versions, spec)]
    lower, upper = (part.strip() for part in spec.split("-", 1))
    selected = select_range(releases, paper_versions, channel_of, lower, upper or None)
    if len(selected) > max_versions:
        raise VersionError(
            f"{spec} resolves to {len(selected)} versions, more than the maximum of {max_versions}; "
            "narrow the range or raise --max-versions"
        )
    return selected


def select_single(releases: list[str], paper_versions: set[str], version: str) -> str:
    """明示的に1つ指定されたバージョンを検証する。

    STABLE でなくてもビルドがあれば試せるようにし、チャンネルは確認しない。
    """
    ensure_supported(releases, version)
    if version not in paper_versions:
        raise VersionError(f"Paper has no builds for {version}")
    return version


def select_latest(releases: list[str], paper_versions: set[str], channel_of: Callable[[str], str]) -> str:
    """新しいリリースから順に見て、Paper の最新ビルドが STABLE な最初のバージョンを返す。"""
    for version in releases:
        # 公開直後のバージョンは Paper が ALPHA/BETA のことが多いので飛ばす
        if version in paper_versions and channel_of(version) == STABLE_CHANNEL:
            return version
    raise VersionError("no Minecraft release has a stable Paper build")


def select_range(
    releases: list[str],
    paper_versions: set[str],
    channel_of: Callable[[str], str],
    lower: str,
    upper: str | None,
) -> list[str]:
    """lower から upper（None なら最新）までのリリースのうち、Paper の STABLE ビルドがあるものを古い順で返す。"""
    if not lower:
        raise VersionError("a version range needs a lower bound, such as 1.21.6-")
    ensure_supported(releases, lower)
    newest, oldest = range_indices(releases, lower, upper)
    candidates = releases[newest : oldest + 1]
    selected = [v for v in candidates if v in paper_versions and channel_of(v) == STABLE_CHANNEL]
    if not selected:
        raise VersionError(f"no release between {lower} and {upper or LATEST} has a stable Paper build")
    return list(reversed(selected))


def range_indices(releases: list[str], lower: str, upper: str | None) -> tuple[int, int]:
    """範囲の両端をマニフェストの添字（newest, oldest）に変換する。新しい順なので newest <= oldest になる。"""
    ensure_release(releases, lower)
    if upper is not None:
        ensure_release(releases, upper)
    newest = releases.index(upper) if upper is not None else 0
    oldest = releases.index(lower)
    if newest > oldest:
        raise VersionError(f"{lower} is newer than {upper}")
    return newest, oldest


def ensure_release(releases: list[str], version: str) -> None:
    """マニフェストのリリース版に含まれることを確かめる（スナップショット等は対象外）。"""
    if version not in releases:
        raise VersionError(f"{version} is not a Minecraft release in the Mojang version manifest")


def ensure_supported(releases: list[str], version: str) -> None:
    """MIN_SUPPORTED 以降のリリースであることを、マニフェストの並び順で確かめる。"""
    ensure_release(releases, version)
    # マニフェストは新しい順なので、インデックスが大きいほど古い
    if MIN_SUPPORTED in releases and releases.index(version) > releases.index(MIN_SUPPORTED):
        raise VersionError(f"fukurou supports Minecraft {MIN_SUPPORTED} or later; got {version}")
