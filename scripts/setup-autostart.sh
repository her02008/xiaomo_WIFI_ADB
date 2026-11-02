#!/usr/bin/env bash
# 在小墨机器人上部署「开机自动进入 WiFi ADB」能力。
#
# 原理：把 persist.adb.tcp.port 写入 /data/property（持久化），
#        重启后 init 拉起 adbd 时会读取该属性，adbd 自动以 TCP 模式监听。
#        设备上的 WiFi ADB App 勾选「设备重启后自动保持 WiFi ADB」也是同一机制。
#
# 前提：设备当前已有 adb 连接（USB 或 WiFi）。
#       该设备 SELinux 为 Disabled，App / shell 均可直接 setprop，无需 su。
#
# 用法: ./setup-autostart.sh

set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

DEV=$(resolve_device) || {
    warn "未找到 adb 设备。请先连接设备（USB 或 WiFi）。"
    exit 1
}
ok "设备: ${DEV}"

info "写入持久化属性 persist.adb.tcp.port=${ADB_PORT} ..."
"$ADB" -s "$DEV" shell setprop persist.adb.tcp.port "${ADB_PORT}" >/dev/null 2>&1

info "写入即时属性 service.adb.tcp.port=${ADB_PORT} ..."
"$ADB" -s "$DEV" shell setprop service.adb.tcp.port "${ADB_PORT}" >/dev/null 2>&1

echo
info "当前属性状态:"
echo "    service.adb.tcp.port = [$(device_prop "$DEV" service.adb.tcp.port)]"
echo "    persist.adb.tcp.port = [$(device_prop "$DEV" persist.adb.tcp.port)]"

cat <<EOF

[✓] 部署完成。

下次设备重启后，adbd 会自动以 TCP 模式监听 ${ADB_PORT}，
无需再插 USB，直接在电脑上执行：
    adb connect <你的设备IP>:${ADB_PORT}

如需恢复 USB-only 模式：
    adb shell setprop service.adb.tcp.port ''
    adb shell setprop persist.adb.tcp.port ''
    adb shell setprop ctl.stop adbd
    adb shell setprop ctl.start adbd
EOF
