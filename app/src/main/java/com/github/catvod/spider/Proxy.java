package com.github.catvod.spider;

import java.util.Map;

/**
 * 没有自带 Proxy 的 JAR 会调用这里。饭太硬这类 JAR 自己带了 Proxy，由子优先的类加载器使用它们的实现。
 */
public class Proxy {
    private static volatile String url = "http://127.0.0.1/";

    private Proxy() {
    }

    public static void setUrl(String value) {
        if (value != null && !value.isEmpty()) {
            url = value;
        }
    }

    public static String getUrl() {
        return url;
    }

    public static Object[] proxy(Map<String, String> params) {
        return null;
    }
}
