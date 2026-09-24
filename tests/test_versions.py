"""versions.py のバージョン選択ロジックを確認するテスト（ネットワークには接続しない）。"""

import pytest

from fukurou.errors import InvalidInputError
from fukurou.mojang import MojangManifest
from fukurou.paper import PaperProject, channel_accepted
from fukurou.versions import VersionError, select_versions

MANIFEST = MojangManifest.model_validate(
    {
        "versions": [
            {"id": "26.4-snapshot-1", "type": "snapshot"},
            {"id": "26.3", "type": "release"},
            {"id": "26.3-rc-3", "type": "snapshot"},
            {"id": "26.2", "type": "release"},
            {"id": "26.1.2", "type": "release"},
            {"id": "1.21.11", "type": "release"},
            {"id": "1.20.1", "type": "release"},
            {"id": "1.20", "type": "release"},
            {"id": "1.19.4", "type": "release"},
            {"id": "1.12.2", "type": "release"},
        ]
    }
)
PAPER_PROJECT = PaperProject.model_validate(
    {
        "versions": {
            "26.3": ["26.3", "26.3-rc-3"],
            "26.2": ["26.2"],
            "26.1": ["26.1.2"],
            "1.21": ["1.21.11"],
            "1.20": ["1.20.1", "1.20"],
            "1.19": ["1.19.4"],
            "1.12": ["1.12.2"],
        }
    }
)
CHANNELS = {"26.3": "ALPHA", "26.2": "STABLE", "26.1.2": "STABLE", "1.21.11": "STABLE", "1.20.1": "STABLE", "1.20": "BETA"}


def select(spec, max_versions=16, channels=CHANNELS, paper_channel="stable"):
    # channels の値は 1 つのチャンネル（全ビルドがそのチャンネル）か、ビルドのチャンネルのタプル（新しい順）
    def has_build(version, threshold):
        history = channels.get(version, "STABLE")
        history = (history,) if isinstance(history, str) else history
        return any(channel_accepted(channel, threshold) for channel in history)

    return select_versions(
        spec,
        MANIFEST.release_ids(),
        PAPER_PROJECT.version_ids(),
        has_build,
        max_versions,
        paper_channel,
    )


def test_latest_skips_snapshots_and_unstable_paper_builds():
    assert select("latest") == ["26.2"]
    with pytest.raises(VersionError, match="no Minecraft release has a stable Paper build"):
        select("latest", channels={v: "BETA" for v in MANIFEST.release_ids()})


def test_single_version_needs_a_build_in_the_accepted_channels():
    # 26.3 は ALPHA しかないので、既定の stable と beta では使えず、--paper-channel を案内する
    for paper_channel in ("stable", "beta"):
        with pytest.raises(VersionError, match="use --paper-channel .*alpha"):
            select("26.3", paper_channel=paper_channel)
    assert select("26.3", paper_channel="alpha") == ["26.3"]
    assert select("1.20", paper_channel="beta") == ["1.20"]
    with pytest.raises(VersionError, match="pre-releases and snapshots are not supported"):
        select("26.3-rc-3", paper_channel="alpha")


def test_channel_threshold_widens_latest_and_ranges():
    assert select("latest", paper_channel="beta") == ["26.2"]
    assert select("latest", paper_channel="alpha") == ["26.3"]
    assert select("1.21.11-", paper_channel="alpha") == ["1.21.11", "26.1.2", "26.2", "26.3"]
    # beta は BETA の 1.20 を含めるが、ALPHA の 26.3 は含めない
    assert select("1.20-", paper_channel="beta") == ["1.20", "1.20.1", "1.21.11", "26.1.2", "26.2"]
    assert select("1.20-", paper_channel="stable") == ["1.20.1", "1.21.11", "26.1.2", "26.2"]
    with pytest.raises(VersionError, match="has a stable/beta Paper build"):
        select("26.3-", paper_channel="beta")
    with pytest.raises(InvalidInputError, match="unknown Paper channel"):
        select("latest", paper_channel="rc")


def test_ranges_are_ordered_oldest_first_by_manifest_order():
    # 1.21.11 と 26.1.2 のように番号体系が変わっても、マニフェストの並びで範囲を決める
    assert select("1.21.11-") == ["1.21.11", "26.1.2", "26.2"]
    assert select("1.20-26.1.2") == ["1.20.1", "1.21.11", "26.1.2"]
    with pytest.raises(VersionError, match="newer than"):
        select("26.2-26.1.2")


def test_versions_older_than_the_minimum_are_rejected():
    for spec in ("1.19.4", "1.12.2-", "1.19.4-1.21.11"):
        with pytest.raises(VersionError, match="fukurou supports Minecraft 1.20 or later; got"):
            select(spec)


def test_max_versions_limits_ranges():
    assert len(select("1.20-", max_versions=4)) == 4
    with pytest.raises(VersionError, match="resolves to 4 versions, more than the maximum of 3"):
        select("1.20-", max_versions=3)


def test_run_takes_only_a_single_version():
    from fukurou.versions import check_single_version

    assert check_single_version(" 1.21.11 ") == "1.21.11"
    for spec in ("latest", "1.21.6-", "1.20.5-1.21.11"):
        with pytest.raises(VersionError, match="single Minecraft version"):
            check_single_version(spec)


def test_parse_spec_accepts_versions_and_ranges_but_not_latest():
    from fukurou.versions import VersionSpec, parse_spec

    assert parse_spec(" 1.21.11 ") == VersionSpec("1.21.11", "1.21.11")
    assert parse_spec("1.21.9-") == VersionSpec("1.21.9", None)
    assert parse_spec("1.21.6 - 1.21.11") == VersionSpec("1.21.6", "1.21.11")
    for spec, message in (("latest", "not allowed"), ("", "empty"), ("-1.21.11", "lower bound")):
        with pytest.raises(VersionError, match=message):
            parse_spec(spec)


def test_spec_includes_uses_the_manifest_order():
    from fukurou.versions import spec_includes

    releases = MANIFEST.release_ids()
    # 番号の大小ではなくマニフェストの並びで比べるので、1.21.11 と 26.x の境界もまたげる
    assert spec_includes("1.21.11-", "26.2", releases)
    assert not spec_includes("26.1.2-", "1.21.11", releases)
    assert spec_includes("1.20.1-26.1.2", "1.20.1", releases)
    assert not spec_includes("1.20.1-26.1.2", "26.2", releases)
    # 単一のバージョンは一致だけを見る
    assert spec_includes("1.21.11", "1.21.11", releases)
    assert not spec_includes("1.21.11", "26.2", releases)
    with pytest.raises(VersionError, match="not a Minecraft release"):
        spec_includes("1.21.99-", "26.2", releases)


def build(build_id: int, channel: str):
    from fukurou.paper import PaperBuild

    return PaperBuild(id=build_id, channel=channel)


def test_pick_build_takes_the_newest_build_in_the_accepted_channels():
    from fukurou.paper import pick_build

    builds = [build(40, "ALPHA"), build(30, "BETA"), build(20, "STABLE"), build(10, "STABLE")]
    assert pick_build(builds, "stable").id == 20
    assert pick_build(builds, "beta").id == 30
    assert pick_build(builds, "alpha").id == 40
    assert pick_build([build(5, "ALPHA")], "beta") is None


def test_a_version_counts_when_an_older_build_is_accepted():
    # 最新ビルドが ALPHA でも、古い STABLE ビルドがあれば stable で選べる（run はその STABLE ビルドを使う）
    channels = {**CHANNELS, "26.3": ("ALPHA", "STABLE")}
    assert select("26.3", channels=channels) == ["26.3"]
    assert select("latest", channels=channels) == ["26.3"]
    assert select("26.2-", channels=channels) == ["26.2", "26.3"]


def test_has_accepted_build_looks_past_the_newest_build(monkeypatch):
    from fukurou import paper

    requested = []
    monkeypatch.setattr(paper, "latest_channel", lambda version: "ALPHA")

    def fake_fetch_json(url):
        requested.append(url)
        return [{"id": 20, "channel": "STABLE"}] if version_of(url) == "26.3" else []

    monkeypatch.setattr(paper, "fetch_json", fake_fetch_json)
    # 最新ビルドが許されれば一覧は取得しない
    assert paper.has_accepted_build("26.3", "alpha")
    assert requested == []
    # 最新が ALPHA でも、絞り込んだ一覧に STABLE があれば stable で使える
    assert paper.has_accepted_build("26.3", "stable")
    assert requested[-1].endswith("/26.3/builds?channel=STABLE")
    assert not paper.has_accepted_build("26.4", "beta")


def version_of(url):
    return url.split("/versions/", 1)[1].split("/", 1)[0]


def test_resolve_build_filters_by_channel_and_keeps_an_explicit_build(monkeypatch):
    from fukurou import paper

    requested = []

    def fake_fetch_json(url):
        requested.append(url)
        # API の channel 絞り込みを真似る
        channels = [part.split("=", 1)[1] for part in url.partition("?")[2].split("&")]
        return [{"id": 40, "channel": "ALPHA"}, {"id": 20, "channel": "STABLE"}] if "ALPHA" in channels else []

    monkeypatch.setattr(paper, "fetch_json", fake_fetch_json)
    assert paper.resolve_build("26.3", paper_channel="alpha").id == 40
    assert requested[-1].endswith("/26.3/builds?channel=STABLE&channel=BETA&channel=ALPHA")
    with pytest.raises(paper.InvalidInputError, match="Paper has no STABLE/BETA build for 26.3.*--paper-channel alpha"):
        paper.resolve_build("26.3", paper_channel="beta")
    # --server-build はチャンネルに関係なくそのビルドを使う
    monkeypatch.setattr(paper, "fetch_model", lambda url, model: model(id=40, channel="ALPHA"))
    assert paper.resolve_build("26.3", 40, "stable").channel == "ALPHA"
