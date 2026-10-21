package com.indextranslate.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：持有代理线程，保证 App 切到后台/息屏后代理仍然可用。
 * Android 14+ 必须带 foregroundServiceType，否则 startForeground 直接抛异常。
 */
public class ProxyService extends Service {

    public static final String ACTION_START = "com.indextranslate.bridge.START";
    public static final String ACTION_STOP = "com.indextranslate.bridge.STOP";
    public static final String ACTION_STATE = "com.indextranslate.bridge.STATE";

    private static final String CHANNEL_ID = "proxy_status";
    private static final int NOTIF_ID = 1;
    private static final int START_PORT = 8080;
    private static final String UPSTREAM = "https://index-translate.bilibili.com/v1";
    private static final String DEFAULT_MODEL = "Index-Translate-9B";
    private static final String PREFS = "bridge";
    private static final String PREF_LAST_PORT = "last_port";

    /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE 的数值（API 34 才有该常量）。 */
    private static final int FGS_TYPE_SPECIAL_USE = 1073741824;

    private ProxyServer server;
    private boolean starting;

    public static Intent startIntent(Context context) {
        return new Intent(context, ProxyService.class).setAction(ACTION_START);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, ProxyService.class).setAction(ACTION_STOP);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "代理运行状态", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setDescription("显示本地代理端口与地址");
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null) ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        ensureServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 内部

    private void startInForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, FGS_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private synchronized void ensureServer() {
        if (server != null || starting) {
            return;
        }
        starting = true;
        // 优先复用上次成功的端口，尽量让扩展里已填的地址继续有效
        int preferPort = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_PORT, START_PORT);
        if (preferPort <= 0 || preferPort > 65535) {
            preferPort = START_PORT;
        }
        final int startPort = preferPort;
        Thread boot = new Thread(new Runnable() {
            @Override
            public void run() {
                ProxyServer s = new ProxyServer(UPSTREAM, DEFAULT_MODEL, startPort);
                try {
                    int port = s.start();
                    server = s;
                    ProxyState.setRunning(port);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(PREF_LAST_PORT, port).apply();
                    ProxyState.log("就绪 " + ProxyState.getBaseUrl());
                } catch (Exception e) {
                    ProxyState.setError(e.getMessage());
                    ProxyState.log("启动失败: " + e.getMessage());
                } finally {
                    starting = false;
                }
                notifyState();
            }
        }, "proxy-boot");
        boot.setDaemon(true);
        boot.start();
    }

    private synchronized void stopProxy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        ProxyState.setStopped();
        notifyState();
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void notifyState() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (RuntimeException ignored) {
            // 通知权限被拒时忽略
        }
        Intent broadcast = new Intent(ACTION_STATE);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    private Notification buildNotification() {
        boolean running = ProxyState.isRunning();
        String title = running ? "代理运行中 · 端口 " + ProxyState.getPort() : "代理启动中…";
        String text = running ? ProxyState.getBaseUrl() : "正在分配本地端口";

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(running)
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "停止代理", stop).build());
        return builder.build();
    }
}
