package com.indextranslate.bridge;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 OpenAI 兼容代理（零第三方依赖）。
 *
 * 职责：
 *   1. 只监听 127.0.0.1，端口被占用时自动 +1 重试；
 *   2. 处理 CORS 预检，响应头一律带 Access-Control-Allow-*（浏览器扩展必需）；
 *   3. 把 /v1/chat/completions 转发到上游，SSE 边收边发；
 *   4. 自动补齐 model / temperature=0 / chat_template_kwargs.enable_thinking=false。
 */
public final class ProxyServer {

    private static final int PORT_TRIES = 30;
    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final long MAX_BODY_BYTES = 32L * 1024 * 1024;
    private static final int MAX_JSON_RESPONSE = 16 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 180000;

    private static final String CORS_HEADERS =
            "Access-Control-Allow-Origin: *\r\n"
          + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
          + "Access-Control-Allow-Headers: *\r\n"
          + "Access-Control-Expose-Headers: *\r\n"
          + "Access-Control-Max-Age: 86400\r\n";

    private final String upstreamBase;
    private final String defaultModel;
    private final int startPort;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private ExecutorService pool;

    public ProxyServer(String upstreamBase, String defaultModel, int startPort) {
        this.upstreamBase = trimSlash(upstreamBase);
        this.defaultModel = defaultModel;
        this.startPort = startPort;
    }

    // ------------------------------------------------------------ 生命周期

    public synchronized int start() throws IOException {
        IOException last = null;
        for (int p = startPort; p < startPort + PORT_TRIES; p++) {
            ServerSocket s = null;
            try {
                s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50);
                serverSocket = s;
                running = true;
                if (p != startPort) {
                    ProxyState.log("端口 " + startPort + " 被占用，已改用 " + p);
                }
                ProxyState.log("监听 127.0.0.1:" + p);
                break;
            } catch (IOException e) {
                last = e;
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // 忽略关闭异常
                    }
                }
                ProxyState.log("端口 " + p + " 不可用，尝试 " + (p + 1));
            }
        }
        if (!running) {
            throw last != null ? last : new IOException("没有可用端口");
        }
        pool = Executors.newCachedThreadPool();
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        ProxyState.setStopped();
        ProxyState.log("代理已停止");
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    ProxyState.log("accept 失败: " + e.getMessage());
                }
                return;
            }
            ExecutorService p = pool;
            if (p == null) {
                closeQuietly(socket);
                return;
            }
            try {
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(socket);
            }
        }
    }

    // ------------------------------------------------------------ 单请求处理

    private void handle(Socket socket) {
        String tag = "?";
        long started = System.currentTimeMillis();
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 8192);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            String[] parts = requestLine.trim().split(" +");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            Map<String, String> headers = readHeaders(in);
            String path = normalizePath(parts[1]);
            tag = method + " " + path;
            byte[] body = readBody(in, headers);

            if ("OPTIONS".equals(method)) {
                writeSimple(out, 204, null, null);
                return;
            }
            if ("GET".equals(method) || "HEAD".equals(method)) {
                if ("/models".equals(path)) {
                    writeSimple(out, 200, "application/json; charset=utf-8", modelsJson());
                } else if ("/".equals(path) || "/health".equals(path)) {
                    writeSimple(out, 200, "application/json; charset=utf-8", healthJson());
                } else {
                    writeSimple(out, 404, "application/json; charset=utf-8",
                            errorJson("unknown path: " + path));
                }
                return;
            }
            if (!"POST".equals(method)) {
                writeSimple(out, 405, "application/json; charset=utf-8",
                        errorJson("method not allowed: " + method));
                return;
            }
            if (!isForwardPath(path)) {
                writeSimple(out, 404, "application/json; charset=utf-8",
                        errorJson("unknown path: " + path + " (try /v1/chat/completions)"));
                return;
            }
            forward(out, path, body);
            ProxyState.log(tag + " ok " + (System.currentTimeMillis() - started) + "ms");
        } catch (IOException e) {
            ProxyState.log(tag + " 连接异常: " + e.getMessage());
        } catch (RuntimeException e) {
            ProxyState.log(tag + " 处理异常: " + e.getMessage());
        } finally {
            closeQuietly(socket);
        }
    }

    /** 转发到上游；SSE 边收边发，普通 JSON 缓冲后带 Content-Length 回写。 */
    private void forward(OutputStream out, String path, byte[] body) throws IOException {
        byte[] payload = preparePayload(body);
        HttpURLConnection conn = null;
        boolean headSent = false;
        try {
            URL url = new URL(upstreamBase + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 上游对 UA 敏感，固定的客户端 UA 可避免反爬 412
            conn.setRequestProperty("User-Agent", "Index-Translate-Client/1.0");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setFixedLengthStreamingMode(payload.length);

            OutputStream upstreamOut = conn.getOutputStream();
            upstreamOut.write(payload);
            upstreamOut.flush();
            upstreamOut.close();

            int code = conn.getResponseCode();
            String contentType = conn.getContentType();
            boolean sse = contentType != null
                    && contentType.toLowerCase(Locale.US).contains("text/event-stream");
            InputStream resp = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (resp == null) {
                resp = conn.getErrorStream();
            }

            if (resp == null) {
                writeSimple(out, 502, "application/json; charset=utf-8",
                        errorJson("upstream returned " + code + " with no body"));
                return;
            }

            if (sse) {
                writeHead(out, code, contentType, -1);
                headSent = true;
                copyStream(resp, out, true, Long.MAX_VALUE);
            } else {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                copyStream(resp, buffer, true, MAX_JSON_RESPONSE);
                byte[] data = buffer.toByteArray();
                writeHead(out, code, contentType == null ? "application/json; charset=utf-8" : contentType,
                        data.length);
                headSent = true;
                out.write(data);
                out.flush();
            }
            resp.close();
        } catch (IOException e) {
            ProxyState.log("上游请求失败: " + e.getMessage());
            // 已经发过头就不能再发状态行，否则会把响应写坏
            if (!headSent) {
                try {
                    writeSimple(out, 502, "application/json; charset=utf-8",
                            errorJson("upstream unreachable: " + e.getMessage()));
                } catch (IOException ignored) {
                    // 客户端已断开
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 补齐上游需要的默认字段。用文本外科手术而不是引 JSON 库：
     * 请求体永远是单个 JSON 对象，插在首字符之后即合法。
     */
    private byte[] preparePayload(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return ("{\"model\":\"" + defaultModel + "\",\"temperature\":0,"
                    + "\"chat_template_kwargs\":{\"enable_thinking\":false}}").getBytes(utf8());
        }
        String json;
        try {
            json = new String(raw, "UTF-8").trim();
        } catch (java.io.UnsupportedEncodingException e) {
            return raw;
        }
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return raw;
        }
        String inner = json.substring(1, json.length() - 1).trim();

        List<String> add = new ArrayList<String>();
        if (!json.contains("\"model\"")) {
            add.add("\"model\":\"" + defaultModel + "\"");
        }
        if (!json.contains("\"temperature\"")) {
            add.add("\"temperature\":0");
        }
        if (!json.contains("\"chat_template_kwargs\"")) {
            // 关闭思考：否则 CoT 文本可能取代译文
            add.add("\"chat_template_kwargs\":{\"enable_thinking\":false}");
        }
        if (add.isEmpty()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < add.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(add.get(i));
        }
        if (inner.length() > 0) {
            sb.append(',').append(inner);
        }
        sb.append('}');
        return sb.toString().getBytes(utf8());
    }

    private static java.nio.charset.Charset utf8() {
        return java.nio.charset.Charset.forName("UTF-8");
    }

    // ------------------------------------------------------------ HTTP 读

    /** 读取一行（CRLF 结尾），返回去掉 \r\n 的字符串；EOF 返回 null。 */
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > MAX_HEAD_BYTES) {
                throw new IOException("header line too long");
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        try {
            return buf.toString("ISO-8859-1");
        } catch (java.io.UnsupportedEncodingException e) {
            return buf.toString();
        }
    }

    private Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        int total = 0;
        while (true) {
            String line = readLine(in);
            if (line == null || line.length() == 0) {
                return headers;
            }
            total += line.length();
            if (total > MAX_HEAD_BYTES) {
                throw new IOException("headers too large");
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                        line.substring(idx + 1).trim());
            }
        }
    }

    private byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            return readChunked(in);
        }
        String cl = headers.get("content-length");
        if (cl == null) {
            return new byte[0];
        }
        long len;
        try {
            len = Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        if (len <= 0) {
            return new byte[0];
        }
        if (len > MAX_BODY_BYTES) {
            throw new IOException("body too large: " + len);
        }
        byte[] data = new byte[(int) len];
        int read = 0;
        while (read < data.length) {
            int n = in.read(data, read, data.length - read);
            if (n == -1) {
                throw new IOException("unexpected EOF in body");
            }
            read += n;
        }
        return data;
    }

    private byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                return buf.toByteArray();
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                return buf.toByteArray();
            }
            if (size == 0) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.length() == 0) {
                        break;
                    }
                }
                return buf.toByteArray();
            }
            if (buf.size() + (long) size > MAX_BODY_BYTES) {
                throw new IOException("chunked body too large");
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n == -1) {
                    return buf.toByteArray();
                }
                read += n;
            }
            buf.write(chunk, 0, size);
            readLine(in); // 丢弃 chunk 尾部的 CRLF
        }
    }

    // ------------------------------------------------------------ HTTP 写

    private void writeHead(OutputStream out, int code, String contentType, long contentLength)
            throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append(CORS_HEADERS);
        sb.append("Cache-Control: no-store\r\n");
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        } else {
            sb.append("X-Accel-Buffering: no\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(utf8()));
        out.flush();
    }

    private void writeSimple(OutputStream out, int code, String contentType, String body)
            throws IOException {
        byte[] data = body == null ? null : body.getBytes(utf8());
        writeHead(out, code, contentType, data == null ? 0 : data.length);
        if (data != null && data.length > 0) {
            out.write(data);
        }
        out.flush();
    }

    private void copyStream(InputStream in, OutputStream out, boolean flushEachChunk, long max)
            throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            if (flushEachChunk) {
                out.flush();
            }
            if (total > max) {
                throw new IOException("response too large");
            }
        }
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 504: return "Gateway Timeout";
            default: return "Status";
        }
    }

    // ------------------------------------------------------------ 杂项

    private static String normalizePath(String raw) {
        String p = raw;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.startsWith("/v1/") || p.equals("/v1")) {
            p = p.substring(3);
        }
        if (p.isEmpty()) {
            p = "/";
        }
        return p;
    }

    private static boolean isForwardPath(String path) {
        return "/chat/completions".equals(path)
                || "/completions".equals(path)
                || "/responses".equals(path);
    }

    private static String trimSlash(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private String modelsJson() {
        StringBuilder sb = new StringBuilder("{\"object\":\"list\",\"data\":[");
        String[] ids = {"Index-Translate-9B", "Index-Translate-2B", "Index-Translate-35B-A3B"};
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(ids[i]).append("\",\"object\":\"model\",\"owned_by\":\"bilibili\"}");
        }
        return sb.append("]}").toString();
    }

    private String healthJson() {
        return "{\"status\":\"ok\",\"service\":\"index-translate-bridge\",\"upstream\":\""
                + upstreamBase + "\",\"default_model\":\"" + defaultModel + "\",\"port\":"
                + (serverSocket != null ? serverSocket.getLocalPort() : -1)
                + ",\"bind\":\"127.0.0.1\",\"url\":\"" + ProxyState.getBaseUrl() + "\"}";
    }

    private static String errorJson(String message) {
        String msg = message == null ? "error" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":{\"message\":\"" + msg + "\",\"type\":\"proxy_error\"}}";
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
