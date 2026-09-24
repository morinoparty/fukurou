"""plugins.py の jar の探索と、plugin.yml・クラスファイルの読み取りを確認するテスト。"""

import zipfile

import pytest

from fukurou.errors import InvalidInputError
from fukurou.plugins import PluginJar, inspect_plugins, required_java, resolve_plugin_paths, split_patterns


def class_bytes(major: int) -> bytes:
    """マジックナンバーと版だけを持つ、最小限のクラスファイルの先頭部分。"""
    return b"\xca\xfe\xba\xbe" + (0).to_bytes(2, "big") + major.to_bytes(2, "big") + b"\x00" * 8


def build_jar(path, entries: dict[str, bytes | str]):
    with zipfile.ZipFile(path, "w") as jar:
        for name, content in entries.items():
            jar.writestr(name, content)
    return path


def test_reads_descriptor_and_class_major(tmp_path):
    jar = build_jar(
        tmp_path / "Example.jar",
        {
            "plugin.yml": "name: Example\nversion: 1.10\nmain: dev.example.Example\n",
            "dev/example/Example.class": class_bytes(65),
            "dev/example/Util.class": class_bytes(61),
            # マルチリリースの版別クラスは判定に含めない
            "META-INF/versions/25/dev/example/Util.class": class_bytes(69),
        },
    )
    plugin = PluginJar.inspect(jar)
    # BaseLoader で読むため、1.10 が 1.1 に変わらない
    assert (plugin.name, plugin.version, plugin.class_file_major) == ("Example", "1.10", 65)
    assert plugin.required_java == 21
    assert len(plugin.sha256) == 64


def test_paper_plugin_descriptor_takes_precedence(tmp_path):
    jar = build_jar(
        tmp_path / "Both.jar",
        {"plugin.yml": "name: Legacy\nversion: '1'\n", "paper-plugin.yml": "name: Modern\nversion: '2'\n"},
    )
    plugin = PluginJar.inspect(jar)
    assert (plugin.name, plugin.version, plugin.class_file_major) == ("Modern", "2", None)


def test_non_plugin_jar_and_broken_files(tmp_path):
    plain = PluginJar.inspect(build_jar(tmp_path / "lib.jar", {"a/B.class": class_bytes(52)}))
    assert (plain.name, plain.version, plain.required_java) == (None, None, 8)
    broken = tmp_path / "broken.jar"
    broken.write_bytes(b"not a zip")
    with pytest.raises(InvalidInputError, match="not a readable jar"):
        PluginJar.inspect(broken)
    bad_yaml = build_jar(tmp_path / "Bad.jar", {"plugin.yml": "name: [unclosed\nmain: a.B\n"})
    with pytest.raises(InvalidInputError, match="invalid plugin descriptor"):
        PluginJar.inspect(bad_yaml)


def test_reads_the_log_prefix(tmp_path):
    jar = build_jar(tmp_path / "P.jar", {"plugin.yml": "name: PrefixedPlugin\nversion: '1.0'\nprefix: PFX\nmain: a.B\n"})
    assert PluginJar.inspect(jar).log_prefix == "PFX"


def test_glob_resolution(tmp_path):
    (tmp_path / "libs").mkdir()
    build_jar(tmp_path / "libs" / "A-all.jar", {"plugin.yml": "name: A\n"})
    build_jar(tmp_path / "libs" / "A.jar", {"plugin.yml": "name: A\n"})
    build_jar(tmp_path / "B.jar", {"plugin.yml": "name: B\n"})
    assert split_patterns(" libs/*-all.jar,\n\n B.jar ") == ["libs/*-all.jar", "B.jar"]
    paths = resolve_plugin_paths(tmp_path, "libs/*-all.jar\nB.jar,libs/*-all.jar")
    assert [path.name for path in paths] == ["A-all.jar", "B.jar"]
    assert resolve_plugin_paths(tmp_path, "") == []
    with pytest.raises(InvalidInputError, match="matched no files"):
        resolve_plugin_paths(tmp_path, "*.jar, missing-*.jar")
    with pytest.raises(InvalidInputError, match="does not exist"):
        resolve_plugin_paths(tmp_path / "nope", "*.jar")


def test_required_java_is_the_newer_of_minecraft_and_plugins(tmp_path):
    build_jar(tmp_path / "New.jar", {"plugin.yml": "name: New\n", "N.class": class_bytes(69)})
    build_jar(tmp_path / "Old.jar", {"plugin.yml": "name: Old\n", "O.class": class_bytes(52)})
    plugins = inspect_plugins(tmp_path, "*.jar")
    assert required_java(21, plugins) == 25
    assert required_java(21, plugins[1:]) == 21
    assert required_java(21, []) == 21
