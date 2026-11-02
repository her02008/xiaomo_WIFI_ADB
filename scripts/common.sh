#!/usr/bin/env bash
# 公共配置：所有脚本 source 本文件
#
# 用法: source "$(dirname "$0")/common.sh"

# ---------- 项目路径 ----------
# 脚本所在目录（scripts/ 的父目录即项目根）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 设备参数 ----------
# 设备 IP 需按实际环境指定，未设置时用占位值（脚本会提示）
# 用法: DEVICE_IP=192.168.1.50 ./scripts/verify.sh
DEVICE_IP="${DEVICE_IP:-}"
ADB_PORT="${ADB_PORT:-5555}"

# ---------- adb 可执行文件定位 ----------
# 优先级: 环境变量 ADB > PATH 中的 adb > 常见安装位置
find_adb() {
    if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then
        echo "$ADB"
        return 0
    fi
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return 0
    fi
    # 常见安装位置（按序探测）。这些只是回退候选，
    # 优先用 PATH 中的 adb 或环境变量 ADB 指定的位置。
    local candidates=(
        "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        "/d/Install_Programs/Android/SDK/platform-tools/adb.exe"
        "/c/Install_Programs/Android/SDK/platform-tools/adb.exe"
        "$HOME/Android/Sdk/platform-tools/adb"
        "/usr/local/bin/adb"
    )
    for c in "${candidates[@]}"; do
        if [ -x "$c" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

ADB="$(find_adb)" || {
    echo "[!] 找不到 adb。请安装 Android platform-tools，或设置环境变量 ADB 指向 adb 可执行文件。" >&2
    exit 1
}

# Git Bash / MSYS 下必须关闭路径转换，否则 /system/... 会被转成 Windows 路径
export MSYS_NO_PATHCONV=1

# ---------- 输出辅助 ----------
info()  { echo "[*] $*"; }
ok()    { echo "[+] $*"; }
warn()  { echo "[!] $*"; }
fail()  { echo "[x] $*"; }

# ---------- 设备连接辅助 ----------
# 找出当前可用的设备序列号（优先 USB，其次 WiFi）
resolve_device() {
    local usb
    usb=$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | grep -v ':' | head -1)
    if [ -n "$usb" ]; then
        echo "$usb"
        return 0
    fi
    # 无 USB 通道时才需要 DEVICE_IP 去连 WiFi
    if [ -n "$DEVICE_IP" ] && "$ADB" connect "${DEVICE_IP}:${ADB_PORT}" 2>&1 | grep -q "connected"; then
        echo "${DEVICE_IP}:${ADB_PORT}"
        return 0
    fi
    return 1
}

# 提示未设置设备 IP（仅在需要走 WiFi 通道时调用）
require_device_ip() {
    if [ -z "$DEVICE_IP" ]; then
        warn "未指定设备 IP，且当前没有 USB 通道。"
        warn "请用环境变量指定后重试，例如："
        warn "    DEVICE_IP=<你的设备IP> $0"
        return 1
    fi
    return 0
}

# 读取设备属性
device_prop() {
    local dev="$1" key="$2"
    "$ADB" -s "$dev" shell getprop "$key" 2>/dev/null | tr -d '\r'
}
