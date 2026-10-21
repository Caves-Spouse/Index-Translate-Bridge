package com.indextranslate.bridge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 进程内的共享状态：运行标志、端口、最近日志。
 * Activity 与 Service 同进程，静态字段即可互通（无需 AIDL / androidx）。
 */
public final class ProxyState {

    private static final int MAX_LOG = 60;
    private static final LinkedList<String> LOGS = new LinkedList<String>();
    private static volatile boolean running = false;
    private static volatile int port = -1;
    private static volatile String lastError = "";

    private ProxyState() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getPort() {
        return port;
    }

    public static String getLastError() {
        return lastError;
    }

    /** 形如 http://127.0.0.1:8080/v1 */
    public static String getBaseUrl() {
        int p = port;
        return p > 0 ? "http://127.0.0.1:" + p + "/v1" : "";
    }

    static void setRunning(int p) {
        port = p;
        running = p > 0;
        lastError = "";
    }

    static void setStopped() {
        running = false;
        port = -1;
    }

    static void setError(String msg) {
        running = false;
        port = -1;
        lastError = msg == null ? "unknown" : msg;
    }

    static void log(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LOGS) {
            LOGS.addFirst(ts + "  " + line);
            while (LOGS.size() > MAX_LOG) {
                LOGS.removeLast();
            }
        }
    }

    public static List<String> getLogs() {
        synchronized (LOGS) {
            return new ArrayList<String>(LOGS);
        }
    }
}
