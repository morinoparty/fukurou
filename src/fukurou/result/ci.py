"""GitHub Actions の環境変数から、実行元のリポジトリやコミットの情報を読む。"""

from collections.abc import Mapping

from fukurou.result.model import CiInfo


def ci_from_env(env: Mapping[str, str]) -> CiInfo | None:
    """GitHub Actions 上でなければ None を返す（ローカル実行では ci を null にする契約）。"""
    if env.get("GITHUB_ACTIONS") != "true":
        return None
    return CiInfo(
        repository=env.get("GITHUB_REPOSITORY"),
        sha=env.get("GITHUB_SHA"),
        ref=env.get("GITHUB_REF"),
        run_id=env.get("GITHUB_RUN_ID"),
        run_attempt=env.get("GITHUB_RUN_ATTEMPT"),
        server_url=env.get("GITHUB_SERVER_URL"),
    )
