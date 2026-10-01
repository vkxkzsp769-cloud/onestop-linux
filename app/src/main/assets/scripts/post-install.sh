#!/bin/bash
# OneStop Linux 首启配置（在 PRoot 容器内执行，rootfs 即 /）
# 方案 §5.1：DNS / apt / 用户 / locale。幂等。
set -e
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# ① DNS（PRoot 下 /etc/resolv.conf 常不可靠）
cat > /etc/resolv.conf <<'EOD'
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 8.8.8.8
options timeout:2 attempts:3 rotate
EOD

# ② 强制 IPv4 优先（apt 卡住的常见真因，方案 §12.3.3）
printf 'precedence ::ffff:0:0/96  100\n' > /etc/gai.conf
mkdir -p /etc/apt/apt.conf.d
cat > /etc/apt/apt.conf.d/99-onestop-network <<'EOA'
Acquire::ForceIPv4 "true";
Acquire::Retries "3";
Acquire::http::Timeout "20";
Acquire::https::Timeout "20";
EOA

# ③ 禁止服务自启（PRoot 无 systemd）
cat > /etc/apt/apt.conf.d/99-onestop <<'EOB'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
EOB
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d

# ④ 默认 shell 环境
mkdir -p /etc/profile.d
cat > /etc/profile.d/00-onestop.sh <<'EOC'
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TERM=xterm-256color
export COLORTERM=truecolor
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
EOC

# ⑤ 校验：必须确实是 Ubuntu 24.04
. /etc/os-release
if [ "$ID" != "ubuntu" ] || [ "${VERSION_ID%%.*}" != "24" ]; then
  echo "FATAL: 内置发行版不是 Ubuntu 24.04 (ID=$ID VERSION_ID=$VERSION_ID)"
  exit 1
fi

echo "post-install OK: $(grep PRETTY_NAME /etc/os-release)"
