package com.workbuddy.wifiadb;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设备端 WiFi ADB 开关界面。
 *
 * 设备是一台教育机器人（小智语音助手 + 教育应用），使用者可能是非技术用户，
 * 因此界面走大字、大按钮、高对比、状态一目了然的路子。
 */
public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvIp;
    private TextView tvCommand;
    private TextView tvDetail;
    private Button btnToggle;
    private Button btnRefresh;
    private Button btnCopy;
    private CheckBox cbKeep;
    private ProgressBar progress;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean wifiAdbOn = false;
    private boolean keepOnReboot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvIp = findViewById(R.id.tv_ip);
        tvCommand = findViewById(R.id.tv_command);
        tvDetail = findViewById(R.id.tv_detail);
        btnToggle = findViewById(R.id.btn_toggle);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnCopy = findViewById(R.id.btn_copy);
        cbKeep = findViewById(R.id.cb_keep);
        progress = findViewById(R.id.progress);

        cbKeep.setOnCheckedChangeListener((v, checked) -> keepOnReboot = checked);

        btnToggle.setOnClickListener(v -> toggleWifiAdb());
        btnRefresh.setOnClickListener(v -> refreshState());
        btnCopy.setOnClickListener(v -> copyCommand());

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    /** 读取当前状态并刷新界面。 */
    private void refreshState() {
        setBusy(true, "正在检测状态…");
        executor.execute(() -> {
            final boolean on = WifiAdbController.isWifiAdbEnabled();
            final int port = WifiAdbController.getAdbPort();
            final String ip = WifiAdbController.getWifiIp();
            final boolean wifiConnected = WifiAdbController.isWifiConnected();

            main.post(() -> {
                setBusy(false, null);
                wifiAdbOn = on;

                int p = port > 0 ? port : WifiAdbController.ADB_PORT;
                if (on) {
                    tvStatus.setText("WiFi ADB 已开启");
                    tvStatus.setTextColor(color(R.color.status_on));
                    btnToggle.setText("关闭 WiFi ADB");
                    btnToggle.setBackgroundColor(color(R.color.btn_off));
                } else {
                    tvStatus.setText("WiFi ADB 已关闭");
                    tvStatus.setTextColor(color(R.color.status_off));
                    btnToggle.setText("开启 WiFi ADB");
                    btnToggle.setBackgroundColor(color(R.color.btn_on));
                }

                if (!wifiConnected || ip == null) {
                    tvIp.setText("本机 IP：未连接 WiFi");
                    tvCommand.setText("请先连接 WiFi 网络");
                    btnCopy.setEnabled(false);
                    tvDetail.setText("设备当前未接入 WiFi，无法通过无线方式连接调试。");
                } else {
                    tvIp.setText("本机 IP：" + ip);
                    tvCommand.setText("adb connect " + ip + ":" + p);
                    btnCopy.setEnabled(true);
                    tvDetail.setText("在电脑终端执行上面的命令即可通过 WiFi 连接本设备。\n"
                            + "连接后拔掉 USB 数据线也不影响使用。");
                    if (keepOnReboot) {
                        tvDetail.setText(tvDetail.getText() + "\n已设置：设备重启后自动恢复 WiFi ADB。");
                    }
                }
            });
        });
    }

    /** 开启 / 关闭 WiFi ADB。 */
    private void toggleWifiAdb() {
        final boolean targetOn = !wifiAdbOn;
        setBusy(true, targetOn ? "正在开启 WiFi ADB…" : "正在关闭 WiFi ADB…");
        btnToggle.setEnabled(false);

        executor.execute(() -> {
            final boolean ok;
            if (targetOn) {
                ok = WifiAdbController.enableWifiAdb(WifiAdbController.ADB_PORT, keepOnReboot);
            } else {
                ok = WifiAdbController.disableWifiAdb();
            }
            final boolean nowOn = WifiAdbController.isWifiAdbEnabled();
            final String ip = WifiAdbController.getWifiIp();

            main.post(() -> {
                setBusy(false, null);
                btnToggle.setEnabled(true);

                if (ok) {
                    Toast.makeText(this,
                            targetOn ? "WiFi ADB 已开启" : "WiFi ADB 已关闭",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            targetOn ? "开启失败，请重试" : "关闭失败，请重试",
                            Toast.LENGTH_LONG).show();
                }

                wifiAdbOn = nowOn;
                if (nowOn) {
                    tvStatus.setText("WiFi ADB 已开启");
                    tvStatus.setTextColor(color(R.color.status_on));
                    btnToggle.setText("关闭 WiFi ADB");
                    btnToggle.setBackgroundColor(color(R.color.btn_off));
                } else {
                    tvStatus.setText("WiFi ADB 已关闭");
                    tvStatus.setTextColor(color(R.color.status_off));
                    btnToggle.setText("开启 WiFi ADB");
                    btnToggle.setBackgroundColor(color(R.color.btn_on));
                }
                if (ip != null) {
                    tvIp.setText("本机 IP：" + ip);
                    tvCommand.setText("adb connect " + ip + ":" + WifiAdbController.ADB_PORT);
                }
            });
        });
    }

    /**
     * 取颜色资源。
     *
     * Activity.getColor(int) 是 API 23 才引入的，而目标设备是 Android 5.1 (API 22)，
     * 必须走 Resources.getColor(int) 这条老路径，否则会抛 NoSuchMethodError。
     */
    private int color(int resId) {
        return getResources().getColor(resId);
    }

    private void copyCommand() {
        String cmd = tvCommand.getText().toString();
        if (TextUtils.isEmpty(cmd)) {
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd));
            Toast.makeText(this, "已复制连接命令", Toast.LENGTH_SHORT).show();
        }
    }

    private void setBusy(boolean busy, String msg) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy && msg != null) {
            tvDetail.setText(msg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
