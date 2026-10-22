package com.indextranslate.bridge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 单页界面：打开即启动代理，显示地址、支持一键复制与自检。
 * 全程用代码构建 UI，不依赖 androidx / 布局 XML。
 */
public class MainActivity extends Activity {

    private TextView statusView;
    private TextView urlView;
    private TextView logView;
    private Button toggleButton;

    private final Handler handler = new Handler();
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationPermissionIfNeeded();
        // 点图标即启动
        startProxy();
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(ProxyService.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        }
        handler.post(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Index-Translate 本地代理桥");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("把沉浸式翻译的请求转发到 bilibili Index-Translate 官方接口（默认 9B）\n仅监听 127.0.0.1，不对外暴露；端口被占用会自动换端口。");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusView);

        urlView = new TextView(this);
        urlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextIsSelectable(true);
        urlView.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(8);
        root.addView(urlView, urlLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(14), 0, 0);
        toggleButton = new Button(this);
        toggleButton.setText("重启代理");
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityRefresh();
            }
        });
        Button stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(ProxyService.stopIntent(MainActivity.this));
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshUi();
                    }
                }, 400);
            }
        });
        row1.addView(toggleButton, weight());
        row1.addView(stopButton, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, dp(14));
        Button copyButton = new Button(this);
        copyButton.setText("复制地址");
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyUrl();
            }
        });
        Button checkButton = new Button(this);
        checkButton.setText("自检");
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfCheck();
            }
        });
        row2.addView(copyButton, weight());
        row2.addView(checkButton, weight());
        root.addView(row2);

        TextView steps = new TextView(this);
        steps.setText("沉浸式翻译里这样填：\n"
                + "  翻译服务：OpenAI 兼容 / 自定义 OpenAI\n"
                + "  接口地址：上面那条 URL（结尾是 /v1）\n"
                + "  模型：Index-Translate-9B\n"
                + "  API Key：随便填，例如 index");
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        steps.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(steps);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setPadding(0, dp(14), 0, dp(4));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        TextView footer = new TextView(this);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setTextColor(0xFF9E9E9E);
        footer.setPadding(0, dp(18), 0, dp(4));
        footer.setText(versionLine());
        root.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        lp.leftMargin = dp(4);
        return lp;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    // ------------------------------------------------------------ 行为

    private void startActivityRefresh() {
        // 先停掉旧实例，再重新启动，确保端口重新协商
        stopService(ProxyService.stopIntent(this));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startProxy();
            }
        }, 300);
    }

    private void startProxy() {
        Intent intent = ProxyService.startIntent(this);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }
    }

    private void copyUrl() {
        String url = ProxyState.getBaseUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "代理还没就绪，稍等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("proxy-url", url));
        Toast.makeText(this, "已复制: " + url, Toast.LENGTH_SHORT).show();
    }

    private void runSelfCheck() {
        final String base = ProxyState.getBaseUrl();
        if (base.isEmpty()) {
            Toast.makeText(this, "代理还没就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在自检…", Toast.LENGTH_SHORT).show();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(base + "/models").openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    int code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    byte[] buf = new byte[512];
                    int n = in == null ? -1 : in.read(buf);
                    conn.disconnect();
                    result = code == 200
                            ? "自检通过 ✓ HTTP 200"
                            : "自检失败: HTTP " + code + (n > 0 ? " " + new String(buf, 0, n) : "");
                } catch (Exception e) {
                    result = "自检失败: " + e.getMessage();
                }
                final String finalResult = result;
                ProxyState.log(finalResult);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, finalResult, Toast.LENGTH_LONG).show();
                        refreshUi();
                    }
                });
            }
        }, "self-check");
        t.setDaemon(true);
        t.start();
    }

    private String versionLine() {
        String name = "?";
        long code = -1;
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            name = info.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            // 取不到版本信息时显示占位符
        }
        return "v" + name + " (build " + code + ") · 仅监听 127.0.0.1 · Index-Translate 本地代理桥";
    }

    private void refreshUi() {
        boolean running = ProxyState.isRunning();
        if (running) {
            statusView.setText("● 运行中 · 端口 " + ProxyState.getPort());
            statusView.setTextColor(0xFF2E7D32);
            urlView.setText(ProxyState.getBaseUrl());
            urlView.setBackgroundColor(0xFFE8F5E9);
            toggleButton.setText("重启代理");
        } else {
            String err = ProxyState.getLastError();
            statusView.setText(err == null || err.isEmpty() ? "○ 未运行" : "● 未运行 · " + err);
            statusView.setTextColor(0xFFC62828);
            urlView.setText("—");
            urlView.setBackgroundColor(0xFFFFEBEE);
            toggleButton.setText("启动代理");
        }

        List<String> logs = ProxyState.getLogs();
        StringBuilder sb = new StringBuilder();
        int count = Math.min(logs.size(), 12);
        for (int i = 0; i < count; i++) {
            sb.append(logs.get(i)).append('\n');
        }
        logView.setText(sb.length() == 0 ? "（暂无日志）" : sb.toString());
    }
}
