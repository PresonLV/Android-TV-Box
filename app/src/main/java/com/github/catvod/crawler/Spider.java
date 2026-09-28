package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * 远程 JAR 会继承这个空类。这里只保留公开接口，不包含任何现成爬虫实现。
 */
public class Spider {
    public static OkHttpClient client() {
        return OkHttp.client();
    }

    public static Dns safeDns() {
        Dns dns = client().dns();
        return dns == null ? Dns.SYSTEM : dns;
    }

    public void init(Context context) {
    }

    public void init(Context context, String extend) {
    }

    public String homeContent(boolean filter) {
        return "";
    }

    public String homeVideoContent() {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "";
    }

    public String detailContent(List<String> ids) {
        return "";
    }

    public String searchContent(String key, boolean quick) {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    public boolean isVideoFormat(String url) {
        return false;
    }

    public boolean manualVideoCheck() {
        return false;
    }

    public Object[] proxyLocal(Map<String, String> params) {
        return null;
    }

    public String action(String action) {
        return "";
    }

    public void destroy() {
    }
}
