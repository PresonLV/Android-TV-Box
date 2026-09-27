package com.github.catvod.net;

import java.io.IOException;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/** 爬虫若调用宿主的 OkHttp 工具，走应用已经配好 DoH 的客户端。 */
public final class OkHttp {
    private static volatile OkHttpClient client = new OkHttpClient();

    private OkHttp() {
    }

    public static void bind(OkHttpClient value) {
        if (value != null) {
            client = value;
        }
    }

    public static OkHttpClient client() {
        return client;
    }

    public static String string(String url) throws IOException {
        return string(url, null);
    }

    public static String string(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }
        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            okhttp3.ResponseBody body = response.body();
            return body == null ? "" : body.string();
        }
    }
}
