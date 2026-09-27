package com.github.catvod.spider;

import android.content.Context;

/** 部分 JAR 在运行时读取这里保存的 Context。 */
public final class Init {
    private static Context context;

    private Init() {
    }

    public static void init(Context value) {
        context = value;
    }

    public static Context context() {
        return context;
    }
}
