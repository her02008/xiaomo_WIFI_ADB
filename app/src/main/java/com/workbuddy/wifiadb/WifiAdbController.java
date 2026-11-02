package com.workbuddy.wifiadb;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * WiFi ADB 控制核心。
 *
 * 该设备 (小墨机器人 <device-model>, Android 5.1.1 userdebug) 的实测特性：
 *
 *  1. SELinux 为 Disabled，property service 几乎不拦截 setprop。
 *     App 进程继承了 adb 组 (gid 1011)，实测可以直接
 *     `setprop service.adb.tcp.port 5555` 和 `setprop ctl.stop adbd`。
 *
 *  2. su (/system/xbin/su) 是 AOSP 原版 ALLOW_SHELL 模式，硬编码只允许
 *     uid 0(root) / uid 2000(shell) 提权，普通 App 调用会被拒：
 *         su: uid <app-uid> not allowed to su
 *     因此本实现**不依赖 su**，改走 init 的 ctl.* 控制通道。
 *
 *  3. 该 ROM 的 init 在 adbd 被 stop 后不会自动重新拉起它，
 *     必须显式 `setprop ctl.start adbd`。
 *
 *  4. `setprop ctl.stop adbd` 会让 adbd 立刻退出，App 自身不受影响
 *     （App 是普通进程，不依赖 adbd 存活），所以可以放心在 App 内串联执行。
 */
public final class WifiAdbController {

    private static final String TAG = "WifiAdb";
    public static final int ADB_PORT = 5555;

    private WifiAdbController() {
    }

    // ------------------------------------------------------------------
    // 命令执行
    // ------------------------------------------------------------------

    /**
     * 执行一条命令并返回标准输出。失败返回 null。
     *
     * 注意：不要用 ; 串联多条命令 —— 必须逐条执行，
     * 否则部分 ROM 的 shell 会把整串当成一个文件名而报错。
     */
    public static String exec(String command) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            // 关闭 stdin，避免命令等待输入而挂起
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // ignore
            }

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
            p.waitFor();
            String text = out.toString().trim();
            Log.i(TAG, "exec[" + command + "] -> " + text);
            return text;
        } catch (Exception e) {
            Log.e(TAG, "exec failed: " + command, e);
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 读取一条系统属性。 */
    private static String getProp(String key) {
        String out = exec("getprop " + key);
        if (out == null) {
            return null;
        }
        out = out.trim();
        return out.isEmpty() ? null : out;
    }

    /** 读取一条系统属性，不存在时返回 null。对外公开供 BootReceiver 使用。 */
    public static String getPropValue(String key) {
        return getProp(key);
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    /** 当前 adbd 是否已在 TCP 模式（service.adb.tcp.port 有有效值）。 */
    public static boolean isWifiAdbEnabled() {
        int port = getAdbPort();
        return port > 0;
    }

    /** 读取当前 adbd 监听的 TCP 端口，未开启返回 -1。 */
    public static int getAdbPort() {
        String port = getProp("service.adb.tcp.port");
        if (port == null) {
            return -1;
        }
        try {
            int p = Integer.parseInt(port);
            return p > 0 ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 开启 / 关闭 WiFi ADB
    // ------------------------------------------------------------------

    /**
     * 开启 WiFi ADB。
     *
     * 流程：写属性 → 重启 adbd → 校验端口真的在监听。
     * 用 ctl.stop / ctl.start 而不是 kill，行为可控且不依赖进程查找。
     *
     * @param port     监听端口
     * @param persist  是否写入持久化属性（重启后仍保持 WiFi ADB）
     */
    public static boolean enableWifiAdb(int port, boolean persist) {
        // 1. 先把端口属性写好，adbd 下次启动即生效
        if (exec("setprop service.adb.tcp.port " + port) == null) {
            Log.e(TAG, "setprop service.adb.tcp.port failed");
            return false;
        }
        if (persist) {
            exec("setprop persist.adb.tcp.port " + port);
        }

        // 2. 重启 adbd 让新属性生效
        //    注意：stop 之后 adbd 已退出，start 必须单独再执行一条命令
        exec("setprop ctl.stop adbd");
        sleep(1500);
        exec("setprop ctl.start adbd");
        sleep(3000);

        // 3. 校验
        if (isWifiAdbEnabled() && isPortListening(port)) {
            return true;
        }

        // 4. 兜底：某些情况下第一次 start 可能撞上 init 尚未完成 stop 的处理
        sleep(1500);
        exec("setprop ctl.start adbd");
        sleep(3000);
        return isWifiAdbEnabled() && isPortListening(port);
    }

    /**
     * 关闭 WiFi ADB，回到仅 USB 模式。
     *
     * 需要同时清掉即时属性和持久化属性，否则重启后又会被拉起。
     */
    public static boolean disableWifiAdb() {
        exec("setprop service.adb.tcp.port ''");
        exec("setprop persist.adb.tcp.port ''");

        exec("setprop ctl.stop adbd");
        sleep(1500);
        exec("setprop ctl.start adbd");
        sleep(3000);

        return !isWifiAdbEnabled();
    }

    /** 本机回环测试端口是否真的在监听。 */
    private static boolean isPortListening(int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 网络信息
    // ------------------------------------------------------------------

    /** 获取设备当前的 WiFi IPv4 地址，无连接返回 null。 */
    public static String getWifiIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis == null) {
                return null;
            }
            List<NetworkInterface> list = Collections.list(nis);
            // 优先 wlan0
            NetworkInterface target = null;
            for (NetworkInterface ni : list) {
                String name = ni.getName();
                if (name != null && name.startsWith("wlan")) {
                    target = ni;
                    break;
                }
            }
            if (target == null) {
                for (NetworkInterface ni : list) {
                    if (ni.isLoopback() || !ni.isUp()) {
                        continue;
                    }
                    target = ni;
                    break;
                }
            }
            if (target == null) {
                return null;
            }
            Enumeration<InetAddress> addrs = target.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getWifiIp failed", e);
        }
        return null;
    }

    /** WiFi 是否已连接到路由器。 */
    public static boolean isWifiConnected() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.getName() != null && ni.getName().startsWith("wlan")
                        && ni.isUp() && !ni.isLoopback()) {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
