"""バニラクライアントの起動に使う PortableMC の取得。"""

import hashlib
from pathlib import Path
import tarfile

from fukurou.net import download
from fukurou.runner.process import GameProcessError

# PortableMC はバージョンとチェックサムを固定し、改ざんされたバイナリを実行しないようにする
PORTABLEMC_VERSION = "5.0.4"
PORTABLEMC_ARCHIVE = f"portablemc-{PORTABLEMC_VERSION}-linux-x86_64-gnu.tar.gz"
PORTABLEMC_SHA256 = "b14d2dff5191dabf90414562820ffdddfb5ee1acf692729782b4691d55b7b4f8"
PORTABLEMC_URL = (
    f"https://github.com/theorzr/portablemc/releases/download/v{PORTABLEMC_VERSION}/{PORTABLEMC_ARCHIVE}"
)


def ensure_portablemc(tools_dir: Path) -> Path:
    """PortableMC をダウンロード・チェックサム検証・展開し、実行ファイルのパスを返す。"""
    executable = tools_dir / f"portablemc-{PORTABLEMC_VERSION}" / "portablemc"
    if executable.is_file():
        return executable
    archive = tools_dir / PORTABLEMC_ARCHIVE
    if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != PORTABLEMC_SHA256:
        # download はチェックサムが一致しなければ保存せずに失敗する
        download(PORTABLEMC_URL, archive, sha256=PORTABLEMC_SHA256)
    with tarfile.open(archive) as tar:
        # アーカイブ内のディレクトリ構成に依存しないよう、実行ファイルだけを取り出す
        member = next((m for m in tar.getmembers() if m.isfile() and Path(m.name).name == "portablemc"), None)
        if member is None:
            raise GameProcessError("portablemc executable was not found in the archive")
        source = tar.extractfile(member)
        executable.parent.mkdir(parents=True, exist_ok=True)
        executable.write_bytes(source.read())
    executable.chmod(0o755)
    return executable
