#!/usr/bin/env bash
# bump-version.sh — 升级插件版本号并同步到所有相关文件
#
# 用法：
#   ./bump-version.sh           # patch: 1.0.0 → 1.0.1
#   ./bump-version.sh minor     # minor: 1.0.0 → 1.1.0
#   ./bump-version.sh major     # major: 1.0.0 → 2.0.0

set -euo pipefail

BUMP="${1:-patch}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

GRADLE="$ROOT/build.gradle.kts"
PLUGIN_XML="$ROOT/src/main/resources/META-INF/plugin.xml"
UPDATE_XML="$ROOT/updatePlugins.xml"

# ── 读取当前版本（以 build.gradle.kts 为准）──────────────────────────────────
CURRENT=$(grep -E '^version\s*=' "$GRADLE" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
if [[ -z "$CURRENT" ]]; then
  echo "❌ 未能从 build.gradle.kts 中读取版本号" >&2
  exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

# ── 计算新版本 ────────────────────────────────────────────────────────────────
case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  *)
    echo "❌ 未知的升级类型：$BUMP（可选：patch / minor / major）" >&2
    exit 1
    ;;
esac

NEW="$MAJOR.$MINOR.$PATCH"
echo "📦 版本升级：$CURRENT → $NEW ($BUMP)"

# ── 更新 build.gradle.kts ────────────────────────────────────────────────────
sed -i '' -E "s/^version[[:space:]]*=[[:space:]]*\"$CURRENT\"/version = \"$NEW\"/" "$GRADLE"
echo "  ✔ build.gradle.kts"

# ── 更新 plugin.xml ──────────────────────────────────────────────────────────
sed -i '' "s|<version>$CURRENT</version>|<version>$NEW</version>|" "$PLUGIN_XML"
echo "  ✔ src/main/resources/META-INF/plugin.xml"

# ── 更新 updatePlugins.xml（version 属性 + URL 中的版本段）──────────────────
sed -i '' \
  -e "s|/v$CURRENT/|/v$NEW/|g" \
  -e "s|idea-method-ref-finder-$CURRENT\.zip|idea-method-ref-finder-$NEW.zip|g" \
  -e "s|version=\"$CURRENT\"|version=\"$NEW\"|g" \
  "$UPDATE_XML"
echo "  ✔ updatePlugins.xml"

echo ""
echo "✅ 完成！当前版本已更新为 $NEW"
echo "   下一步：git commit -am \"chore: bump version to $NEW\" && git tag v$NEW"
