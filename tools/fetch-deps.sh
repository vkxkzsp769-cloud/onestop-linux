#!/usr/bin/env bash
# =============================================================================
# 还原 APK 内置的第三方资源（在 CI 或任意 Linux 构建环境执行，**不在手机上编译**）。
#
# 产出：
#   app/src/main/assets/bootstrap/bootstrap-aarch64.zip           Termux bootstrap（proot/loader/libtalloc）
#   app/src/main/assets/rootfs/ubuntu-24.04-base-arm64.tar.zst    Ubuntu Base 24.04 LTS ARM64（zstd 重压）
#   app/src/main/jniLibs/arm64-v8a/libproot.so                    来自 Termux 官方 deb（Android aarch64 可执行）
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so
#   app/src/main/assets/runtime/libtalloc.so.2
#
# 用法：
#   tools/fetch-deps.sh                 # 完整下载
#   tools/fetch-deps.sh --check-urls    # 只校验 URL/校验和是否可达（不下载大文件）
#
# 所有版本与 SHA256 固定在此文件；改版本必须同时改 SHA256。
# =============================================================================
set -Eeuo pipefail

# ---------------- 固定版本与校验和（改这里 = 改依赖） ----------------
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.09.27-r1%2Bapt.android-7/bootstrap-aarch64.zip"
BOOTSTRAP_SHA256="9ddc32921187c85b04556bf56c6cce94e00b813ecd9299959a2d9b7c33386994"

UBUNTU_BASE_VER="24.04.5"
UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_BASE_VER}-base-arm64.tar.gz"
UBUNTU_BASE_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
# 不再重压为 tar.zst：真机实证 Termux 的 zstd 因缺 libzstd.so.1 无法执行，
# 而 bootstrap 的 GNU tar 支持 -z，直接内置官方 .tar.gz 更少依赖、更可靠。

TERMUX_APT="https://packages-cf.termux.dev/apt/termux-main"
PROOT_DEB="pool/main/p/proot/proot_5.1.107.95_aarch64.deb"
PROOT_DEB_SHA256="0a1b3d0f6ef76436c5ed924cd8e8f5a6b7186e99e1650eb2d9bc734e218a74cb"
TALLOC_DEB="pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
TALLOC_DEB_SHA256="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
SHMEM_DEB="pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
SHMEM_DEB_SHA256="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"

# ---------------- 路径 ----------------
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
CACHE="${CACHE_DIR:-$ROOT/.cache-deps}"
mkdir -p "$ASSETS/bootstrap" "$ASSETS/rootfs" "$ASSETS/runtime" "$JNI" "$CACHE"

log()  { printf '\033[1;36m[deps]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[deps:ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

verify() { # verify <file> <sha256>
  local f="$1" want="$2"
  [ -f "$f" ] || fail "缺少文件 $f"
  local got; got="$(sha256sum "$f" | awk '{print $1}')"
  if [ -z "$want" ]; then fail "$(basename "$f") 的 SHA256 未固定，拒绝继续（可复现性要求）"; fi
  if [ "$got" != "$want" ]; then
    fail "$(basename "$f") 校验失败\n  期望 $want\n  实际 $got"
  fi
  log "校验通过 $(basename "$f")"
}

dl() { # dl <url> <out>
  local url="$1" out="$2"
  if [ -s "$out" ]; then log "已缓存 $(basename "$out")"; return; fi
  log "下载 $(basename "$out")"
  curl -fL --retry 3 --retry-delay 2 -o "$out.part" "$url" || fail "下载失败: $url"
  mv "$out.part" "$out"
}

# ---------------- --check-urls ----------------
if [ "${1:-}" = "--check-urls" ]; then
  log "仅校验可达性（HEAD 请求）"
  for u in "$BOOTSTRAP_URL" "$UBUNTU_BASE_URL" "$TERMUX_APT/$PROOT_DEB" "$TERMUX_APT/$TALLOC_DEB" "$TERMUX_APT/$SHMEM_DEB"; do
    code="$(curl -sIL -o /dev/null -w '%{http_code}' "$u" || echo 000)"
    printf '  %-3s %s\n' "$code" "$u"
  done
  exit 0
fi

# ---------------- 1. Termux bootstrap ----------------
BS="$CACHE/bootstrap-aarch64.zip"
dl "$BOOTSTRAP_URL" "$BS"
verify "$BS" "$BOOTSTRAP_SHA256"
cp -f "$BS" "$ASSETS/bootstrap/bootstrap-aarch64.zip"

# ---------------- 2. Ubuntu Base → tar.zst ----------------
UB="$CACHE/ubuntu-base-${UBUNTU_BASE_VER}-base-arm64.tar.gz"
dl "$UBUNTU_BASE_URL" "$UB"
verify "$UB" "$UBUNTU_BASE_SHA256"
# 原样内置官方 tarball（App 侧用 bootstrap 的 GNU tar -xzf 解压）
ROOTFS_NAME="ubuntu-base-${UBUNTU_BASE_VER}-base-arm64.tar.gz"
rm -f "$ASSETS/rootfs"/*.tar.zst "$ASSETS/rootfs"/*.tar.gz
cp -f "$UB" "$ASSETS/rootfs/$ROOTFS_NAME"
( cd "$ASSETS/rootfs" && sha256sum "$ROOTFS_NAME" > SHA256SUMS && cat SHA256SUMS )

# ---------------- 3. proot / loader / talloc（Termux 官方 deb）----------------
for spec in "$PROOT_DEB|$PROOT_DEB_SHA256" "$TALLOC_DEB|$TALLOC_DEB_SHA256" "$SHMEM_DEB|$SHMEM_DEB_SHA256"; do
  rel="${spec%%|*}"; sum="${spec##*|}"
  deb="$CACHE/$(basename "$rel")"
  dl "$TERMUX_APT/$rel" "$deb"
  verify "$deb" "$sum"
  ex="$CACHE/extract/$(basename "$rel")"
  rm -rf "$ex"; mkdir -p "$ex"
  ( cd "$ex" && ar x "$deb" && tar -xf data.tar.* 2>/dev/null || true )
  log "  $(basename "$deb") 内共 $(find "$ex" -type f | wc -l) 个文件"
done

# proot 主程序与 loader 的真实布局（已实测 proot_5.1.107.95_aarch64.deb）：
#   data/data/com.termux/files/usr/bin/proot              主程序
#   data/data/com.termux/files/usr/libexec/proot/loader   64 位 loader（不是 libproot-loader.so！）
#   data/data/com.termux/files/usr/libexec/proot/loader32 32 位 loader（arm64 不需要，跳过）
PROOT_EX="$CACHE/extract/$(basename "$PROOT_DEB")"
PROOT_PREFIX="$PROOT_EX/data/data/com.termux/files/usr"
PROOT_BIN="$PROOT_PREFIX/bin/proot"
LD_BIN="$PROOT_PREFIX/libexec/proot/loader"
[ -f "$PROOT_BIN" ] || PROOT_BIN="$(find "$PROOT_EX" -type f -name proot -perm -u+x | head -1)"
[ -f "$LD_BIN" ]   || LD_BIN="$(find "$PROOT_EX" -type f -path '*libexec/proot/loader' | head -1)"
if [ ! -f "${PROOT_BIN:-/nonexistent}" ]; then
  fail "未在 proot deb 中找到 proot 可执行文件；deb 内容：\n$(find "$PROOT_EX" -type f | head -20)"
fi
if [ ! -f "${LD_BIN:-/nonexistent}" ]; then
  fail "未在 proot deb 中找到 loader（期望 usr/libexec/proot/loader）；deb 内容：\n$(find "$PROOT_EX" -type f | head -20)"
fi
log "proot 布局确认：${PROOT_BIN#$PROOT_EX/}"
log "loader 布局确认：${LD_BIN#$PROOT_EX/}"

cp -f "$PROOT_BIN" "$JNI/libproot.so"
cp -f "$LD_BIN"   "$JNI/libproot-loader.so"
chmod +x "$JNI/libproot.so" "$JNI/libproot-loader.so"

# ★ 真机实证：proot 启动时报 `CANNOT LINK EXECUTABLE: library "libtalloc.so.2" not found`
#   后接 `library "libandroid-shmem.so" not found`。Android 上动态链接器只信任 nativeLibraryDir，
#   因此这两个库必须放进 jniLibs（文件名保持 *.so 才能被 PackageManager 识别为原生库）。
TALLOC_EX="$CACHE/extract/$(basename "$TALLOC_DEB")"
TALLOC_SO="$(find "$TALLOC_EX" -type f -name 'libtalloc.so.2.*' -o -type f -name 'libtalloc.so.2' | head -1)"
[ -n "$TALLOC_SO" ] || TALLOC_SO="$(find "$TALLOC_EX" -type f -name 'libtalloc.so*' -size +1k | head -1)"
[ -n "$TALLOC_SO" ] || fail "未在 libtalloc deb 中找到 libtalloc.so"
cp -f "$TALLOC_SO" "$JNI/libtalloc.so"
cp -f "$TALLOC_SO" "$ASSETS/runtime/libtalloc.so.2"

SHMEM_EX="$CACHE/extract/$(basename "$SHMEM_DEB")"
SHMEM_SO="$(find "$SHMEM_EX" -type f -name 'libandroid-shmem.so' -size +1k | head -1)"
[ -n "$SHMEM_SO" ] || fail "未在 libandroid-shmem deb 中找到 .so"
cp -f "$SHMEM_SO" "$JNI/libandroid-shmem.so"

# ---------------- 4. 产物报告 ----------------
log "产物清单"
for f in "$ASSETS/bootstrap/bootstrap-aarch64.zip" \
         "$ASSETS/rootfs/$ROOTFS_NAME" \
         "$JNI/libproot.so" "$JNI/libproot-loader.so" "$JNI/libtalloc.so" "$JNI/libandroid-shmem.so"; do
  printf '  %-64s %s\n' "${f#$ROOT/}" "$(du -h "$f" | cut -f1)"
done

log "验证 proot 二进制架构（必须是 ARM aarch64 ELF）"
file "$JNI/libproot.so" || true
if command -v readelf >/dev/null; then
  readelf -h "$JNI/libproot.so" | grep -E 'Class|Machine' || true
fi
log "完成"
