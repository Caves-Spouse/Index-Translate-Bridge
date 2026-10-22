#!/usr/bin/env node
/**
 * Index-Translate 本地代理桥 / Local Proxy Bridge
 * ------------------------------------------------------------------
 * 把浏览器扩展（沉浸式翻译等）发来的 OpenAI 兼容请求转发到
 *   https://index-translate.bilibili.com/v1
 * 解决两个问题：
 *   1) 扩展直连上游会被反爬/CORS 拦（412 或无 Access-Control-*）；
 *   2) 上游需要 chat_template_kwargs.enable_thinking=false 才不会把
 *      CoT 思考文本当成译文返回。
 *
 * 特性：
 *   - 零依赖（只用 Node 内置模块），Node >= 16 即可
 *   - 端口自动探测：默认从 8080 起，被占用则自动 +1 直到成功
 *   - 仅绑定 127.0.0.1（绝不对外暴露）
 *   - SSE 流式透传（边收边发，不缓冲）
 *   - 非流式请求体/响应体 gzip（请求体被上游拒绝时自动回退一次明文）
 *   - 上游连接 keep-alive 复用，避免每条请求重做 TCP+TLS 握手
 *   - 完整 CORS 支持（OPTIONS 预检 / 所有响应带 ACAO）
 *
 * 环境变量：
 *   PORT            起始端口（默认 8080）
 *   PORT_TRIES      最多尝试多少个端口（默认 30）
 *   DEFAULT_MODEL   客户端未指定 model 时使用（默认 Index-Translate-9B）
 *   UPSTREAM_BASE   上游 base（默认 https://index-translate.bilibili.com/v1）
 *   DISABLE_GZIP=1  关闭传输层压缩（调试/对比流量用）
 *   DISABLE_GZIP_UPLOAD=1  只关请求体压缩，保留响应压缩（上游拒收 gzip 请求体时省一次回退）
 *   QUIET=1         关闭逐请求日志
 */

'use strict';

const http = require('http');
const https = require('https');
const zlib = require('zlib');

const UPSTREAM_BASE = (process.env.UPSTREAM_BASE || 'https://index-translate.bilibili.com/v1').replace(/\/+$/, '');
const BIND_HOST = '127.0.0.1';
const START_PORT = parseInt(process.env.PORT || '8080', 10);
const MAX_PORT_TRIES = parseInt(process.env.PORT_TRIES || '30', 10);
const DEFAULT_MODEL = process.env.DEFAULT_MODEL || 'Index-Translate-9B';
const QUIET = process.env.QUIET === '1';

const KNOWN_MODELS = ['Index-Translate-9B', 'Index-Translate-2B', 'Index-Translate-35B-A3B'];
const MAX_BODY = 32 * 1024 * 1024; // 32MB，够多模态图片 Base64
const UPSTREAM_TIMEOUT = 180000; // 180s
const GZIP_MIN_BYTES = 512; // 小于 512B 压完往往更大，直接发明文
const DISABLE_GZIP = process.env.DISABLE_GZIP === '1';
const DISABLE_GZIP_UPLOAD = DISABLE_GZIP || process.env.DISABLE_GZIP_UPLOAD === '1';

// ---------------------------------------------------------------- 上游转发

const AGENT_OPTIONS = {
  keepAlive: true,
  keepAliveMsecs: 30000,
  maxSockets: 64,
  maxFreeSockets: 16,
  scheduling: 'lifo',
};
const httpsAgent = new https.Agent(AGENT_OPTIONS);
const httpAgent = new http.Agent(AGENT_OPTIONS);

const upstream = new URL(UPSTREAM_BASE);
// UPSTREAM_BASE 可指向 http 上游（自建/中转），此时不能强行走 https
const transport = upstream.protocol === 'http:' ? http : https;
const upstreamAgent = upstream.protocol === 'http:' ? httpAgent : httpsAgent;

/** 上游一旦拒绝过 gzip 请求体，本进程后续直接发明文，避免每次多花一次往返 */
let upstreamGzipRejected = false;

function forwardUpstream(upstreamPath, bodyBuffer, opts) {
  const gzipBody = !!(opts && opts.gzipBody);
  const gzipResponse = !!(opts && opts.gzipResponse);
  const options = {
    protocol: upstream.protocol,
    hostname: upstream.hostname,
    port: upstream.port || (upstream.protocol === 'http:' ? 80 : 443),
    method: 'POST',
    path: `${upstream.pathname.replace(/\/+$/, '')}${upstreamPath}`,
    agent: upstreamAgent,
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': bodyBuffer.length,
      // 上游对 UA 敏感，用固定客户端 UA 避免反爬 412
      'User-Agent': 'Index-Translate-Client/1.0',
      Accept: 'text/event-stream, application/json',
      // 只有调用方明确要求时才让上游压缩（SSE 必须 identity）
      'Accept-Encoding': gzipResponse ? 'gzip' : 'identity',
      Connection: 'keep-alive',
    },
  };
  if (gzipBody) options.headers['Content-Encoding'] = 'gzip';
  return new Promise((resolve, reject) => {
    const req = transport.request(options, resolve);
    req.setTimeout(UPSTREAM_TIMEOUT, () => {
      req.destroy(new Error(`upstream timeout after ${UPSTREAM_TIMEOUT}ms`));
    });
    req.on('error', reject);
    req.end(bodyBuffer);
  });
}

// ---------------------------------------------------------------- 传输层工具

/** 客户端是否接受 gzip；识别 gzip;q=0 这种显式拒绝 */
function acceptsGzip(header) {
  if (!header) return false;
  const h = String(header).toLowerCase();
  return h.includes('gzip') && !h.includes('gzip;q=0') && !h.includes('gzip; q=0');
}

/** 压完不变小就返回 null（保持明文） */
function gzipBuffer(buf) {
  try {
    const gz = zlib.gzipSync(buf);
    return gz.length < buf.length ? gz : null;
  } catch (_) {
    return null;
  }
}

/** 上游拒绝 gzip 请求体时的回退判定（与 Java 版保持一致） */
function shouldRetryAsPlain(status) {
  if (status < 400) return false;
  return ![401, 403, 404, 408, 429, 504].includes(status);
}

function contentEncodingOf(headers) {
  const ce = headers && headers['content-encoding'];
  return typeof ce === 'string' ? ce.trim() : '';
}

function isGzipEncoding(ce) {
  return !!ce && ce.toLowerCase().includes('gzip');
}

function looksGzip(buf) {
  return buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b;
}

// ---------------------------------------------------------------- 工具函数

const PASS_HEADERS = ['content-type', 'cache-control', 'x-request-id', 'retry-after'];

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Expose-Headers': '*',
    'Access-Control-Max-Age': '86400',
  };
}

function sendJson(res, status, obj) {
  const buf = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, {
    ...corsHeaders(),
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': buf.length,
    'Cache-Control': 'no-store',
  });
  res.end(buf);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error('request body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/** 归一化路径：/v1/chat/completions、/chat/completions 都接受 */
function normalizePath(rawPath) {
  const p = rawPath.split('?')[0].replace(/\/+$/, '');
  return p.startsWith('/v1/') || p === '/v1' ? p.slice(3) : p;
}

function log(msg) {
  if (QUIET) return;
  const t = new Date().toTimeString().slice(0, 8);
  process.stdout.write(`[${t}] ${msg}\n`);
}

// ---------------------------------------------------------------- 请求处理

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const path = normalizePath(req.url || '/');

  if (req.method === 'OPTIONS') {
    res.writeHead(204, corsHeaders());
    res.end();
    return;
  }

  if (req.method === 'GET' || req.method === 'HEAD') {
    if (path === '/models' || path === '/model/list') {
      sendJson(res, 200, {
        object: 'list',
        data: KNOWN_MODELS.map((id) => ({ id, object: 'model', owned_by: 'bilibili' })),
      });
      return;
    }
    if (path === '/' || path === '/health') {
      sendJson(res, 200, {
        status: 'ok',
        service: 'index-translate-proxy',
        upstream: UPSTREAM_BASE,
        default_model: DEFAULT_MODEL,
        port: server.address() ? server.address().port : null,
        models: KNOWN_MODELS,
        hint: '在沉浸式翻译中填 API URL: http://127.0.0.1:<port>/v1',
      });
      return;
    }
    sendJson(res, 404, { error: { message: `unknown path: ${path}`, type: 'not_found' } });
    return;
  }

  if (req.method !== 'POST') {
    sendJson(res, 405, { error: { message: 'method not allowed', type: 'method_not_allowed' } });
    return;
  }

  const allowed = ['/chat/completions', '/completions', '/responses', '/embeddings'];
  if (!allowed.includes(path)) {
    sendJson(res, 404, {
      error: { message: `unknown path: ${path} (supported: ${allowed.join(', ')})`, type: 'not_found' },
    });
    return;
  }

  let rawBody;
  try {
    rawBody = await readBody(req);
  } catch (e) {
    sendJson(res, 413, { error: { message: e.message, type: 'invalid_request_error' } });
    return;
  }

  let payload = null;
  if (rawBody.length > 0) {
    try {
      payload = JSON.parse(rawBody.toString('utf8'));
    } catch (e) {
      sendJson(res, 400, { error: { message: `invalid JSON body: ${e.message}`, type: 'invalid_request_error' } });
      return;
    }
  }
  if (!payload || typeof payload !== 'object') payload = {};

  // 默认值：模型 / 关闭思考 / 温度 0（沉浸式翻译不会自己带这些）
  if (!payload.model) payload.model = DEFAULT_MODEL;
  if (payload.temperature === undefined || payload.temperature === null) payload.temperature = 0;
  if (!payload.chat_template_kwargs || typeof payload.chat_template_kwargs !== 'object') {
    payload.chat_template_kwargs = {};
  }
  if (payload.chat_template_kwargs.enable_thinking === undefined) {
    payload.chat_template_kwargs.enable_thinking = false;
  }

  const isStream = payload.stream === true;
  const bodyBuffer = Buffer.from(JSON.stringify(payload), 'utf8');

  // 压缩策略与 Java 版对齐：
  //   - 客户端声明支持 gzip 且非流式 → 让上游压缩并原样透传压缩字节；
  //   - 请求体超阈值先 gzip 上传，上游拒绝则只回退一次明文并记住结果；
  //   - SSE 恒用 identity，保逐块到达。
  const clientGzip = !DISABLE_GZIP && acceptsGzip(req.headers['accept-encoding']);
  const gzipResponse = clientGzip && !isStream;
  let bodyGzip = null;
  if (!DISABLE_GZIP_UPLOAD && !upstreamGzipRejected && bodyBuffer.length >= GZIP_MIN_BYTES) {
    bodyGzip = gzipBuffer(bodyBuffer);
  }
  let upload = bodyGzip || bodyBuffer;
  let uploadGzip = !!bodyGzip;

  let upstreamRes;
  try {
    upstreamRes = await forwardUpstream(path, upload, { gzipBody: uploadGzip, gzipResponse });
    if (uploadGzip && shouldRetryAsPlain(upstreamRes.statusCode || 502)) {
      const rejected = upstreamRes.statusCode || 502;
      upstreamRes.resume(); // 丢掉被拒绝的响应体，让连接回池
      upstreamGzipRejected = true;
      log(`上游拒绝 gzip 请求体(${rejected})，本进程后续改为明文上传`);
      upload = bodyBuffer;
      uploadGzip = false;
      upstreamRes = await forwardUpstream(path, upload, { gzipBody: false, gzipResponse });
    }
  } catch (e) {
    log(`${req.method} ${path} -> 502 (${e.message})`);
    sendJson(res, 502, { error: { message: `upstream unreachable: ${e.message}`, type: 'upstream_error' } });
    return;
  }

  const status = upstreamRes.statusCode || 502;
  const headers = { ...corsHeaders(), 'X-Proxy-Upstream-Status': String(status) };
  for (const k of PASS_HEADERS) {
    const v = upstreamRes.headers[k];
    if (v) headers[k] = v;
  }

  if (isStream && status === 200) {
    // SSE：不设 content-length，边收边发；上游若无视 identity 仍压缩，先解开再直通
    const sseEncoding = contentEncodingOf(upstreamRes.headers);
    let source = upstreamRes;
    if (isGzipEncoding(sseEncoding)) {
      source = upstreamRes.pipe(zlib.createGunzip());
    } else if (sseEncoding && sseEncoding.toLowerCase() !== 'identity') {
      headers['Content-Encoding'] = sseEncoding;
    }
    headers['Content-Type'] = headers['Content-Type'] || 'text/event-stream; charset=utf-8';
    headers['Cache-Control'] = 'no-cache, no-transform';
    headers['X-Accel-Buffering'] = 'no';
    headers['Connection'] = 'keep-alive';
    res.writeHead(status, headers);
    if (typeof res.flushHeaders === 'function') res.flushHeaders();

    let firstChunkAt = null;
    let bytes = 0;
    source.on('data', (chunk) => {
      if (firstChunkAt === null) firstChunkAt = Date.now();
      bytes += chunk.length;
      res.write(chunk);
    });
    source.on('end', () => {
      res.end();
      log(
        `POST ${path} model=${payload.model} stream -> ${status} ` +
          `↑${upload.length}B${uploadGzip ? '(gzip)' : ''} ↓${bytes}B ` +
          `ttfb=${firstChunkAt ? firstChunkAt - started : '-'}ms total=${Date.now() - started}ms`
      );
    });
    source.on('error', (e) => {
      log(`POST ${path} stream aborted: ${e.message}`);
      res.destroy();
    });
    req.on('close', () => {
      if (!res.writableEnded) upstreamRes.destroy();
    });
    return;
  }

  // 非流式：整体读完再回（含错误响应，原样透传给客户端）
  const chunks = [];
  upstreamRes.on('data', (c) => chunks.push(c));
  upstreamRes.on('end', () => {
    const buf = Buffer.concat(chunks);
    // 上游已压缩：字节原样透传 + 声明 Content-Encoding，客户端自行解压
    const ce = contentEncodingOf(upstreamRes.headers);
    let passthrough = false;
    if (gzipResponse && ce && ce.toLowerCase() !== 'identity') {
      if (isGzipEncoding(ce)) {
        if (looksGzip(buf)) {
          headers['Content-Encoding'] = 'gzip';
          passthrough = true;
        }
      } else {
        headers['Content-Encoding'] = ce;
        passthrough = true;
      }
    }
    if (passthrough) headers['Vary'] = 'Accept-Encoding';
    headers['Content-Length'] = buf.length;
    if (!headers['Content-Type']) headers['Content-Type'] = 'application/json; charset=utf-8';
    res.writeHead(status, headers);
    res.end(buf);
    let extra = '';
    if (status === 200 && payload.model && !passthrough) {
      try {
        const j = JSON.parse(buf.toString('utf8'));
        const u = j.usage;
        if (u) extra = ` tokens=${u.prompt_tokens}/${u.completion_tokens}`;
      } catch (_) {
        /* 忽略解析失败 */
      }
    }
    log(
      `POST ${path} model=${payload.model} -> ${status} ` +
        `↑${upload.length}B${uploadGzip ? '(gzip)' : ''} ↓${buf.length}B${passthrough ? '(gzip)' : ''}` +
        `${extra} total=${Date.now() - started}ms`
    );
  });
  upstreamRes.on('error', (e) => {
    log(`POST ${path} read error: ${e.message}`);
    if (!res.headersSent) sendJson(res, 502, { error: { message: e.message, type: 'upstream_error' } });
    else res.destroy();
  });
});

// ---------------------------------------------------------------- 端口自动探测

function tryListen(port, remainingTries) {
  return new Promise((resolve, reject) => {
    const onError = (err) => {
      server.removeListener('listening', onListening);
      if (err.code === 'EADDRINUSE' || err.code === 'EACCES') {
        if (remainingTries <= 0) {
          reject(new Error(`端口 ${START_PORT}~${port} 全部被占用，可用 PORT=xxxx 指定其它端口`));
          return;
        }
        const next = port + 1;
        if (!QUIET) process.stdout.write(`端口 ${port} 被占用，尝试 ${next} ...\n`);
        server.removeListener('error', onError);
        resolve(tryListen(next, remainingTries - 1));
        return;
      }
      reject(err);
    };
    const onListening = () => {
      server.removeListener('error', onError);
      resolve(port);
    };
    server.once('error', onError);
    server.once('listening', onListening);
    server.listen(port, BIND_HOST);
  });
}

(async () => {
  let port;
  try {
    port = await tryListen(START_PORT, MAX_PORT_TRIES);
  } catch (e) {
    process.stderr.write(`[FATAL] ${e.message}\n`);
    process.exit(1);
  }

  const base = `http://${BIND_HOST}:${port}/v1`;
  const line = '='.repeat(64);
  process.stdout.write(
    [
      line,
      ` Index-Translate 本地代理已启动  (仅本机可访问)`,
      ` 代理地址 : ${base}`,
      ` 上游 API : ${UPSTREAM_BASE}`,
      ` 默认模型 : ${DEFAULT_MODEL}   (可选: ${KNOWN_MODELS.join(' / ')})`,
      line,
      ' 沉浸式翻译配置:',
      '   翻译服务   : OpenAI 兼容 / 自定义 OpenAI',
      `   接口地址   : ${base}`,
      `   模型       : ${DEFAULT_MODEL}`,
      '   API Key    : 随便填，例如 index',
      line,
      ' 自检: curl -s ' + base + '/models',
      ' 退出: Ctrl+C',
      line,
      '',
    ].join('\n')
  );
})();

function shutdown(sig) {
  process.stdout.write(`\n收到 ${sig}，正在关闭代理...\n`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
