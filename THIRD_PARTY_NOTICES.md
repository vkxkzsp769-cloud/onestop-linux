# 第三方组件与许可证

本工程分发或链接以下第三方组件。分发时必须同时提供相应许可证文本与（GPL 组件的）源码获取途径。

| 组件 | 版本 | 许可证 | 出处 |
|---|---|---|---|
| PRoot | 5.1.107.95（Termux 打包） | GPL-2.0-or-later | https://github.com/termux/proot |
| libtalloc | 2.4.3 | LGPL-3.0-or-later | https://talloc.samba.org/ |
| libandroid-shmem | 0.7 | Apache-2.0 | https://github.com/termux/libandroid-shmem |
| Termux bootstrap（bash/coreutils/apt 等） | 2026.09.27-r1 | 各包自身许可（多为 GPL-2.0+/MIT） | https://github.com/termux/termux-packages |
| termux-app: terminal-emulator / terminal-view / termux-shared | v0.118.3 | Apache-2.0 | https://github.com/termux/termux-app |
| Ubuntu Base 24.04.5 LTS (ARM64) | 24.04.5 | 混合自由软件许可（GPL/LGPL/BSD/MIT…） | https://cdimage.ubuntu.com/ubuntu-base/ |
| AndroidX / Material Components | 见 gradle 依赖 | Apache-2.0 | https://developer.android.com/jetpack |
| OkHttp | 4.12.0 | Apache-2.0 | https://square.github.io/okhttp/ |

## 合规要点

1. **PRoot 为 GPL-2.0-or-later**：本仓库分发其二进制，对应源码见上游 tag；本工程的构建脚本与补丁即为其「对应源码」的一部分（未修改 proot 源码）。
2. **termux-app 三模块为 Apache-2.0**：已保留各文件头部版权声明；`tools/vendor-termux.sh` 仅施加构建配置补丁（namespace/compileSdk），不修改业务代码。
3. **Ubuntu Base**：分发的是官方未修改的二进制 tarball；已保留 `/usr/share/doc/*/copyright`。`apt` 安装的软件由用户在容器内自行获取，作者义务随 Ubuntu 官方源。
4. **商标**：本工程不以 "Ubuntu" 作为产品名称；「Ubuntu 24.04 LTS」仅作事实性描述。
5. **GPL-3.0 整体许可**：因后续阶段 3 计划引入 GPL-3.0 的 Termux:X11(lorie) 显示端，本工程整体采用 GPL-3.0，
   以避免混用不兼容许可。若最终不引入该组件，可另行评估改为更宽松许可。
