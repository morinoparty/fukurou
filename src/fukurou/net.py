"""HTTP での JSON 取得とファイルのダウンロード。標準ライブラリだけで実装する。"""

import hashlib
import json
import os
from pathlib import Path
from typing import Any
import urllib.error
import urllib.request

from pydantic import BaseModel, ValidationError

from fukurou import __version__
from fukurou.errors import FukurouError

# Paper の API は連絡先の分かる User-Agent を求めている
USER_AGENT = f"fukurou/{__version__} (+https://github.com/morinoparty/fukurou)"
TIMEOUT_SECONDS = 60
CHUNK_SIZE = 1 << 20


class NetworkError(FukurouError):
    """HTTP リクエストの失敗や、期待しない応答を受け取った場合に送出する。"""


class NotFoundError(NetworkError):
    """HTTP 404。存在しないバージョンやビルドを指定した場合に、呼び出し側で入力の誤りとして扱えるようにする。"""


def build_request(url: str, headers: dict[str, str] | None = None, token: str | None = None) -> urllib.request.Request:
    """User-Agent 付きのリクエストを作る。

    token はリダイレクト先（S3 などの別ホスト）へ送らないよう、転送されないヘッダーとして付ける。
    """
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    if token:
        request.add_unredirected_header("Authorization", f"Bearer {token}")
    return request


def fetch_json(url: str, headers: dict[str, str] | None = None, token: str | None = None) -> Any:
    """URL から JSON を取得して返す。"""
    try:
        with urllib.request.urlopen(build_request(url, headers, token), timeout=TIMEOUT_SECONDS) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        error_type = NotFoundError if error.code == 404 else NetworkError
        raise error_type(f"GET {url} failed with HTTP {error.code}: {_error_detail(error)}") from error
    except OSError as error:
        raise NetworkError(f"GET {url} failed: {error}") from error
    try:
        return json.loads(body)
    except json.JSONDecodeError as error:
        raise NetworkError(f"GET {url} did not return JSON: {error}") from error


def fetch_model[T: BaseModel](url: str, model: type[T]) -> T:
    """URL から JSON を取得し、指定したモデルとして検証して返す。"""
    try:
        return model.model_validate(fetch_json(url))
    except ValidationError as error:
        raise NetworkError(f"unexpected response from {url}: {error}") from error


def sha256_file(path: Path) -> str:
    """ファイルの SHA-256 を16進数で返す。大きな jar でもメモリを使いすぎないよう分割して読む。"""
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def download(
    url: str,
    destination: Path,
    sha256: str | None = None,
    headers: dict[str, str] | None = None,
    token: str | None = None,
) -> Path:
    """URL の内容を destination に保存する。sha256 を指定した場合は一致しなければ失敗にする。

    途中で失敗しても壊れたファイルが残らないよう、一時ファイルに書いてから置き換える。
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(build_request(url, headers, token), timeout=TIMEOUT_SECONDS) as response:
            with partial.open("wb") as file:
                while chunk := response.read(CHUNK_SIZE):
                    digest.update(chunk)
                    file.write(chunk)
    except urllib.error.HTTPError as error:
        partial.unlink(missing_ok=True)
        raise NetworkError(f"download of {url} failed with HTTP {error.code}") from error
    except OSError as error:
        partial.unlink(missing_ok=True)
        raise NetworkError(f"download of {url} failed: {error}") from error
    actual = digest.hexdigest()
    if sha256 is not None and actual != sha256.lower():
        partial.unlink(missing_ok=True)
        raise NetworkError(f"checksum mismatch for {url}: expected sha256 {sha256.lower()}, got {actual}")
    os.replace(partial, destination)
    return destination


def cached_download(url: str, destination: Path, sha256: str) -> Path:
    """チェックサムが一致するファイルがキャッシュにあれば再利用し、無ければダウンロードする。"""
    if destination.is_file() and sha256_file(destination) == sha256.lower():
        return destination
    return download(url, destination, sha256=sha256)


def _error_detail(error: urllib.error.HTTPError) -> str:
    """エラー応答の本文（API のエラーメッセージ）を短く取り出す。"""
    try:
        return error.read(500).decode("utf-8", errors="replace").strip() or error.reason
    except OSError:
        return str(error.reason)
