#!/usr/bin/env bash
# WiFi ADB 自检：验证设备状态、App 安装情况与开关功能
#
# 用法: ./verify.sh
#
# 检查项:
#   1. 设备连接状态（USB / WiFi）
#   2. adbd 属性与服务状态
#   3. WiFi ADB App 是否已安装
#   4. 端口监听情况
#
# 环境变量: DEVICE_IP / ADB_PORT / ADB，含义同 common.sh

set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

FAIL_COUNT=0
check() {
    local name="$1" ok_flag="$2" detail="${3:-}"
    if [ "$ok_flag" = "yes" ]; then
        printf "  [✓] %-28s %s\n" "$name" "$detail"
    else
        printf "  [x] %-28s %s\n" "$name" "$detail"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
}

echo "=== 小墨机器人 WiFi ADB 自检 ==="
echo

# ---------- 1. 设备连接 ----------
echo "1. 设备连接"
DEV=$(resolve_device) || DEV=""
if [ -n "$DEV" ]; then
    check "adb 设备连接" yes "$DEV"
else
    check "adb 设备连接" no "未找到设备"
    echo
    fail "没有设备可检，后续检查跳过"
    exit 1
fi

# ---------- 2. adbd 状态 ----------
echo
echo "2. adbd 状态"
SVC=$(device_prop "$DEV" init.svc.adbd)
PORT_PROP=$(device_prop "$DEV" service.adb.tcp.port)
PERSIST=$(device_prop "$DEV" persist.adb.tcp.port)

[ "$SVC" = "running" ] && check "init.svc.adbd" yes "$SVC" || check "init.svc.adbd" no "$SVC"

if [ -n "$PORT_PROP" ] && [ "$PORT_PROP" != "0" ]; then
    check "service.adb.tcp.port" yes "$PORT_PROP (WiFi ADB 已开启)"
else
    check "service.adb.tcp.port" no "[空] (WiFi ADB 已关闭)"
fi

if [ -n "$PERSIST" ]; then
    check "persist.adb.tcp.port" yes "$PERSIST (重启后保持)"
else
    check "persist.adb.tcp.port" no "[空] (重启后不保持)"
fi

# ---------- 3. App 安装 ----------
echo
echo "3. App 安装"
PKG="com.workbuddy.wifiadb"
if "$ADB" -s "$DEV" shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -q "package:$PKG"; then
    check "WiFi ADB App" yes "$PKG"
    VER=$("$ADB" -s "$DEV" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -m1 "versionName" | awk '{print $3}')
    [ -n "$VER" ] && echo "        版本: $VER"
else
    check "WiFi ADB App" no "未安装 ($PKG)"
fi

# ---------- 4. 端口监听 ----------
echo
echo "4. 端口监听"
if [ -z "$DEVICE_IP" ]; then
    check "端口监听" no "未指定 DEVICE_IP，跳过（用 DEVICE_IP=<ip> 运行可检查）"
elif timeout 3 bash -c "exec 3<>/dev/tcp/${DEVICE_IP}/${ADB_PORT}" 2>/dev/null; then
    check "${DEVICE_IP}:${ADB_PORT}" yes "可连接"
else
    check "${DEVICE_IP}:${ADB_PORT}" no "不可连接"
fi

# ---------- 5. 设备信息 ----------
echo
echo "5. 设备信息"
echo "    model   = $(device_prop "$DEV" ro.product.model)"
echo "    android = $(device_prop "$DEV" ro.build.version.release)"
echo "    sdk     = $(device_prop "$DEV" ro.build.version.sdk)"
echo "    type    = $(device_prop "$DEV" ro.build.type)"

echo
if [ "$FAIL_COUNT" -eq 0 ]; then
    ok "全部检查通过"
else
    fail "${FAIL_COUNT} 项检查未通过"
fi
exit "$FAIL_COUNT"
