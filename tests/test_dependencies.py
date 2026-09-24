"""依存プラグインの指定（YAML）の解釈を確認するテスト（ダウンロードはしない）。"""

import pytest

from fukurou.dependencies import GithubDependency, UrlDependency, parse_dependencies
from fukurou.errors import InvalidInputError

SHA = "a" * 64


def test_parses_url_and_github_dependencies():
    dependencies = parse_dependencies(
        f"""
- github: dmulloy2/ProtocolLib
  tag: dev-build
  asset: ProtocolLib.jar
- url: https://example.com/files/Vault%201.7.jar
  sha256: {SHA}
- url: https://example.com/download?id=3
"""
    )
    github, url, query = dependencies
    assert isinstance(github, GithubDependency)
    assert github.source == "github:dmulloy2/ProtocolLib@dev-build/ProtocolLib.jar"
    assert github.file_name == "ProtocolLib.jar"
    assert isinstance(url, UrlDependency)
    assert (url.source, url.sha256) == ("https://example.com/files/Vault%201.7.jar", SHA)
    # ファイル名に使えない文字は _ に置き換え、.jar で終わらない URL は URL から決まる名前にする
    assert url.file_name == "Vault_1.7.jar"
    assert query.file_name.startswith("dependency-") and query.file_name.endswith(".jar")


def test_empty_input_means_no_dependencies():
    assert parse_dependencies("") == []
    assert parse_dependencies("  \n") == []
    assert parse_dependencies(None) == []


@pytest.mark.parametrize(
    ("text", "message"),
    [
        ("url: https://example.com/a.jar", "must be a YAML list"),
        ("- url: ftp://example.com/a.jar", "should match pattern"),
        ("- url: https://example.com/a.jar\n  sha256: xyz", "should match pattern"),
        ("- github: not-a-repo\n  tag: v1\n  asset: a.jar", "should match pattern"),
        ("- github: owner/repo\n  asset: a.jar", "Field required"),
        ("- url: https://example.com/a.jar\n  sha: typo", "Extra inputs are not permitted"),
        ("- [", "not valid YAML"),
    ],
)
def test_rejects_invalid_dependencies(text, message):
    with pytest.raises(InvalidInputError, match=message):
        parse_dependencies(text)
