package com.workbuddy.wifiadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启接收器。
 *
 * 若用户勾选了「设备重启后自动保持 WiFi ADB」（persist.adb.tcp.port 已写入），
 * 则在系统启动完成后确保 adbd 以 TCP 模式重新拉起。
 *
 * 之所以需要它：adbd 一旦被 stop，该 ROM 的 init 不会自动重新拉起，
 * 必须显式 setprop ctl.start adbd。开机时 init 拉起 adbd 的时机
 * 早于 BOOT_COMPLETED 广播，所以这里在广播里再校正一次。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiAdbBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "BOOT_COMPLETED received, checking adbd state");

        // 读取持久化属性，判断用户是否开启了开机保持
        final String persistPort = WifiAdbController.getPropValue("persist.adb.tcp.port");
        if (persistPort == null || persistPort.isEmpty()) {
            Log.i(TAG, "persist.adb.tcp.port not set, skip");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(persistPort.trim());
        } catch (NumberFormatException e) {
            Log.e(TAG, "bad persist port: " + persistPort);
            return;
        }
        if (port <= 0) {
            return;
        }

        // 延迟几秒，等系统服务完全就绪后再操作 init
        new Thread(() -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "ensuring adbd tcp mode on port " + port);
            boolean ok = WifiAdbController.enableWifiAdb(port, true);
            Log.i(TAG, "ensure result = " + ok);
        }).start();
    }
}
