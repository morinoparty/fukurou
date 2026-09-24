"""Mojang のバージョンマニフェストと、バージョンごとのメタデータ。"""

from pydantic import BaseModel, Field

from fukurou.errors import InvalidInputError
from fukurou.net import fetch_model

MOJANG_VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


class MojangVersion(BaseModel):
    """マニフェスト内の1バージョン。使うフィールドだけを定義し、それ以外は無視する。"""

    id: str
    type: str
    # バージョンごとの詳細 JSON（必要な Java の版などが書かれている）の URL
    url: str = ""


class MojangManifest(BaseModel):
    """Mojang のバージョンマニフェスト。versions はリリースの新しい順に並んでいる。"""

    versions: list[MojangVersion]

    def release_ids(self) -> list[str]:
        """リリース版の ID を新しい順（マニフェストの並び順）で返す。"""
        return [version.id for version in self.versions if version.type == "release"]

    def find(self, version_id: str) -> MojangVersion:
        """ID に一致するバージョンを返す。無ければ入力の誤りとして扱う。"""
        for version in self.versions:
            if version.id == version_id:
                return version
        raise InvalidInputError(f"{version_id} is not a Minecraft version in the Mojang version manifest")


class JavaVersion(BaseModel):
    major_version: int = Field(alias="majorVersion")


class VersionDetails(BaseModel):
    """バージョンごとの詳細 JSON。古いバージョンには javaVersion が無い。"""

    java_version: JavaVersion | None = Field(default=None, alias="javaVersion")


def fetch_manifest() -> MojangManifest:
    return fetch_model(MOJANG_VERSION_MANIFEST_URL, MojangManifest)


def fetch_java_major(version_id: str, manifest: MojangManifest | None = None) -> int:
    """そのバージョンの公式クライアント・サーバーが要求する Java の major 番号を返す。"""
    manifest = manifest or fetch_manifest()
    details = fetch_model(manifest.find(version_id).url, VersionDetails)
    if details.java_version is None:
        # javaVersion が無いのは 1.16 以前だけで、fukurou の対象外だが念のため Java 8 とみなす
        return 8
    return details.java_version.major_version
