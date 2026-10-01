# 构建状态与进展记录

> 记录每次 CI 结果与已知问题，避免重复踩坑。
> **铁律：本工程不在本地编译 APK；所有编译在 GitHub Actions 完成。**

## 仓库

- 仓库：`git@github.com:vkxkzsp769-cloud/onestop-linux.git`
- Actions：https://github.com/vkxkzsp769-cloud/onestop-linux/actions
- 推送方式：源码在 `/sdcard/.../一体式/onestop-linux`，git 操作必须在 `/tmp/push/onestop-linux`（`/sdcard` 是 FUSE，不能当 git 仓库）

## CI 运行记录

| # | 提交 | 结果 | 失败/说明 |
|---|---|---|---|
| 1 | d136114 | ❌ failure | 「安装构建依赖」步骤：把 `ar` 当成独立包名（Ubuntu 无此包，属 `binutils`） |
| 2 | 67b0a02 | 运行中 | 已修正 apt 列表；SDK 组件显式化；Gradle 加 `--stacktrace` |

## 本机已验证（非编译）

| 项 | 方法 | 结果 |
|---|---|---|
| 5 个第三方依赖 URL 可达 | `tools/fetch-deps.sh --check-urls` | 全部 200 |
| 依赖版本与 SHA256 固定 | bootstrap / Ubuntu Base / proot 三包 | 已固定（含官方 digest） |
| termux vendor 补丁逻辑 | 在 `/tmp` 副本执行 `tools/vendor-termux.sh` | namespace / compileSdk / abiFilters / 移除 publishing 全部正确 |
| 脚本语法 | `bash -n` | 3 个脚本通过 |
| workflow YAML | pyyaml | 通过 |
| post-install.sh | `bash -n` + 发行版校验逻辑 | 通过 |

## 待本机验证（无需编译）

- [ ] `ZstdTarExtractor` 的 tar 解析正确性：用真实 Ubuntu Base tarball 在**桌面 JVM**上跑单元测试（不需要 Android）
- [ ] rootfs 首启释放后的 `restoreModesFromManifest` 判定规则：用真实 tar 头验证
- [ ] `ServiceDetector.listenPorts()` 的 `/proc/net/tcp` 解析：用样例数据做单元测试

## 已知风险（尚未验证）

1. **proot deb 内路径假设**：`tools/fetch-deps.sh` 里对 Termux deb 的解包路径做了假设（`data/data/com.termux/files/usr/bin/proot`），
   若 Termux 改了包布局，CI 的「校验关键产物」步骤会失败——已有 `find` 兜底并会在日志中打印实际路径。
2. **Kotlin/Java 编译**：`TerminalSessionClient` / `TerminalViewClient` 的接口实现是按上游源码逐个实现的，
   若上游版本有默认方法差异，CI 编译会报错（这是首次真编译，即为首次真验证）。
3. **termux-shared 是否需要**：当前 app 只显式依赖 `:terminal-emulator` 与 `:terminal-view`，
   未 include `:termux-shared`（若编译报缺类，再补）。
4. **tar 的 PAX 头**：Ubuntu Base tarball 可能带 PAX 扩展头；`ZstdTarExtractor` 目前只处理 ustar 字段，
   遇到 PAX 条目会走 `else -> skip`，可能导致个别文件丢失（首次真机验证时检查）。

## 下一步

1. 等 CI #2 结果：先过编译，再谈产物可用性。
2. 编译通过后，做「本机可验证」的三项单元测试（见上）。
3. 真机验证：安装 APK → 看是否进入 Ubuntu 24.04 shell（阶段 1 验收）。
