package com.indextranslate.bridge;

/**
 * 仅用于开发期验证：把 APK 里的 ProxyServer/ProxyState 原样跑在桌面 JVM 上，
 * 用 curl 打同样的接口，确认代理逻辑与 Node 版一致（两者都只依赖 JDK 标准库）。
 */
public final class ProxyMain {

    public static void main(String[] args) throws Exception {
        int startPort = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        ProxyServer server = new ProxyServer(
                "https://index-translate.bilibili.com/v1", "Index-Translate-9B", startPort);
        int port = server.start();
        System.out.println("PORT=" + port);
        System.out.flush();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                for (String line : ProxyState.getLogs()) {
                    System.out.println("LOG " + line);
                }
            }
        }));
        Thread.sleep(Long.MAX_VALUE);
    }
}
