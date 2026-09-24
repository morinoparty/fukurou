"""server.properties の既定値と、利用者の上書き指定との合成。"""

# 再現性のためのフラットワールドなど、テスト用サーバーの既定値。
# 値は server.properties の書式のまま（: は \: にエスケープ済み）で持つ
DEFAULT_PROPERTIES: dict[str, str] = {
    # オフラインモードのため、クライアントは Microsoft アカウント無しで参加できる
    "online-mode": "false",
    "enforce-secure-profile": "false",
    # 26.3 から white-list の既定値が true になり、テスト用のプレイヤーが参加できなくなるため明示的に切る
    "white-list": "false",
    "enforce-whitelist": "false",
    # 平坦なワールドにして背景の地形による写り方の揺れを減らす
    "level-type": "minecraft\\:flat",
    # 既定の "{}" だとレイヤー無しとしてエラーになるため明示する（地表は y=-61、足元は y=-60）
    "generator-settings": (
        '{"layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},'
        '{"block"\\:"minecraft\\:dirt","height"\\:2},{"block"\\:"minecraft\\:grass_block","height"\\:1}],'
        '"biome"\\:"minecraft\\:plains"}'
    ),
    "level-name": "fukurou-world",
    "difficulty": "peaceful",
    "spawn-monsters": "false",
    "spawn-protection": "0",
    "view-distance": "4",
    "simulation-distance": "4",
    "motd": "fukurou game test",
}


def parse_properties(text: str) -> dict[str, str]:
    """key=value の行を読む。空行と # / ! で始まるコメント行は無視する。

    値はエスケープを解釈せずにそのまま保持し、書き出すときも同じ文字列を使う。
    """
    properties: dict[str, str] = {}
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip():
            raise ValueError(f"line {number}: expected key=value, got {raw!r}")
        properties[key.strip()] = value.strip()
    return properties


def merge_properties(layers: list[dict[str, str]], managed: dict[str, str]) -> tuple[dict[str, str], list[str]]:
    """既定値に layers を順に重ね、最後に fukurou が管理するキーで上書きする。

    ポートや RCON の設定を変えられるとテストを制御できなくなるため、managed は必ず優先する。
    利用者が指定したのに無視した管理キーの一覧も返す（警告の表示に使う）。
    """
    merged = dict(DEFAULT_PROPERTIES)
    ignored: list[str] = []
    for layer in layers:
        for key, value in layer.items():
            if key in managed:
                if key not in ignored:
                    ignored.append(key)
                continue
            merged[key] = value
    merged.update(managed)
    return merged, ignored


def render_properties(properties: dict[str, str]) -> str:
    """server.properties の本文を作る。"""
    return "".join(f"{key}={value}\n" for key, value in properties.items())
