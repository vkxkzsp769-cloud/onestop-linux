# OneStop Linux

一体化 Android APK：**内置 Ubuntu 24.04 LTS ARM64（PRoot，无需 Root）** + Termux 终端内核 + 应用内 WebView + （规划）XFCE 桌面。

- **源码在本仓库**；**APK 由 GitHub Actions 构建**（见 `Actions` → `Build APK` → 产物 `onestop-linux-apk`）。
- 不在任何手机端/开发机本地编译。

## 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 终端 + PRoot Ubuntu 24.04 可进 shell | 骨架已就绪，首次 CI 构建中 |
| 2 | 用户自装 `dsh web` 后，应用内 WebView 自动显示其网页 | 骨架已就绪（三级检测 + token 注入） |
| 3 | Termux:X11 + XFCE 桌面 | 规划中 |
| 4 | Turnip/Freedreno GPU 加速（可选，失败自动回退软件渲染） | 规划中 |

完整技术方案（17 章，含参考实现分析与 62 处评审修订）：见配套文档 `一站式apk-完整方案.md`。

## 构建

CI 会自动完成全部步骤；本地手工构建等价于：

```bash
bash tools/vendor-termux.sh   # vendor termux-app 终端三模块（含 AGP 8 补丁）
bash tools/fetch-deps.sh      # 还原 bootstrap / Ubuntu rootfs / proot（全部带 SHA256 校验）
./gradlew :app:assembleDebug
```

## 依赖与可复现性

所有第三方输入都固定版本 + SHA256（见 `tools/fetch-deps.sh` 顶部常量）：

| 依赖 | 版本 | 来源 |
|---|---|---|
| Ubuntu Base | 24.04.5 | cdimage.ubuntu.com 官方 |
| Termux bootstrap | 2026.09.27-r1+apt.android-7 | termux-packages Release |
| proot / libtalloc / libandroid-shmem | 5.1.107.95 / 2.4.3 / 0.7 | Termux 官方 apt 仓库 |
| termux-app（终端内核，只取 terminal-emulator + terminal-view） | v0.118.3 | github.com/termux/termux-app |

## 关键设计（与实测对齐）

- **targetSdk = 28 + `extractNativeLibs=true` + `useLegacyPackaging=true`**：沿用 Termux 策略，
  保证应用私有目录内的原生二进制可执行（Android 10+ 的 W^X/SELinux 限制）。
- **proot 以 `libproot.so` 命名**：确保被 PackageManager 解压到 `nativeLibraryDir`。
- **rootfs 用 `tar.zst` 流式解压**，先解到临时目录再原子 `renameTo`，写哨兵标记保证幂等。
- **`dsh web` 三级检测**：端口提示文件 → `/proc/net/tcp` 回环 LISTEN 扫描 → HTTP 指纹确认。
- **token 注入唯一实现**：`TokenInjectingClient`（`shouldInterceptRequest` + document-start JS 补丁）。

## 许可证

- 本工程整体以 **GPL-3.0** 发布（见 `LICENSE`）。
- 第三方组件与出处见 `THIRD_PARTY_NOTICES.md`。
- Ubuntu 是 Canonical 的注册商标；本工程以 "OneStop Linux" 为名，仅描述「内置 Ubuntu 24.04 基础系统」。
