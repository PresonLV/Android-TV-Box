package com.github.catvod.crawler;

import android.util.Log;

/**
 * 饭太硬解密后的爬虫在宿主里找这个类。缺了它，InitOrigin 和站点类的静态初始化会失败。
 */
public final class SpiderDebug {
    private SpiderDebug() {
    }

    public static void log(String message) {
        if (message != null && !message.isEmpty()) {
            Log.d("JianXia", message);
        }
    }

    public static void log(Throwable error) {
        if (error != null) {
            Log.w("JianXia", error);
        }
    }
}
