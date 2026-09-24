"""versions.py のバージョン選択ロジックを確認するテスト（ネットワークには接続しない）。"""

import pytest

from fukurou.mojang import MojangManifest
from fukurou.paper import PaperProject
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


def select(spec, max_versions=16, channels=CHANNELS):
    return select_versions(
        spec, MANIFEST.release_ids(), PAPER_PROJECT.version_ids(), lambda v: channels.get(v, "STABLE"), max_versions
    )


def test_latest_skips_snapshots_and_unstable_paper_builds():
    assert select("latest") == ["26.2"]
    with pytest.raises(VersionError, match="no Minecraft release has a stable Paper build"):
        select("latest", channels={v: "BETA" for v in MANIFEST.release_ids()})


def test_single_version_does_not_require_a_stable_build():
    assert select("26.3") == ["26.3"]
    with pytest.raises(VersionError, match="pre-releases and snapshots are not supported"):
        select("26.3-rc-3")


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
