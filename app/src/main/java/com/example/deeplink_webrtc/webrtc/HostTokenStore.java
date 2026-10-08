package com.example.deeplink_webrtc.webrtc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * เก็บ "รหัสผู้สร้างห้อง" ที่เซิร์ฟเวอร์ให้มาตอนเราเป็นคนแรกที่เข้าห้อง
 * ครั้งต่อไปที่เข้าห้องเดิม แอปส่งรหัสนี้ไปด้วย เซิร์ฟเวอร์จึงรู้ว่าเราเป็นผู้สร้างห้อง
 * (ถ้าลบแอปหรือล้างข้อมูลแอป รหัสจะหาย และจะไม่ได้เป็นผู้สร้างห้องนั้นอีก)
 */
public final class HostTokenStore {
    private static final String PREFS = "host_tokens";
    private final SharedPreferences prefs;

    public HostTokenStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public String get(String roomId) {
        return prefs.getString(roomId, null);
    }

    public void put(String roomId, String token) {
        prefs.edit().putString(roomId, token).apply();
    }
}
