#!/usr/bin/env bash
set -euo pipefail

# 构建 iOS 可链接的 `AgentCoreKit.framework`（Kotlin Multiplatform / Kotlin/Native）。
#
# 用途：iOS 侧不再维护第二份决策实现，直接 `import AgentCoreKit` 调用 `:agentcore` 里的
# Kotlin 代码（InProcessAgentCore / SleepPolicy / DeferReply / ChineseTerms / VectorMath …）。
#
# 产物（默认落在 ios/.build/agentcore/，已被 .gitignore 忽略）：
#   ios/.build/agentcore/AgentCoreKit.xcframework        ← Xcode 直接拖入 / SPM binaryTarget
#   ios/.build/agentcore/iosSimulatorArm64/AgentCoreKit.framework
#   ios/.build/agentcore/iosArm64/AgentCoreKit.framework
#   ios/.build/agentcore/iosX64/AgentCoreKit.framework
#
# 用法：
#   ios/scripts/build-agentcore-framework.sh                 # Debug，三个 iOS target + xcframework
#   AGENTCORE_FRAMEWORK_CONFIGURATION=Release ios/scripts/build-agentcore-framework.sh
#   AGENTCORE_FRAMEWORK_TARGETS="iosSimulatorArm64" ios/scripts/build-agentcore-framework.sh
#
# 环境变量（都可覆盖）：
#   AGENTCORE_FRAMEWORK_OUT            产物目录（默认 <repo>/ios/.build/agentcore）
#   AGENTCORE_FRAMEWORK_CONFIGURATION  Debug | Release（默认 Debug）
#   AGENTCORE_FRAMEWORK_TARGETS        空格分隔的 target 名（默认三个 iOS target）
#   KONAN_DATA_DIR                     Kotlin/Native 工具链目录（默认 <repo>/.tooling/konan；
#                                      不设置时会用 ~/.konan，本机沙箱下不可写）

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${AGENTCORE_FRAMEWORK_OUT:-$ROOT/ios/.build/agentcore}"
CONFIGURATION="${AGENTCORE_FRAMEWORK_CONFIGURATION:-Debug}"
TARGETS_RAW="${AGENTCORE_FRAMEWORK_TARGETS:-iosSimulatorArm64 iosArm64 iosX64}"
GRADLE="$ROOT/.tooling/gradle-8.9/bin/gradle"

if [[ ! -x "$GRADLE" ]]; then
  echo "找不到 Gradle：$GRADLE（仓库自带 .tooling/gradle-8.9）" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$ROOT/.tooling/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.tooling/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.tooling/build-home}"
export TMPDIR="${TMPDIR:-$ROOT/.tooling/tmp}"
# 默认把 Kotlin/Native 工具链放在仓库内（~/.konan 在受限沙箱里往往不可写）。
export KONAN_DATA_DIR="${KONAN_DATA_DIR:-$ROOT/.tooling/konan}"

mkdir -p "$TMPDIR" "$KONAN_DATA_DIR" "$OUT_DIR"

read -r -a TARGETS <<< "$TARGETS_RAW"
TASKS=()
for target in "${TARGETS[@]}"; do
  # iosSimulatorArm64 -> IosSimulatorArm64
  capitalized="$(printf '%s' "${target:0:1}" | tr '[:lower:]' '[:upper:]')${target:1}"
  TASKS+=(":agentcore:link${CONFIGURATION}Framework${capitalized}")
done

echo "==> 构建 AgentCoreKit framework（${CONFIGURATION}）：${TARGETS[*]}"
"$GRADLE" -p "$ROOT" "${TASKS[@]}" --console=plain

for target in "${TARGETS[@]}"; do
  src="$ROOT/agentcore/build/bin/$target/$(printf '%s' "$CONFIGURATION" | tr '[:upper:]' '[:lower:]')Framework/AgentCoreKit.framework"
  if [[ ! -d "$src" ]]; then
    echo "构建成功但找不到产物：$src" >&2
    exit 1
  fi
  dst="$OUT_DIR/$target/AgentCoreKit.framework"
  rm -rf "$dst"
  mkdir -p "$(dirname "$dst")"
  cp -R "$src" "$dst"
done

# 打成 xcframework：Xcode target 只需要挂一个目录，模拟器/真机 slice 自动选择。
#
# 注意：xcframework 里同一个 platform（ios-simulator）只能出现一次。Kotlin/Native 给
# iosSimulatorArm64 与 iosX64 各产出一个 thin framework，所以先用 lipo 合成一个
# universal 模拟器 framework，再和真机 slice 一起打包。
if command -v xcodebuild >/dev/null 2>&1 && command -v lipo >/dev/null 2>&1; then
  XCFRAMEWORK="$OUT_DIR/AgentCoreKit.xcframework"
  rm -rf "$XCFRAMEWORK"
  slices=()

  if [[ -d "$OUT_DIR/iosArm64/AgentCoreKit.framework" ]]; then
    slices+=("$OUT_DIR/iosArm64/AgentCoreKit.framework")
  fi

  simulator_slices=()
  for target in iosSimulatorArm64 iosX64; do
    if [[ -d "$OUT_DIR/$target/AgentCoreKit.framework" ]]; then
      simulator_slices+=("$OUT_DIR/$target/AgentCoreKit.framework")
    fi
  done

  if [[ ${#simulator_slices[@]} -eq 1 ]]; then
    slices+=("${simulator_slices[0]}")
  elif [[ ${#simulator_slices[@]} -gt 1 ]]; then
    universal_dir="$OUT_DIR/universal-simulator"
    universal="$universal_dir/AgentCoreKit.framework"
    rm -rf "$universal_dir"
    mkdir -p "$universal_dir"
    cp -R "${simulator_slices[0]}" "$universal"
    binaries=()
    for framework in "${simulator_slices[@]}"; do
      binaries+=("$framework/AgentCoreKit")
    done
    lipo -create "${binaries[@]}" -output "$universal/AgentCoreKit"
    slices+=("$universal")
  fi

  if [[ ${#slices[@]} -gt 0 ]]; then
    args=()
    for framework in "${slices[@]}"; do
      args+=(-framework "$framework")
    done
    echo "==> 生成 xcframework：$XCFRAMEWORK"
    xcodebuild -create-xcframework "${args[@]}" -output "$XCFRAMEWORK"
  fi
else
  echo "未找到 xcodebuild/lipo，跳过 xcframework 打包（各 target 的 .framework 已生成）"
fi

echo
echo "==> 产物："
find "$OUT_DIR" -maxdepth 4 \( -name "*.framework" -o -name "*.xcframework" -o -name "AgentCoreKit" -o -name "Info.plist" \) | sort
