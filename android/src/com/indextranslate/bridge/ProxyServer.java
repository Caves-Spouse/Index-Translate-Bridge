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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
    /** 小于 512B 的请求体压完往往更大，直接发明文。 */
    private static final int GZIP_MIN_BYTES = 512;
    /** 回退前最多读掉这么多上游字节，再多就放弃复用这条连接。 */
    private static final int MAX_DRAIN_BYTES = 256 * 1024;

    /**
     * 传输层压缩总开关。APK 内恒为开启；JVM 下调试可用
     * {@code -Dindex.bridge.gzip=0} 关掉，便于对比压缩前后的字节数。
     */
    private static final boolean COMPRESSION_ENABLED =
            !"0".equals(System.getProperty("index.bridge.gzip", "1"));
    /**
     * 请求体压缩单独开关：上游（index-translate.bilibili.com）目前对
     * {@code Content-Encoding: gzip} 的请求体直接返 400，会自动回退，
     * 但首次回退仍要付一次往返。若只想保留响应压缩，可用
     * {@code -Dindex.bridge.gzip.upload=0}。
     */
    private static final boolean UPLOAD_GZIP_ENABLED = COMPRESSION_ENABLED
            && !"0".equals(System.getProperty("index.bridge.gzip.upload", "1"));

    static {
        // HttpURLConnection 默认会复用 keep-alive 连接，但连接池上限只有 5，
        // 并发翻译时容易退化成每条请求重新做一次 TCP+TLS 握手。
        if (System.getProperty("http.keepAlive") == null) {
            System.setProperty("http.keepAlive", "true");
        }
        if (System.getProperty("http.maxConnections") == null) {
            System.setProperty("http.maxConnections", "16");
        }
    }

    private static final String CORS_HEADERS =
            "Access-Control-Allow-Origin: *\r\n"
          + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
          + "Access-Control-Allow-Headers: *\r\n"
          + "Access-Control-Expose-Headers: *\r\n"
          + "Access-Control-Max-Age: 86400\r\n";

    private final String upstreamBase;
    private final String defaultModel;
    private final int startPort;

    /** 上游一旦拒绝过 gzip 请求体，本进程后续就直接发明文，避免每次多花一次往返。 */
    private volatile boolean upstreamGzipRejected;

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
            Stats stats = forward(out, path, body, headers);
            ProxyState.log(tag + " ok " + (System.currentTimeMillis() - started) + "ms"
                    + " ↑" + stats.up.data.length + "B" + (stats.up.gzip ? "(gzip)" : "")
                    + " ↓" + stats.downBytes + "B" + (stats.downGzip ? "(gzip)" : ""));
        } catch (IOException e) {
            ProxyState.log(tag + " 连接异常: " + e.getMessage());
        } catch (RuntimeException e) {
            ProxyState.log(tag + " 处理异常: " + e.getMessage());
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 转发到上游；SSE 边收边发，普通 JSON 缓冲后带 Content-Length 回写。
     *
     * 传输层策略：
     *   1. 请求体超过阈值先 gzip 再上传（译文请求是 JSON 文本，压缩率通常 60%~80%）；
     *      上游若不接受，回退一次明文重发，并记住“本进程不再压缩上传”；
     *   2. 客户端声明支持 gzip 时，让上游压缩响应并把压缩字节原样透传（浏览器自行解压）；
     *   3. SSE 两端都保持 identity，逐块 flush，牺牲压缩换实时性；
     *   4. 读完 body 只 close()，不 disconnect()，让连接回到 keep-alive 池复用 TLS 会话。
     */
    private Stats forward(OutputStream out, String path, byte[] body, Map<String, String> clientHeaders)
            throws IOException {
        byte[] payload = preparePayload(body);
        boolean streamRequest = isStreamRequest(payload);
        // curl 等不声明 Accept-Encoding 的客户端不给压缩响应，避免把二进制塞给不认识它的调用方
        boolean clientGzip = COMPRESSION_ENABLED && acceptsGzip(clientHeaders.get("accept-encoding"));
        boolean wantGzipResponse = clientGzip && !streamRequest;

        Upload upload = buildUpload(payload, UPLOAD_GZIP_ENABLED && !upstreamGzipRejected);
        Upstream up = null;
        boolean headSent = false;
        long downBytes = 0;
        boolean downGzip = false;
        try {
            up = openUpstream(path, upload, wantGzipResponse);
            if (upload.gzip && shouldRetryAsPlain(up.code)) {
                int rejected = up.code;
                up.close();
                up = null;
                upstreamGzipRejected = true;
                ProxyState.log("上游拒绝 gzip 请求体(" + rejected + ")，本进程后续改为明文上传");
                upload = buildUpload(payload, false);
                up = openUpstream(path, upload, wantGzipResponse);
            }

            int code = up.code;
            String contentType = up.contentType;
            boolean sse = contentType != null
                    && contentType.toLowerCase(Locale.US).contains("text/event-stream");
            InputStream resp = up.stream;

            if (resp == null) {
                writeSimple(out, 502, "application/json; charset=utf-8",
                        errorJson("upstream returned " + code + " with no body"));
                return new Stats(upload, 0, false);
            }

            if (sse) {
                // 万一上游无视 identity 仍然压缩：gzip 先解开再直通，保证客户端收到可解析的 SSE；
                // 其它编码（罕见，如 br）只能原样透传并声明 Content-Encoding，交给客户端解。
                String sseEncoding = up.contentEncoding;
                InputStream sseIn = resp;
                String sseExtra = null;
                if (up.isGzipEncoded()) {
                    sseIn = new GZIPInputStream(resp);
                } else if (sseEncoding != null && !"identity".equalsIgnoreCase(sseEncoding.trim())) {
                    sseExtra = "Content-Encoding: " + sseEncoding.trim() + "\r\n";
                }
                writeHead(out, code, contentType, -1, sseExtra);
                headSent = true;
                downBytes = copyStream(sseIn, out, true, Long.MAX_VALUE);
            } else {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                copyStream(resp, buffer, false, MAX_JSON_RESPONSE);
                byte[] data = buffer.toByteArray();
                // 上游已压缩：字节原样透传 + 声明 Content-Encoding，客户端自行解压。
                // 用 gzip 魔数兜底，防止上游声称 gzip 却发了明文。
                boolean passthrough = wantGzipResponse && isEncodedBody(up.contentEncoding, data);
                String extra = passthrough
                        ? "Content-Encoding: " + up.contentEncoding.trim()
                                + "\r\nVary: Accept-Encoding\r\n"
                        : null;
                writeHead(out, code,
                        contentType == null ? "application/json; charset=utf-8" : contentType,
                        data.length, extra);
                headSent = true;
                out.write(data);
                out.flush();
                downBytes = data.length;
                downGzip = passthrough;
            }
            return new Stats(upload, downBytes, downGzip);
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
            return new Stats(upload, downBytes, downGzip);
        } finally {
            if (up != null) {
                up.close();
            }
        }
    }

    /** 发起一次上游 POST；读完 body 后由调用方 close() 归还连接。 */
    private Upstream openUpstream(String path, Upload upload, boolean gzipResponse) throws IOException {
        URL url = new URL(upstreamBase + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 上游对 UA 敏感，固定的客户端 UA 可避免反爬 412
            conn.setRequestProperty("User-Agent", "Index-Translate-Client/1.0");
            conn.setRequestProperty("Accept", "text/event-stream, application/json");
            conn.setRequestProperty("Accept-Encoding", gzipResponse ? "gzip" : "identity");
            if (upload.gzip) {
                conn.setRequestProperty("Content-Encoding", "gzip");
            }
            conn.setFixedLengthStreamingMode(upload.data.length);

            OutputStream upstreamOut = conn.getOutputStream();
            try {
                upstreamOut.write(upload.data);
                upstreamOut.flush();
            } finally {
                upstreamOut.close();
            }

            int code = conn.getResponseCode();
            InputStream resp = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (resp == null) {
                resp = conn.getErrorStream();
            }
            return new Upstream(code, conn.getContentType(),
                    conn.getHeaderField("Content-Encoding"), resp, conn);
        } catch (IOException e) {
            // 请求或读响应头失败：这条连接已不可复用，必须直接关掉
            conn.disconnect();
            throw e;
        } catch (RuntimeException e) {
            conn.disconnect();
            throw e;
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
        writeHead(out, code, contentType, contentLength, null);
    }

    /** @param extraHeaders 形如 "Header: v\r\n" 的额外响应头，可为 null */
    private void writeHead(OutputStream out, int code, String contentType, long contentLength,
                           String extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append(CORS_HEADERS);
        sb.append("Cache-Control: no-store\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
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

    private long copyStream(InputStream in, OutputStream out, boolean flushEachChunk, long max)
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
        return total;
    }

    // ------------------------------------------------------------ 传输层工具

    /** 客户端是否接受 gzip；识别 gzip;q=0 这种显式拒绝。 */
    private static boolean acceptsGzip(String header) {
        if (header == null) {
            return false;
        }
        String h = header.toLowerCase(Locale.US);
        return h.contains("gzip") && !h.contains("gzip;q=0") && !h.contains("gzip; q=0");
    }

    /** 请求体是否要求 SSE 流式返回（"stream": true）。 */
    private static boolean isStreamRequest(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        String s = new String(payload, utf8());
        int i = s.indexOf("\"stream\"");
        if (i < 0) {
            return false;
        }
        int colon = s.indexOf(':', i + 8);
        if (colon < 0) {
            return false;
        }
        return s.substring(colon + 1).trim().startsWith("true");
    }

    private static boolean isGzipData(byte[] data) {
        return data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
    }

    /**
     * body 是否真的是压缩过的（可以原样透传 + 声明 Content-Encoding）。
     * gzip 用魔数验证；其它编码无法验证，按声明透传。
     */
    private static boolean isEncodedBody(String contentEncoding, byte[] data) {
        if (contentEncoding == null) {
            return false;
        }
        String enc = contentEncoding.trim();
        if (enc.length() == 0 || "identity".equalsIgnoreCase(enc)) {
            return false;
        }
        if (enc.toLowerCase(Locale.US).contains("gzip")) {
            return isGzipData(data);
        }
        return true;
    }

    /**
     * 上游拒绝 gzip 请求体时的回退判定。401/403/404/408/429/504 与压缩无关
     * （鉴权 / 限流 / 超时），回退只会白多一次往返。
     */
    private static boolean shouldRetryAsPlain(int code) {
        if (code < 400) {
            return false;
        }
        switch (code) {
            case 401:
            case 403:
            case 404:
            case 408:
            case 429:
            case 504:
                return false;
            default:
                return true;
        }
    }

    /** 压缩请求体；压完不变小就保持明文。 */
    private static Upload buildUpload(byte[] payload, boolean allowGzip) {
        if (!allowGzip || payload.length < GZIP_MIN_BYTES) {
            return new Upload(payload, false);
        }
        byte[] zipped = gzipBytes(payload);
        if (zipped == null || zipped.length >= payload.length) {
            return new Upload(payload, false);
        }
        return new Upload(zipped, true);
    }

    private static byte[] gzipBytes(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        GZIPOutputStream gz = null;
        try {
            gz = new GZIPOutputStream(bos, 8192);
            gz.write(data);
            gz.finish();
            gz.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (gz != null) {
                try {
                    gz.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
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

    // ------------------------------------------------------------ 内部数据结构

    /** 待上传的请求体（可能是 gzip 后的字节）。 */
    private static final class Upload {
        final byte[] data;
        final boolean gzip;

        Upload(byte[] data, boolean gzip) {
            this.data = data;
            this.gzip = gzip;
        }
    }

    /** 一次上游响应；读完 body 后 close()，连接会回到 keep-alive 池。 */
    private static final class Upstream {
        final int code;
        final String contentType;
        final String contentEncoding;
        final InputStream stream;
        final HttpURLConnection conn;

        Upstream(int code, String contentType, String contentEncoding, InputStream stream,
                 HttpURLConnection conn) {
            this.code = code;
            this.contentType = contentType;
            this.contentEncoding = contentEncoding;
            this.stream = stream;
            this.conn = conn;
        }

        boolean isGzipEncoded() {
            return contentEncoding != null && contentEncoding.toLowerCase(Locale.US).contains("gzip");
        }

        void close() {
            if (stream == null) {
                if (conn != null) {
                    // 没有 body 可读，这条连接无法安全复用
                    conn.disconnect();
                }
                return;
            }
            try {
                // 把剩余字节读净，连接才能安全回池；超过上限就弃用这条连接
                byte[] skip = new byte[4096];
                long drained = 0;
                int n;
                while ((n = stream.read(skip)) != -1) {
                    drained += n;
                    if (drained > MAX_DRAIN_BYTES) {
                        conn.disconnect();
                        break;
                    }
                }
            } catch (IOException ignored) {
                // 已断开
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 单次转发的字节统计，用于日志里直观看到压缩收益。 */
    private static final class Stats {
        final Upload up;
        final long downBytes;
        final boolean downGzip;

        Stats(Upload up, long downBytes, boolean downGzip) {
            this.up = up;
            this.downBytes = downBytes;
            this.downGzip = downGzip;
        }
    }
}
