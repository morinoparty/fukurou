"""サーバーディレクトリの準備（server.properties の合成）と、組み込みのプラグイン確認のテスト。"""

import pytest

from fukurou.errors import InvalidInputError
from fukurou.server.plugin_checks import PluginCheckError, PluginIdentity, check_plugins
from fukurou.server.properties import DEFAULT_PROPERTIES, merge_properties, parse_properties
from fukurou.server.server_dir import ServerDirSpec, prepare_server_dir

MANAGED = {"server-port": "25601", "enable-rcon": "true", "rcon.port": "25602", "rcon.password": "secret", "max-players": "2"}


def test_parse_properties_skips_comments_and_keeps_raw_values():
    text = "# comment\n! also comment\n\nmotd = Hello=World\nlevel-type=minecraft\\:normal\n"
    assert parse_properties(text) == {"motd": "Hello=World", "level-type": "minecraft\\:normal"}
    with pytest.raises(ValueError, match="expected key=value"):
        parse_properties("no-separator")


def test_merge_applies_layers_in_order_and_keeps_managed_keys():
    merged, ignored = merge_properties(
        [{"difficulty": "easy", "motd": "from files"}, {"motd": "from input", "server-port": "1", "rcon.password": "x"}],
        MANAGED,
    )
    assert merged["difficulty"] == "easy"
    assert merged["motd"] == "from input"
    assert merged["server-port"] == "25601" and merged["rcon.password"] == "secret"
    assert merged["generator-settings"] == DEFAULT_PROPERTIES["generator-settings"]
    assert ignored == ["server-port", "rcon.password"]


def test_prepare_server_dir(tmp_path):
    files = tmp_path / "files"
    (files / "plugins" / "Example").mkdir(parents=True)
    (files / "plugins" / "Example" / "config.yml").write_text("enabled: true\n")
    (files / "server.properties").write_text("difficulty=hard\nserver-port=1\n")
    jar = tmp_path / "Example.jar"
    jar.write_bytes(b"jar")
    server_dir = tmp_path / "server"
    (server_dir / "world").mkdir(parents=True)

    spec = ServerDirSpec(
        plugin_jars=[jar],
        properties_text="motd=hi\n",
        managed_properties=MANAGED,
        server_files=files,
        accept_eula=True,
    )
    prepare_server_dir(server_dir, spec)

    # 前回のワールドは消え、server-files とプラグインが入り、管理キーは fukurou の値になる
    assert not (server_dir / "world").exists()
    assert (server_dir / "plugins" / "Example" / "config.yml").is_file()
    assert (server_dir / "plugins" / "Example.jar").read_bytes() == b"jar"
    properties = parse_properties((server_dir / "server.properties").read_text())
    assert (properties["difficulty"], properties["motd"], properties["server-port"]) == ("hard", "hi", "25601")
    assert (server_dir / "eula.txt").read_text() == "eula=true\n"

    prepare_server_dir(server_dir, ServerDirSpec([], "", MANAGED, None, accept_eula=False))
    assert not (server_dir / "eula.txt").exists()
    with pytest.raises(InvalidInputError, match="same file name"):
        prepare_server_dir(server_dir, ServerDirSpec([jar, jar], "", MANAGED, None, True))


PAPER_LOG = """\
[12:00:01 INFO]: [Example] Loading server plugin Example v1.0
[12:00:02 INFO]: [Example] Enabling Example v1.0
[12:00:02 INFO]: [My_Plugin] Enabling My_Plugin v2
[12:00:05 INFO]: Done (4.2s)! For help, type "help"
"""


def identities(*names: str) -> list[PluginIdentity]:
    return [PluginIdentity(name=name, file_name=f"{name.replace(' ', '')}.jar") for name in names]


def test_plugin_check_passes_when_every_plugin_is_enabled():
    enabled = check_plugins(lambda: PAPER_LOG, identities("Example", "My Plugin"), timeout=0)
    assert enabled == {"Example": True, "My Plugin": True}


def test_plugin_check_uses_the_log_prefix():
    log = "[16:18:04 INFO]: [PFX] Enabling PrefixedPlugin v1.0\n"
    plugin = PluginIdentity(name="PrefixedPlugin", file_name="Prefixed.jar", log_prefix="PFX")
    assert check_plugins(lambda: log, [plugin], timeout=0) == {"PrefixedPlugin": True}


def test_plugin_check_reports_errors_and_missing_plugins():
    log = PAPER_LOG + "[12:00:03 ERROR]: Error occurred while enabling Example v1.0 (Is it up to date?)\n"
    with pytest.raises(PluginCheckError, match="Error occurred while enabling Example") as caught:
        check_plugins(lambda: log, identities("Example", "Other"), timeout=0, sleep=lambda _: None)
    assert caught.value.enabled == {"Example": False, "Other": False}
    assert "Other was not enabled" in str(caught.value)


def test_plugin_check_ties_errors_only_to_the_plugin_they_are_about():
    # 依存先（Example）や名前の一部が一致するプラグイン（Core）は、他のプラグインのエラーで無効にならない
    log = (
        "[12:00:01 INFO]: [Example] Enabling Example v1.0\n"
        "[12:00:01 INFO]: [Core] Enabling Core v1\n"
        "[12:00:02 ERROR]: Could not load plugin 'MineStampCore.jar' in folder 'plugins'\n"
        "[12:00:02 ERROR]: Error occurred while enabling Addon v1 (Is it up to date?) Example missing\n"
    )
    with pytest.raises(PluginCheckError) as caught:
        check_plugins(lambda: log, identities("Example", "Core", "MineStampCore", "Addon"), timeout=0)
    assert caught.value.enabled == {"Example": True, "Core": True, "MineStampCore": False, "Addon": False}
    assert "was not enabled" not in str(caught.value)
