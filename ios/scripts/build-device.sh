#!/usr/bin/env bash
# 构建真机包（需要你自己的 Apple 开发者签名）。
#
# 用法：
#   export DEVELOPMENT_TEAM=ABCDE12345        # 必需：Xcode → Settings → Accounts 里能看到 Team ID
#   export BUNDLE_ID=com.yourname.yap    # 可选，默认 com.yap.app.ios
#   ./scripts/build-device.sh
set -euo pipefail
cd "$(dirname "$0")/.."

: "${DEVELOPMENT_TEAM:?请先 export DEVELOPMENT_TEAM=<你的 Team ID>}"
BUNDLE_ID="${BUNDLE_ID:-com.yap.app.ios}"

TMPDIR="$PWD/.build/tmp" \
CLANG_MODULE_CACHE_PATH="$PWD/.build/clang-cache" \
SWIFT_MODULE_CACHE_PATH="$PWD/.build/swift-cache" \
xcodebuild -project YetAnotherPal.xcodeproj -scheme YetAnotherPal \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath .build/DerivedData \
  -allowProvisioningUpdates \
  OTHER_SWIFT_FLAGS='$(inherited) -disable-sandbox' \
  DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
  PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" \
  build

echo
echo "构建完成：.build/DerivedData/Build/Products/Debug-iphoneos/YetAnotherPal.app"
echo "下一步：./scripts/install-device.sh（或直接用 Xcode 的 Run）"
