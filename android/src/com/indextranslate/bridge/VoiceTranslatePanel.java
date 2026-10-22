package com.indextranslate.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 语音翻译面板：按住说话 → 系统 SpeechRecognizer 识别 → 送 Index-Translate 翻成简体中文。
 *
 * 零第三方依赖：识别用系统 API，翻译走本地代理（回退直连上游），响应用手写极简 JSON 扫描。
 * 由 {@link MainActivity} 挂载并转发权限回调 / 生命周期。
 */
final class VoiceTranslatePanel {

    static final int REQUEST_RECORD_AUDIO = 2;

    /** 与 ProxyService.UPSTREAM 保持一致；仅在本地代理未运行时直连。 */
    private static final String UPSTREAM = "https://index-translate.bilibili.com/v1";
    private static final String DEFAULT_MODEL = "Index-Translate-9B";
    private static final String PREFS = "bridge";
    private static final String PREF_VOICE_LANG = "voice_source_lang";

    private static final String[] LANG_LABELS = {"英语", "日语", "韩语", "跟随系统"};
    private static final String[] LANG_CODES = {"en-US", "ja-JP", "ko-KR", null};

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** 按得太快视为误触，给个提示而不是白等识别结果。 */
    private static final long MIN_HOLD_MS = 350;

    private static final String SYSTEM_PROMPT =
            "你是翻译引擎。把用户输入的文本翻译成简体中文，只输出译文本身，"
                    + "不要解释、不要加引号、不要保留原文。";

    private final Activity activity;
    private final LinearLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Button speakButton;
    private final TextView statusView;
    private final TextView sourceView;
    private final TextView targetView;
    private final Button copyButton;
    private final Spinner langSpinner;

    private SpeechRecognizer speech;
    private boolean listening;
    private boolean translating;
    private long pressDownAt;
    private StringBuilder partial = new StringBuilder();

    VoiceTranslatePanel(Activity activity) {
        this.activity = activity;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(22), 0, 0);

        TextView title = new TextView(activity);
        title.setText("语音翻译（外语 → 中文）");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(activity);
        hint.setText("按住下面按钮说话，松手后自动识别并翻成简体中文。"
                + "识别用手机自带语音服务，翻译走本桥上的 Index-Translate。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        statusView = new TextView(activity);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setText("就绪");
        statusView.setTextColor(0xFF616161);
        statusView.setPadding(0, 0, 0, dp(8));
        root.addView(statusView);

        sourceView = textBox("原文", 13, 0xFF212121, 0xFFF5F5F5);
        targetView = textBox("译文", 16, 0xFF1B5E20, 0xFFE8F5E9);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(8), 0, dp(4));

        TextView langLabel = new TextView(activity);
        langLabel.setText("识别语言");
        langLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.rightMargin = dp(8);
        labelLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        actionRow.addView(langLabel, labelLp);

        langSpinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_spinner_item, LANG_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(adapter);
        langSpinner.setSelection(loadLangIndex());
        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_VOICE_LANG, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不改动已保存的选择
            }
        });
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionRow.addView(langSpinner, spinnerLp);

        copyButton = new Button(activity);
        copyButton.setText("复制译文");
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTarget();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.leftMargin = dp(8);
        actionRow.addView(copyButton, copyLp);
        root.addView(actionRow);

        speakButton = new Button(activity);
        speakButton.setText("按住说话");
        speakButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        speakButton.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams speakLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        speakLp.topMargin = dp(6);
        root.addView(speakButton, speakLp);
        speakButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        onPressDown();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        onPressUp();
                        return true;
                    default:
                        return false;
                }
            }
        });

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            speakButton.setEnabled(false);
            setStatus("本机没有可用的语音识别服务，请先安装/启用系统的语音输入。", 0xFFC62828);
        }
    }

    View getView() {
        return root;
    }

    // ------------------------------------------------------------ 生命周期 / 权限

    void onResume() {
        // 回到前台时识别器可能已被系统回收，丢弃旧实例，下次按需重建
        releaseRecognizer();
    }

    void onPause() {
        if (listening) {
            stopListening();
        }
    }

    void destroy() {
        releaseRecognizer();
        main.removeCallbacksAndMessages(null);
    }

    void requestPermissionIfNeeded() {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            setStatus("麦克风权限已授权，按住「说话」开始。", 0xFF2E7D32);
        } else {
            setStatus("需要麦克风权限才能语音输入，请再按一次并在弹窗里允许。", 0xFFC62828);
        }
    }

    // ------------------------------------------------------------ 按住说话

    private void onPressDown() {
        if (translating) {
            return;
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionIfNeeded();
            setStatus("正在申请麦克风权限，请允许后再按住说话。", 0xFFC62828);
            return;
        }
        pressDownAt = System.currentTimeMillis();
        partial.setLength(0);
        sourceView.setText("");
        startListening();
    }

    private void onPressUp() {
        if (!listening) {
            return;
        }
        long held = System.currentTimeMillis() - pressDownAt;
        stopListening();
        if (held < MIN_HOLD_MS) {
            setStatus("按住不放再说，松手结束。", 0xFF616161);
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            setStatus("本机没有可用的语音识别服务。", 0xFFC62828);
            return;
        }
        if (speech == null) {
            try {
                speech = SpeechRecognizer.createSpeechRecognizer(activity);
            } catch (Exception e) {
                setStatus("无法启动语音识别：" + e.getMessage(), 0xFFC62828);
                return;
            }
            speech.setRecognitionListener(listener);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        try {
            speech.startListening(intent);
            listening = true;
            speakButton.setText("松手结束");
            setStatus("正在听…", 0xFF1565C0);
        } catch (Exception e) {
            listening = false;
            setStatus("语音识别启动失败：" + e.getMessage(), 0xFFC62828);
        }
    }

    private void stopListening() {
        listening = false;
        speakButton.setText("按住说话");
        if (speech == null) {
            return;
        }
        try {
            speech.stopListening();
        } catch (Exception ignored) {
            // 识别器可能已被系统回收，忽略即可
        }
    }

    private void releaseRecognizer() {
        listening = false;
        if (speech != null) {
            try {
                speech.cancel();
            } catch (Exception ignored) {
                // 取消失败不影响后续重建
            }
            try {
                speech.destroy();
            } catch (Exception ignored) {
                // 同上
            }
            speech = null;
        }
    }

    private String currentLanguageTag() {
        int index = langSpinner.getSelectedItemPosition();
        if (index < 0 || index >= LANG_CODES.length || LANG_CODES[index] == null) {
            return Locale.getDefault().toLanguageTag();
        }
        return LANG_CODES[index];
    }

    private int loadLangIndex() {
        int saved = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(PREF_VOICE_LANG, 0);
        return (saved < 0 || saved >= LANG_LABELS.length) ? 0 : saved;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            setStatus("正在听…松手结束", 0xFF1565C0);
        }

        @Override
        public void onBeginningOfSpeech() {
            setStatus("正在听…松手结束", 0xFF1565C0);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // 不做音量动画，界面保持稳定
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // 系统不回传音频缓冲
        }

        @Override
        public void onEndOfSpeech() {
            setStatus("识别中…", 0xFF1565C0);
        }

        @Override
        public void onError(int error) {
            listening = false;
            speakButton.setText("按住说话");
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                setStatus("没听清，靠近麦克风再说一次。", 0xFF616161);
            } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setStatus("缺少麦克风权限，请在系统设置里允许。", 0xFFC62828);
            } else if (error == SpeechRecognizer.ERROR_NETWORK
                    || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                setStatus("语音服务网络异常（部分手机识别依赖联网）。", 0xFFC62828);
            } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // 上一轮还没结束，忽略这次按下
                setStatus("识别忙，稍等一秒再按住。", 0xFF616161);
            } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                setStatus("识别被中断，再按一次。", 0xFF616161);
            } else if (error == SpeechRecognizer.ERROR_SERVER) {
                setStatus("语音服务返回错误，稍后再试。", 0xFFC62828);
            } else {
                setStatus("识别失败 (code " + error + ")。", 0xFFC62828);
            }
        }

        @Override
        public void onResults(Bundle results) {
            listening = false;
            speakButton.setText("按住说话");
            String text = firstResult(results);
            if (text.isEmpty()) {
                setStatus("没听到内容，再说一次。", 0xFF616161);
                return;
            }
            sourceView.setText(text);
            translate(text);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (!text.isEmpty()) {
                partial.setLength(0);
                partial.append(text);
                sourceView.setText(text);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // 无自定义事件
        }
    };

    private static String firstResult(Bundle bundle) {
        if (bundle == null) {
            return "";
        }
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) {
            return "";
        }
        String first = list.get(0);
        return first == null ? "" : first.trim();
    }

    // ------------------------------------------------------------ 翻译

    private void translate(final String text) {
        translating = true;
        speakButton.setEnabled(false);
        targetView.setText("…");
        setStatus("翻译中…", 0xFF1565C0);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean ok;
                try {
                    result = doTranslate(text);
                    ok = result != null && !result.trim().isEmpty();
                    if (!ok) {
                        result = "翻译失败：模型没有返回译文。";
                    }
                } catch (Exception e) {
                    ok = false;
                    String msg = e.getMessage();
                    result = "翻译失败：" + (msg == null || msg.isEmpty() ? e.toString() : msg);
                }
                final String finalResult = result;
                final boolean finalOk = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        translating = false;
                        speakButton.setEnabled(true);
                        targetView.setText(finalResult);
                        setStatus(finalOk ? "翻译完成，可点「复制译文」。" : "出错了，可再按住说话重试。",
                                finalOk ? 0xFF2E7D32 : 0xFFC62828);
                        if (!finalOk) {
                            ProxyState.log("voice: " + finalResult);
                        }
                    }
                });
            }
        }, "voice-translate");
        worker.setDaemon(true);
        worker.start();
    }

    /** 优先本地代理，失败/未运行则直连上游；两条都失败时抛出带两段原因的异常。 */
    private String doTranslate(String text) throws IOException {
        String body = buildBody(text);
        String localBase = ProxyState.getBaseUrl();
        IOException localError = null;
        if (!localBase.isEmpty()) {
            try {
                return postJson(localBase + "/chat/completions", body);
            } catch (IOException e) {
                localError = e;
            }
        }
        try {
            return postJson(UPSTREAM + "/chat/completions", body);
        } catch (IOException e) {
            if (localError != null) {
                throw new IOException("本地代理失败(" + localError.getMessage()
                        + ")，直连上游也失败(" + e.getMessage() + ")");
            }
            throw e;
        }
    }

    private static String buildBody(String text) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"model\":\"").append(DEFAULT_MODEL).append('"')
                .append(",\"temperature\":0,\"stream\":false")
                .append(",\"messages\":[")
                .append("{\"role\":\"system\",\"content\":\"").append(escapeJson(SYSTEM_PROMPT)).append("\"},")
                .append("{\"role\":\"user\",\"content\":\"").append(escapeJson(text)).append("\"}")
                .append("],\"chat_template_kwargs\":{\"enable_thinking\":false}}");
        return sb.toString();
    }

    private static String postJson(String endpoint, String body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            conn.setRequestProperty("User-Agent", "Index-Translate-Client/1.0");
            byte[] payload = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = readAll(in);
            if (code != 200) {
                String snippet = raw.length() > 160 ? raw.substring(0, 160) : raw;
                throw new IOException("HTTP " + code + (snippet.isEmpty() ? "" : " " + snippet));
            }
            String content = parseContent(raw);
            if (content == null) {
                String snippet = raw.length() > 160 ? raw.substring(0, 160) : raw;
                throw new IOException("响应里没有译文 " + snippet);
            }
            return content;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    break;
                }
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 读完了，关不掉也不影响结果
            }
        }
    }

    /** 解析普通 JSON 响应或 SSE 流，取出 choices[0].message.content（SSE 下拼接 delta）。 */
    private static String parseContent(String raw) {
        String trimmed = raw.trim();
        boolean sse = trimmed.startsWith("data:") || trimmed.contains("\ndata:");
        if (!sse) {
            String content = extractQuotedAfterKey(trimmed, "\"content\"");
            return content == null ? null : cleanTranslation(content);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : trimmed.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("data:")) {
                continue;
            }
            String payload = t.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            String piece = extractQuotedAfterKey(payload, "\"content\"");
            if (piece != null) {
                sb.append(piece);
            }
        }
        return sb.length() == 0 ? null : cleanTranslation(sb.toString());
    }

    /** 手写扫描：找 key 之后的第一个 JSON 字符串并反转义（不引入 JSON 库）。 */
    private static String extractQuotedAfterKey(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIdx + key.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = open + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                break;
            }
            if (c != '\\') {
                sb.append(c);
                i++;
                continue;
            }
            if (i + 1 >= json.length()) {
                break;
            }
            char esc = json.charAt(i + 1);
            switch (esc) {
                case 'n':
                    sb.append('\n');
                    i += 2;
                    break;
                case 't':
                    sb.append('\t');
                    i += 2;
                    break;
                case 'r':
                    sb.append('\r');
                    i += 2;
                    break;
                case 'b':
                    sb.append('\b');
                    i += 2;
                    break;
                case 'f':
                    sb.append('\f');
                    i += 2;
                    break;
                case 'u':
                    if (i + 6 <= json.length()) {
                        String hex = json.substring(i + 2, i + 6);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 6;
                        } catch (NumberFormatException e) {
                            sb.append(esc);
                            i += 2;
                        }
                    } else {
                        i += 2;
                    }
                    break;
                default:
                    sb.append(esc);
                    i += 2;
                    break;
            }
        }
        return sb.toString();
    }

    /** 去掉模型可能残留的思考块与多余包裹。 */
    private static String cleanTranslation(String raw) {
        String s = raw;
        s = s.replaceAll("(?s)<" + "think>.*?<" + "/think>", "");
        s = s.replaceAll("(?s)<\\｜end▁of▁thinking\\｜>", "");
        s = s.replace("＜｜end▁of▁thinking｜＞", "");
        s = s.trim();
        if (s.startsWith("译文：")) {
            s = s.substring(3).trim();
        } else if (s.startsWith("译文:")) {
            s = s.substring(3).trim();
        }
        return s;
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 小工具

    private void copyTarget() {
        CharSequence text = targetView.getText();
        if (text == null || text.length() == 0 || "…".contentEquals(text)) {
            Toast.makeText(activity, "还没有译文", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(activity, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("translation", text));
        Toast.makeText(activity, "译文已复制", Toast.LENGTH_SHORT).show();
    }

    private TextView textBox(String label, int sizeSp, int textColor, int background) {
        TextView box = new TextView(activity);
        box.setText(label + "：—");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        box.setTextColor(textColor);
        box.setTextIsSelectable(true);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackgroundColor(background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        box.setLayoutParams(lp);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(wrapper);
        return box;
    }

    private void setStatus(String text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
