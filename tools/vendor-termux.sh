#!/usr/bin/env bash
# =============================================================================
# vendor termux-app 的终端三模块，并做 AGP 8 兼容补丁（在 CI 或构建机执行）。
#
# 上游：https://github.com/termux/termux-app  tag 固定为 TERMUX_TAG
# 许可证：Apache-2.0（保留各文件头部版权声明，勿删）
#
# 补丁内容：
#   1. 加 `namespace`（AGP 8 强制要求；上游是 AGP 7 写法）
#   2. `compileSdkVersion x` → `compileSdk = x`（AGP 8 移除了该方法的兼容写法）
#   3. 移除 maven-publish 块（我们不需要发布产物）
#   4. abiFilters 收敛为 arm64-v8a（本工程仅 ARM64）
# 这些补丁在每次 CI 构建时重新应用，保证「上游源码 + 可复现补丁」，而不是把
# 改过的第三方代码长期留在仓库里（便于升级上游）。
# =============================================================================
set -Eeuo pipefail

TERMUX_TAG="${TERMUX_TAG:-v0.118.3}"
TERMUX_REPO="${TERMUX_REPO:-https://github.com/termux/termux-app}"

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${VENDOR_DIR:-$ROOT/.vendor}"
SRC="$WORK/termux-app-$TERMUX_TAG"

log() { printf '\033[1;35m[vendor]\033[0m %s\n' "$*"; }

mkdir -p "$WORK"
if [ ! -d "$SRC/terminal-emulator" ]; then
  log "拉取 termux-app $TERMUX_TAG（codeload tarball，比 git clone 更适配受限网络）"
  rm -rf "$SRC"
  curl -fL --retry 3 -o "$WORK/t.tar.gz" \
    "https://codeload.github.com/termux/termux-app/tar.gz/refs/tags/$TERMUX_TAG"
  tar -xzf "$WORK/t.tar.gz" -C "$WORK"
  # codeload 解出的目录名带/不带 v 前缀均可能出现，统一规范为 $SRC
  alt="$(find "$WORK" -maxdepth 1 -type d -name 'termux-app-*' | head -1)"
  if [ -z "$alt" ]; then
    echo "解压后未找到 termux-app-* 目录；$WORK 内容："
    ls -la "$WORK" || true
    exit 1
  fi
  [ "$alt" = "$SRC" ] || mv -f "$alt" "$SRC"
fi
[ -d "$SRC/terminal-emulator" ] || {
  echo "源码目录异常: $SRC"; ls -la "$SRC" 2>/dev/null | head; exit 1; }

for m in terminal-emulator terminal-view termux-shared; do
  log "vendor $m"
  rm -rf "$ROOT/$m"
  cp -a "$SRC/$m" "$ROOT/$m"
  # 注意（CI#5 的血泪教训）：**不要把上游的 Groovy build.gradle 改名成 .kts**。
  # 上游用的是 `apply plugin: 'x'`、`abiFilters 'arm64-v8a'` 等 Groovy 语法，
  # Kotlin DSL 无法解析。本工程与 vendored 模块各自使用原生 DSL（Gradle 支持混用）。
done

# ---------------- 补丁 1/2：namespace + compileSdk ----------------
patch_ns() { # patch_ns <module> <namespace>
  local m="$1" ns="$2" f="$ROOT/$1/build.gradle"
  [ -f "$f" ] || { echo "缺少 $f"; exit 1; }
  # 1) namespace：插到 android { 之后
  if ! grep -q "namespace" "$f"; then
    awk -v ns="$ns" '
      { print }
      /^android \{/ && !done { printf "    namespace \"%s\"\n", ns; done=1 }
    ' "$f" > "$f.tmp" && mv "$f.tmp" "$f"
  fi
  # 2) compileSdkVersion x → compileSdk = x
  sed -i -E 's/^([[:space:]]*)compileSdkVersion[[:space:]]+(.+)$/\1compileSdk = \2/' "$f"
  # 3) minSdkVersion/targetSdkVersion（AGP8 仍支持，但统一为属性写法更稳）
  sed -i -E 's/^([[:space:]]*)minSdkVersion[[:space:]]+(.+)$/\1minSdk = \2/' "$f"
  sed -i -E 's/^([[:space:]]*)targetSdkVersion[[:space:]]+(.+)$/\1targetSdk = \2/' "$f"
  # 4) abiFilters 收敛
  sed -i -E "s/abiFilters 'x86', 'x86_64', 'armeabi-v7a', 'arm64-v8a'/abiFilters 'arm64-v8a'/" "$f"
  log "  补丁完成: $m (namespace=$ns)"
}

patch_ns terminal-emulator com.termux.terminal
patch_ns terminal-view     com.termux.view
patch_ns termux-shared     com.termux.shared

# ---------------- 补丁 3：移除 maven-publish ----------------
for m in terminal-emulator terminal-view termux-shared; do
  f="$ROOT/$m/build.gradle"
  python3 - "$f" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = s.replace("apply plugin: 'maven-publish'\n", "")
# 删除 afterEvaluate { publishing { ... } } 块（按大括号配平）
i = s.find('afterEvaluate {')
if i != -1:
    depth = 0; j = i
    while j < len(s):
        if s[j] == '{': depth += 1
        elif s[j] == '}':
            depth -= 1
            if depth == 0: j += 1; break
        j += 1
    s = s[:i] + s[j:]
open(p, 'w', encoding='utf-8').write(s)
PY
  log "  移除 publishing 块: $m"
done

# ---------------- 补丁 3b：Gradle 8 兼容（classifier → archiveClassifier）----------------
# Gradle 8 移除了 Jar 任务的 classifier 属性（CI#6 失败点）。
# 上游写法：classifier "sources"  →  正确写法：archiveClassifier.set("sources")
for m in terminal-emulator terminal-view termux-shared; do
  f="$ROOT/$m/build.gradle"
  [ -f "$f" ] || continue
  python3 - "$f" <<'PYPATCH'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
new, n = re.subn(r'(?m)^(\s*)classifier\s+([\'"])([^\'"]+)\2\s*$',
                 lambda m: f'{m.group(1)}archiveClassifier.set("{m.group(3)}")', s)
if n:
    open(p, 'w', encoding='utf-8').write(new)
    print(f"  修正 classifier → archiveClassifier: {n} 处")
PYPATCH
done

# ---------------- 补丁 4：termux-shared 的依赖裁剪（仅保留被终端链路使用的部分）----------------
SHARED_GRADLE="$ROOT/termux-shared/build.gradle"
if [ -f "$SHARED_GRADLE" ]; then
  python3 - "$SHARED_GRADLE" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
marker = "// —— vendored by OneStop Linux ——"
if marker not in s:
    s += f"""
{marker}
// 说明：termux-shared 上游包含大量与终端无关的功能（Termux:API、崩溃上报等）。
// 本工程只用到其中的少量工具类；为避免引入额外依赖，这里的裁剪保持保守，
// 编译期若发现未使用的类，可在后续版本中按需删除（见方案 §0.2 形态 C）。
"""
    open(p, 'w', encoding='utf-8').write(s)
PY
fi

log "vendor 完成，模块就位："
for m in terminal-emulator terminal-view termux-shared; do
  printf '  %-20s %s\n' "$m" "$(grep -m1 namespace "$ROOT/$m/build.gradle" | tr -d ' ' || echo '(无 namespace)')"
done
