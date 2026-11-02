#!/usr/bin/env bash
# WiFi ADB 一键开启 / 关闭（电脑端操作）
#
# 用法:
#   ./recover.sh          # 开启 WiFi ADB
#   ./recover.sh usb      # 关闭 WiFi ADB，回到纯 USB 模式
#   ./recover.sh status   # 查看当前状态
#
# 环境变量:
#   DEVICE_IP   设备 IP（可选；仅在需要走 WiFi 通道时需要）
#   ADB_PORT    端口（默认 5555）
#   ADB         adb 可执行文件路径（默认自动探测）

set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

echo "=== 小墨机器人 WiFi ADB 工具 ==="

DEV=$(resolve_device) || {
    warn "找不到设备。请插好 USB 数据线，或重启设备后再试。"
    require_device_ip || true
    exit 1
}
ok "设备通道: ${DEV}"

case "${1:-on}" in
    status)
        info "service.adb.tcp.port = [$(device_prop "$DEV" service.adb.tcp.port)]"
        info "persist.adb.tcp.port = [$(device_prop "$DEV" persist.adb.tcp.port)]"
        info "init.svc.adbd        = [$(device_prop "$DEV" init.svc.adbd)]"
        echo
        "$ADB" devices -l
        ;;

    usb)
        info "关闭 WiFi ADB，恢复 USB 模式..."
        # 两条命令必须分开执行：stop 之后 adbd 立刻退出，
        # start 若与 stop 同处一条命令将永远送不出去。
        "$ADB" -s "$DEV" shell setprop service.adb.tcp.port '' >/dev/null 2>&1
        "$ADB" -s "$DEV" shell setprop persist.adb.tcp.port '' >/dev/null 2>&1
        "$ADB" -s "$DEV" shell setprop ctl.stop adbd >/dev/null 2>&1
        sleep 2
        "$ADB" -s "$DEV" shell setprop ctl.start adbd >/dev/null 2>&1
        sleep 3
        ok "已关闭 WiFi ADB"
        [ -n "$DEVICE_IP" ] && "$ADB" disconnect "${DEVICE_IP}:${ADB_PORT}" >/dev/null 2>&1
        ;;

    on|*)
        info "开启 WiFi ADB..."
        "$ADB" -s "$DEV" shell setprop service.adb.tcp.port "${ADB_PORT}" >/dev/null 2>&1
        "$ADB" -s "$DEV" shell setprop persist.adb.tcp.port "${ADB_PORT}" >/dev/null 2>&1
        info "重启 adbd 以应用新端口..."
        "$ADB" -s "$DEV" shell setprop ctl.stop adbd >/dev/null 2>&1
        sleep 2
        "$ADB" -s "$DEV" shell setprop ctl.start adbd >/dev/null 2>&1
        sleep 3

        echo
        info "属性状态: service.adb.tcp.port = [$(device_prop "$DEV" service.adb.tcp.port)]"
        echo
        info "当前连接:"
        "$ADB" devices -l
        if [ -n "$DEVICE_IP" ]; then
            echo
            ok "在电脑上执行: adb connect ${DEVICE_IP}:${ADB_PORT}"
        else
            echo
            ok "在电脑上执行: adb connect <你的设备IP>:${ADB_PORT}"
        fi
        ;;

esac
