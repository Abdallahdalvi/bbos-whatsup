package com.aghani.q20messenger;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    private static final String FILE = "q20_messenger_settings";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_PHONE = "phone_number";
    private static final String KEY_API_TOKEN = "api_token";

    private AppPrefs() { }

    public static boolean backgroundEnabled(Context context){return preferences(context).getBoolean("background_enabled",true);}
    public static void setBackgroundEnabled(Context context,boolean enabled){preferences(context).edit().putBoolean("background_enabled",enabled).commit();}
    public static boolean isDark(Context context) { return preferences(context).getBoolean("dark_theme", true); }
    public static void setDark(Context context, boolean dark) { preferences(context).edit().putBoolean("dark_theme", dark).commit(); }

    public static String getServerUrl(Context context) {
        return preferences(context).getString(KEY_SERVER, "http://192.168.0.107:8034");
    }

    public static String getPhoneNumber(Context context) {
        return preferences(context).getString(KEY_PHONE, "");
    }

    public static String getApiToken(Context context) {
        String saved = preferences(context).getString(KEY_API_TOKEN, null);
        return saved == null || saved.trim().length() == 0 ? BuildConfig.Q20_API_TOKEN : saved;
    }

    public static void save(Context context, String serverUrl, String phoneNumber) {
        preferences(context).edit()
                .putString(KEY_SERVER, cleanServer(serverUrl))
                .putString(KEY_PHONE, digits(phoneNumber))
                .commit();
    }

    public static void saveApiToken(Context context, String token) {
        preferences(context).edit().putString(KEY_API_TOKEN, token == null ? "" : token.trim()).commit();
    }

    public static String cleanServer(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static String digits(String value) {
        if (value == null) return "";
        return value.replaceAll("[^0-9]", "");
    }

    public static String ownJid(Context context) {
        String phone = getPhoneNumber(context);
        return phone.length() == 0 ? "" : phone + "@c.us";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
