#!/usr/bin/env bash
# 把已构建的 App 装到通过 USB 连接的 iPhone 并启动。
# 需要 Xcode 15+（devicectl）与已信任的开发者证书。
set -euo pipefail
cd "$(dirname "$0")/.."

APP=".build/DerivedData/Build/Products/Debug-iphoneos/MyFriend.app"
BUNDLE_ID="${BUNDLE_ID:-com.myfriend.app.ios}"

if [ ! -d "$APP" ]; then
  echo "找不到 $APP；先跑 ./scripts/build-device.sh" >&2
  exit 1
fi

echo "已连接的设备："
xcrun devicectl list devices | sed -n '1,12p'

DEVICE_ID="$(xcrun devicectl list devices 2>/dev/null | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1 || true)"
if [ -z "$DEVICE_ID" ]; then
  echo "没找到已连接的 iPhone：请用数据线连上、解锁、并在手机上点「信任」。" >&2
  exit 1
fi

xcrun devicectl device install app --device "$DEVICE_ID" "$APP"
xcrun devicectl device process launch --device "$DEVICE_ID" "$BUNDLE_ID"
