#!/bin/zsh
# 从 Xcode .xip 安装到外置盘（系统盘只剩 35Gi，装 /Applications 会塞满）
#
# 为什么必须用 .xip：
#   · App Store / mas 只能装到 /Applications（系统盘），Xcode 需 40–50GB → 空间不足
#   · xcodes CLI 自身要 xcbuild 构建，而 xcbuild 来自 Xcode → 无法在没有 Xcode 的机器上安装
#   · `xip -x` 直接解包到目标目录，不占系统盘
#
# 用法：
#   ./ios/install-xcode-from-xip.sh /Volumes/CY-MAC/Xcode_26.xip
#
set -euo pipefail

XIP="${1:-}"
DEV_ROOT="/Volumes/CY-MAC/ios-dev"
APP="$DEV_ROOT/Xcode.app"

if [ -z "$XIP" ] || [ ! -f "$XIP" ]; then
  cat <<'EOF'
用法: ./ios/install-xcode-from-xip.sh <Xcode.xip 路径>

如何拿到 .xip（必须登录，无法脚本化）：
  1. 浏览器打开 https://developer.apple.com/download/all/?q=Xcode
  2. 用你的 Apple ID 登录（免费账号即可，同意开发者协议）
  3. 选最新 Xcode（.xip，约 8–10GB），下载**直接存到外置盘**，例如：
       /Volumes/CY-MAC/Xcode_26.xip
  4. 回来运行本脚本

备用路径：App Store 里搜 Xcode 也能装（免费），但会装到 /Applications，
系统盘 35GB 不够；若你已清理出 ≥55GB 也可以用那种方式，之后手动
  mv /Applications/Xcode.app /Volumes/CY-MAC/ios-dev/
EOF
  exit 1
fi

echo "==> 检查外置盘空间"
AVAIL=$(df -g /Volumes/CY-MAC | tail -1 | awk '{print $4}')
echo "   可用 ${AVAIL}GB（解包后 Xcode 约占 20–25GB）"
mkdir -p "$DEV_ROOT"
if [ -d "$APP" ]; then
  echo "⚠ 已存在 $APP，先移走再继续： mv '$APP' '$APP.bak'"
  exit 2
fi

echo "==> 解包（较慢，10–30 分钟，期间请勿休眠：caffeinate 已启用）"
cd "$DEV_ROOT"
caffeinate -i xip --expand "$XIP"

if [ ! -d "$APP" ]; then
  echo "✗ 解包完成但没找到 $APP，检查解出的目录名（可能叫 Xcode-beta.app）"
  ls "$DEV_ROOT" | head
  exit 3
fi

echo "==> 切换工具链"
sudo xcode-select -s "$APP/Contents/Developer"
sudo xcodebuild -runFirstLaunch || true
xcodebuild -version
echo "   iOS SDK: $(xcrun --sdk iphonesimulator --show-sdk-version 2>/dev/null || echo '未找到')"

echo "==> 生成 iOS 工程"
cd "$(dirname "$0")"
if command -v xcodegen >/dev/null 2>&1; then
  xcodegen generate && echo "✓ WeiFan.xcodeproj 已生成"
else
  echo "⚠ 缺 xcodegen：brew install xcodegen"
fi

echo "==> 验证 Core 能编译（Xcode 提供完整 SwiftPM）"
(cd ../core && swift build 2>&1 | tail -5) || echo "⚠ Core 编译失败，见上"

cat <<'EOF'

完成。下一步：
  cd ios && open WeiFan.xcodeproj     # 选 iPhone 模拟器 Run
  真机：project.yml 填 DEVELOPMENT_TEAM 或在 Xcode 里选 Team
EOF
