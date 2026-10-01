# 构建状态与真机验证记录

> **铁律：本工程不在本地编译 APK；所有编译在 GitHub Actions 完成。**
> 本地（Linux 环境）只做：脚本语法检查、真实资源校验、以及**真机 adb 取证**。

## 仓库

- 仓库：`git@github.com:vkxkzsp769-cloud/onestop-linux.git`
- Actions：https://github.com/vkxkzsp769-cloud/onestop-linux/actions
- 推送方式：源码在 `/sdcard/.../一体式/onestop-linux`，git 操作必须在 `/tmp/push/onestop-linux`
  （`/sdcard` 是 FUSE，不能当 git 仓库）

## CI 运行记录

| # | 提交 | 结果 | 失败原因 / 修复 |
|---|---|---|---|
| 1 | d136114 | ❌ | 「安装构建依赖」：把 `ar` 当独立包名（属 `binutils`） |
| 2 | 67b0a02 | ❌ | 依赖还原：`libproot-loader.so` 不存在——实测 deb 布局为 `usr/libexec/proot/loader` |
| 3 | 6642781 | ❌ | `settings.gradle` 第 3 行 `maven()` 未找到——Kotlin DSL 语法配了 Groovy 扩展名 |
| 4 | 2f6ffeb | ❌ | `app/build.gradle` 第 28 行 `setOf()` 未找到——同一病根 |
| 5 | f81b572 | ❌ | 把 vendored 模块改名 `.kts` 后，`apply plugin:` 等 Groovy 语法无法被 Kotlin DSL 解析 |
| 6 | adb4cd9 | ❌ | `classifier()` 在 Gradle 8 已移除（→ `archiveClassifier`） |
| 7 | 79bf4a0 | ❌ | `termux-shared` 需要上游 `markwonVersion`；核对后确认**根本不需要该模块**，移除 |
| 8 | 4fe4f6c | ❌ | 进入 Kotlin 编译：KDoc 内 `*/` 提前闭合注释 + `log*` 表达式体返回类型不符 |
| 9 | 8f8bd7c | ✅ | **首次构建成功**：app-debug.apk 64 MB / app-release-unsigned.apk 62 MB |
| 10 | 7841a5e | ✅ | 文档更新 |
| 11 | 2b6ef24 | ✅ | 手写 tar 解析器（PAX/GNU 长名/硬链接）+ tar 规则门禁 |
| 12–13 | 62ddf95 / 31479ac | 运行中 | 真机实测后的大修（见下） |

## 🔴 真机实测发现（vivo V2429A / Android 16 / API 36）

### 环境坐标

| 项 | 值 |
|---|---|
| 机型 | vivo V2429A（PD2429），Android 16（API 36），arm64-v8a |
| 连接方式 | 无线调试（`adb pair` + `adb connect <局域网IP:动态端口>`） |
| adb 版本 | 34.0.4（本机免 root 解包安装于 `~/.local/share/adbkit`，`adb` 在 `~/.local/bin`） |
| targetSdk 28 是否可用 | ✅ **可用**：`nativeLibraryDir` 内 `libproot.so`(247 KB)、`libproot-loader.so`(18 KB)、`libtermux.so` **均已安装且可执行** |

### 首启失败的两个致命 bug（已用 `run-as` 逐层取证）

1. **Termux 的 `zstd` 在设备上无法执行**
   ```
   CANNOT LINK EXECUTABLE "…/usr/bin/zstd": library "libzstd.so.1" not found
   ```
   且我们的代码**没有检查子进程退出码**，于是拿着 **0 字节输出**继续「解析」tar。

2. **最终崩在写文件处**
   ```
   E/MainActivity: java.io.FileNotFoundException: Invalid file path
       at com.onestop.linux.core.ZstdTarExtractor.extract(ZstdTarExtractor.kt:104)
       at com.onestop.linux.core.RootfsInstaller.install(RootfsInstaller.kt:35)
   ```

3. **proot 的两个依赖从未就位**（手工补上后才跑通）
   - `libtalloc.so.2`：**在 APK 的 `assets/runtime/` 里，但代码从没把它拷出来**
   - `libandroid-shmem.so`：**APK 里压根没有**

### ✅ 真机人工验证成功（用 run-as 手工执行）

```
===PROOT_OK===
PRETTY_NAME="Ubuntu 24.04.5 LTS"
NAME="Ubuntu"
aarch64
0
```

即：**在非 Root 的 Android 16 上，成功进入内置的 Ubuntu 24.04.5 LTS ARM64 容器（uid=0）**。
这是方案中原本被标为「高危」的一环，现已实测通过。

### 关键实测数据

| 项 | 结果 |
|---|---|
| `tar -xzf` 解压 29 MB gz → 105 MB rootfs | **1.2 秒** |
| GNU tar 运行条件 | 需 `LD_LIBRARY_PATH=<usr>/lib`（否则 `libandroid-glob.so` 找不到） |
| 硬链接 | Android FUSE 不允许，tar 对 2 个条目（`perl5.38.2`/`uncompress`）告警但继续完成 |
| APK 体积构成 | bootstrap.zip 31.3 MB (49%) + rootfs 18.9 MB (30%) + dex 11.7 MB (18%) ≈ 63 MB |

## 修复后的架构（v2）

```
首启：
  ① 解 assets/bootstrap/bootstrap-aarch64.zip → files/linux/usr（zip 解压，3479 条目，真机 OK）
  ② 用 bootstrap 的 GNU tar 解 assets/rootfs/ubuntu-base-24.04.5-base-arm64.tar.gz
     （tar -xzf，LD_LIBRARY_PATH=<usr>/lib，以 bin/bash 是否就位判定真实成败）
  ③ 补执行位（ExecPermissionFixer）
  ④ 原子 renameTo + 哨兵
运行：
  PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so
  LD_LIBRARY_PATH=<nativeLibraryDir>:<usr>/lib   ← 首位必须是 nativeLibraryDir
  <nativeLibraryDir>/libproot.so --link2symlink -0 -r <rootfs> -b /dev -b /proc -b /sys …
```

**已删除**：手写 tar 解析器（`ZstdTarExtractor`）、`ZstdDecompressor`、以及对 `zstd` 的依赖。

## 下一步

1. 等 CI#13 出 APK → 重装 → 验证「全自动首启」能否走到 `===PROOT_OK===`（此前是我手工执行的）
2. 之后进阶段 2：`dsh web` 三级检测 + 内嵌 WebView
3. 体积优化：bootstrap 31 MB 可裁剪（只留 proot/tar/bash/coreutils 等必需项）
