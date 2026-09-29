package com.fongmi.android.tv;

/**
 * 网盘 JAR 会反射这个类读取夸克、UC 和阿里的登录信息。
 * 值由设置页和手机扫码页写入，不在安装包里放任何账号。
 */
public final class Setting {
    public static volatile String quark = "";
    public static volatile String uc = "";
    public static volatile String ali = "";

    private Setting() {
    }

    public static String getQuark() {
        return quark == null ? "" : quark;
    }

    public static String getUc() {
        return uc == null ? "" : uc;
    }

    public static String getUC() {
        return getUc();
    }

    public static String getAli() {
        return ali == null ? "" : ali;
    }

    public static String getAlipan() {
        return getAli();
    }

    public static String getAliYun() {
        return getAli();
    }

    public static String getString(String key) {
        if (key == null) return "";
        String name = key.toLowerCase();
        if (name.contains("quark")) return getQuark();
        if (name.contains("uc")) return getUc();
        if (name.contains("ali")) return getAli();
        return "";
    }
}
