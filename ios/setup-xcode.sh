#!/bin/zsh
# iOS 开发环境部署（装到外置盘，系统盘只剩 35Gi 不够）
#
# 背景与结论（2026-11-03 实测）：
#   · 系统盘 / 460Gi，可用仅 35Gi → Xcode 解包需 40–50Gi，装系统盘必然塞满
#   · 外置盘 /Volumes/CY-MAC 931Gi，可用 305Gi → 装这里
#   · 本机只有 CommandLineTools（含 macOS SDK，无 iOS SDK），`swift build` 连
#     Package.swift manifest 都链不上（缺 SwiftPM 组件）→ 必须有完整 Xcode
#   · Xcode 的获取必须登录 Apple ID（App Store 或 developer.apple.com），无法脚本化绕过
#
# 用法：
#   1) 先在 App Store 登录你的 Apple ID（系统设置 → Apple 账户，或打开 App Store 登录）
#   2) 运行本脚本： ./ios/setup-xcode.sh
#   3) 脚本会把 Xcode 下载安装到 /Volumes/CY-MAC/ios-dev，并切换 xcode-select
#
set -euo pipefail

DEV_ROOT="/Volumes/CY-MAC/ios-dev"
XCODE_APP="$DEV_ROOT/Xcode.app"

echo "==> 检查外置盘"
if [ ! -d /Volumes/CY-MAC ]; then
  echo "✗ 外置盘 /Volumes/CY-MAC 未挂载"; exit 1
fi
AVAIL=$(df -g /Volumes/CY-MAC | tail -1 | awk '{print $4}')
echo "   外置盘可用: ${AVAIL}GB"
if [ "$AVAIL" -lt 60 ]; then
  echo "✗ 可用空间不足 60GB，Xcode 需要约 40–50GB"; exit 1
fi

mkdir -p "$DEV_ROOT"

echo "==> 安装辅助工具（mas / xcodegen）"
if ! command -v mas >/dev/null 2>&1; then
  brew install mas || { echo "✗ brew 安装 mas 失败"; exit 1; }
fi
if ! command -v xcodegen >/dev/null 2>&1; then
  brew install xcodegen || echo "⚠ xcodegen 安装失败（生成 .xcodeproj 时需要）"
fi

echo "==> 检查 App Store 登录态"
if ! mas account 2>/dev/null | head -1 | grep -q '@'; then
  cat <<'EOF'
✗ 未检测到 App Store 登录。

   请先做下面任一件事，然后重新运行本脚本：
   a) 打开「App Store」→ 左下角头像 → 登录你的 Apple ID（免费账号即可，不需要付费开发者）
   b) 如果你已经有 Xcode 安装包（.xip），直接：
        xip -x /path/to/Xcode.xip           # 解包
        mv Xcode.app /Volumes/CY-MAC/ios-dev/
        sudo xcode-select -s /Volumes/CY-MAC/ios-dev/Xcode.app/Contents/Developer
        xcodebuild -runFirstLaunch
      然后跳到本脚本末尾的「完成后」检查。
EOF
  exit 2
fi

echo "==> 通过 App Store 下载 Xcode（很大，几十分钟到数小时）"
# 注意：mas 下载的 Xcode 会装到 /Applications（系统盘）。
# 若系统盘不够，改用 developer.apple.com 下载 .xip 后解包到外置盘（见上面的 b 方案）。
mas install 497799835 || { echo "✗ 下载失败（检查 Apple ID 与网络）"; exit 3; }

echo "==> 把 Xcode 迁到外置盘（系统盘空间紧张时必做）"
if [ -d /Applications/Xcode.app ] && [ ! -d "$XCODE_APP" ]; then
  mv /Applications/Xcode.app "$DEV_ROOT/" || {
    echo "⚠ 迁移失败，可保留在 /Applications（需系统盘 ≥50GB）"; }
fi

echo "==> 切换工具链并完成首启"
if [ -d "$XCODE_APP" ]; then
  sudo xcode-select -s "$XCODE_APP/Contents/Developer"
  sudo xcodebuild -runFirstLaunch || true
  xcodebuild -version
else
  xcodebuild -version || true
fi

echo "==> 生成 iOS 工程（XcodeGen）"
cd "$(dirname "$0")"
if command -v xcodegen >/dev/null 2>&1; then
  xcodegen generate
  echo "✓ 已生成 WeiFan.xcodeproj"
fi

cat <<'EOF'

==> 完成后检查清单
  1) xcodebuild -version                     # 应显示 Xcode 16/26 等版本
  2) xcrun --sdk iphonesimulator --show-sdk-version   # 应显示 iOS SDK 版本
  3) cd ../core && swift build                # Core 应能编译（Xcode 提供完整 SwiftPM）
  4) open WeiFan.xcodeproj                    # 选模拟器 Run
  5) 真机运行需在 project.yml 填 DEVELOPMENT_TEAM，或在 Xcode 里选 Team

协议层（可选，连 WhatsApp 才需要）：
  cd ../../weifan-protocol && ./build-ios.sh   # 产出 WeifanProtocol.xcframework
EOF
