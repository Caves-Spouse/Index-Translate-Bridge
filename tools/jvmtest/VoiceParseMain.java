package com.indextranslate.bridge;

import java.lang.reflect.Method;

/**
 * JVM 侧验证语音翻译面板的纯逻辑部分（JSON 解析、SSE 拼接、转义、请求体），
 * 直接反射调用 APK 内真实代码，不复制实现。
 */
public final class VoiceParseMain {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        Method parse = get("parseContent", String.class);
        Method buildBody = get("buildBody", String.class);

        check("普通 JSON", "你好，世界", parse.invoke(null,
                "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"你好，世界\"}}]}"));

        check("SSE 拼接", "你好，世界", parse.invoke(null,
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                        + "data: [DONE]\n\n"));

        check("转义还原", "a\"b\nc你", parse.invoke(null,
                "{\"choices\":[{\"message\":{\"content\":\"a\\\"b\\nc\\u4f60\"}}]}"));

        check("去思考块+去前缀", "多行\n测试", parse.invoke(null,
                "{\"choices\":[{\"message\":{\"content\":\"<" + "think>想一想<"
                        + "/think>\\n\\n译文：多行\\n测试\"}}]}"));

        check("坏响应返回 null", null, parse.invoke(null, "{\"error\":\"boom\"}"));

        String body = String.valueOf(buildBody.invoke(null, "he said \"hi\"\nline2"));
        check("请求体转义文本", true, body.contains("he said \\\"hi\\\"\\nline2"));
        check("请求体禁思考", true, body.contains("\"enable_thinking\":false"));
        check("请求体默认模型", true, body.contains("\"model\":\"Index-Translate-9B\""));
        check("请求体非流式", true, body.contains("\"stream\":false"));
        check("请求体含系统提示", true, body.contains("简体中文"));

        System.out.println(fail == 0 ? "ALL PASS (" + pass + ")" : "FAILED (" + fail + ")");
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static Method get(String name, Class<?>... types) throws Exception {
        Method m = VoiceTranslatePanel.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    private static void check(String name, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name + " 期望=" + expect + " 实际=" + actual);
        }
    }
}
