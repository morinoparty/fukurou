#!/usr/bin/env bash
# fukurou（Python / Kotlin）が使う仮想ディスプレイ・入力・ソフトウェア描画のパッケージ。action.yml と setup/action.yml が共有する
set -euo pipefail
sudo apt-get update
# GPU の無いランナーで Mesa のソフトウェアレンダリングを使うための描画・音声ライブラリも入れる。
# 26.3 以降のクライアント（renderpearl）は Xvfb で OpenGL のウィンドウを作れないことがあるため、
# ソフトウェア実装の Vulkan（lavapipe）も入れて Vulkan バックエンドで描画できるようにする。
# x11-xserver-utils は Kotlin 版がキーコードの解決に使う xmodmap のため
sudo apt-get install --no-install-recommends -y \
  xvfb xdotool x11-xserver-utils \
  libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 libopenal1 \
  libvulkan1 mesa-vulkan-drivers \
  libxrandr2 libxinerama1 libxcursor1 libxi6
