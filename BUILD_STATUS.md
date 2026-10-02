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


## 🔴 真机实测 bug 全清单（vivo V2429A / Android 16 / API 36）

> 每一轮都是「装 APK → 抓 logcat/run-as 取证 → 定位根因 → 修 → CI 重建」，
> 下面 7 条全部**有实测证据**，其中 4 条是「只有真机才会暴露」的。

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `FileNotFoundException: Invalid file path` | Termux 的 `zstd` 无法执行（缺 `libzstd.so.1`），输出 0 字节；**代码未检查子进程退出码**，拿空流继续解析 | 弃用 zstd，改用 bootstrap 的 GNU `tar`；所有子进程都必须检查退出码 |
| 2 | `FileNotFoundException: rootfs/…tar.gz` | 资产名与 APK 内实际名不符 | 增加候选名兜底 + 明确报错 |
| 3 | APK 内 rootfs 变成 101.8 MB 的 `.tar` | **AGP 按 `.gz` 后缀嗅探并解压**资产（去掉 `noCompress("tar")` 也没用） | 资产后缀改 `.targz`；运行时**按魔数 1f8b** 判定是否 gzip |
| 4 | `tar: gzip: Cannot write: Broken pipe` | 对纯 tar 仍传了 `-z` | 按魔数选择 `tar -xz` / `tar -x` |
| 5 | `CANNOT LINK EXECUTABLE: library "libtalloc.so.2" not found` | `libtalloc.so` **在 APK 里但代码从没拷出来** | `ensureRuntimeLibs()` 启动时复制 |
| 6 | 同上（改名后仍失败） | **文件名必须等于 SONAME**：文件叫 `libtalloc.so` 满足不了 SONAME `libtalloc.so.2` | 复制为 `libtalloc.so.2` 放私有 lib 目录，并置于 `LD_LIBRARY_PATH` 首位 |
| 7 | APK 里根本没有 `libtalloc.so.2` | **AGP 只打包匹配 `lib*.so` 的文件**，`libtalloc.so.2` 被静默丢弃 | jniLibs 用 `libtalloc.so` + 运行时改名（与 #6 组合成完整解法） |

另有 1 条产品问题：

| # | 现象 | 修法 |
|---|---|---|
| 8 | 终端不弹出软键盘 | TerminalFragment 增加 `isFocusableInTouchMode` + `requestFocus` + 焦点变化触发 + 延时显式 `showSoftInput`，点击时再次触发 |

## ✅ 真机已达成的关键里程碑

| 里程碑 | 证据 |
|---|---|
| 非 Root 进 Ubuntu 容器 | `===PROOT_OK=== / PRETTY_NAME="Ubuntu 24.04.5 LTS" / aarch64 / uid=0` |
| targetSdk 28 在 Android 16 可用 | `nativeLibraryDir` 内 `libproot.so`(247 KB)/`libproot-loader.so`(18 KB)/`libtermux.so` 均已安装且可执行 |
| 全自动首启释放 rootfs | 日志：`rootfs 释放完成 → …/files/linux/rootfs（大小校验通过：bin/bash 存在）`，实测 105 MB |
| bootstrap 自动释放 | 日志：`bootstrap 释放完成，条目数=3479` |
| tar 解压性能 | 29 MB gz → 105 MB，**1.2 秒** |

## APK 体积变化（记录 AGP 行为对体积的影响）

| 版本 | APK | rootfs 资产 |
|---|---|---|
| 早期（noCompress 含 tar） | 147 MB | 106 MB 纯 tar |
| 去掉 noCompress tar | 75 MB | 106 MB 纯 tar |
| 改后缀 .targz | **73 MB** | **28.55 MB gzip** |


## 🔴 卡顿根因（由应用内日志导出定位，2026-10-02）

用户反馈「终端反应迟钝、跟手差」。日志（`log/onestop-log-20261002_075737.txt`）给出决定性证据：

```
07:57:11.622 [session] TerminalEmulator[size=74x33]   ← 键盘弹出：68 行 → 33 行
07:57:17.246 [session] TerminalEmulator[size=74x68]   ← 键盘收起：33 行 → 68 行
07:57:18.514 [session] TerminalEmulator[size=74x33]   ← 又弹
07:57:30.125 [session] TerminalEmulator[size=74x68]   ← 再收
```

而 PRoot 本身**很快**：`[proc] [proot] 退出码=0 耗时=104ms / 102ms / 108ms`。

### 结论
**卡顿不是 PRoot 慢，而是终端尺寸反复抖动**：软键盘弹收 → `TerminalView.updateSize()` →
行数在 68↔33 之间来回变 → 每次都要**重排整屏**并向 shell 发 `SIGWINCH` →
用户敲的键要等这一轮重绘完成才回显。

### 根因与修法
| 问题 | 根因 | 修法 |
|---|---|---|
| 尺寸抖动 | `TerminalFragment` 在 `onFocusChange` 与 `postDelayed(300ms)` 两处**反复调用 showSoftInput** | 只保留一次性 `requestFocus()`，键盘交给用户点击/系统策略（与 Termux 一致） |
| 可用堆过小 | 日志快照显示 `可用内存: 3MB / 256MB` | Manifest 加 `android:largeHeap="true"`；`configChanges` 对齐 Termux（补 keyboard/navigation） |
| 日志本身可能干扰 | 会话抓屏节流 400ms，快速输出时持续写盘 | 放宽到 2s + 内容去重 |
| 自检假阴性 | `BootstrapInstaller.isInstalled` 用 `bin/proot` 判定，但**bootstrap 不含 proot**（proot 来自 jniLibs），导致日志长期显示 `bootstrap=false` 而实际 `bin/tar=true` | 改用 `bin/tar` 判定 |

### 阶段 1 已确认达成（同一份日志的证据）
```
[app] [Rootfs] 释放完成（bin/bash 校验通过）
[proc] [stdout] post-install OK: PRETTY_NAME="Ubuntu 24.04.5 LTS"
[proc] [proot] 退出码=0 耗时=103ms
[proc] [stdout] aarch64
[proc] [stdout] 0            ← uid=0（PRoot 映射）
```


## 🔴 卡顿真正根因（第二轮，2026-10-02 由用户长日志定位）

用户第二轮日志（3010 行，含 2600 条按键打点）给出两个关键事实：

**事实 1：输入链路极快，完全不是瓶颈**
```
[Latency] 样本=34 最小=0ms 中位=1ms P90=4ms 最大=19ms
```

**事实 2：一次会话中出现 150 次尺寸变化，且大部分与键盘无关**
```
layoutChange(deltaH=-197) rows=46 viewH=1553 rootH=2092 遮挡高度=0 imeVisible=false
layoutChange(deltaH=+197) rows=40 viewH=1356 rootH=1895 遮挡高度=0 imeVisible=false
…
rootH 取值分布：2800(62次) / 2092(30次) / 1895(17次) / 1829 / 2101 / 2100 / 1798
```

### 结论
不是 PRoot（退出码=0、耗时 200~700ms），不是输入（1ms），也不只是键盘弹收
——**是窗口高度被反复改动，每次改动都触发 `TerminalView.updateSize()` 整屏重排 + SIGWINCH**。
150 次重排累积起来，就是用户感知的「打字时字母出现要等」。

### 修法
`DiagTerminalView` 对尺寸变化做**防抖**：`onSizeChanged` 不再立即重排，
只在尺寸稳定 350ms 后应用一次（并记录 `[SizeDebounce]` 日志用于验证效果）。
预期把 150 次重排压到个位数。

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
