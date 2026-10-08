package com.example.deeplink_webrtc.deeplink;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.example.deeplink_webrtc.Config;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * ลิงก์เชิญสำหรับฝั่งผู้สร้างห้อง:
 * - สุ่ม Room ID ใหม่
 * - สร้างลิงก์เชิญ https://<server>/join/<roomId> ให้คนอื่นเปิดเข้าประชุมผ่านเบราว์เซอร์
 * - เปิดเมนูแชร์ของ Android
 *
 * แอปนี้ไม่รับลิงก์เข้าห้องแล้ว (คนที่ได้รับลิงก์เข้าผ่านเว็บเท่านั้น)
 */
public final class DeepLinkHelper {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String ROOM_ID_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"; // ตัด 0/o/1/l ที่อ่านสับสน
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeepLinkHelper() {}

    public static boolean isValidRoomId(@Nullable String roomId) {
        return roomId != null && ROOM_ID_PATTERN.matcher(roomId).matches();
    }

    public static String buildShareLink(String roomId) {
        return Config.WEB_BASE_URL + "/join/" + Uri.encode(roomId);
    }

    public static String buildInviteText(String roomId) {
        return "เชิญเข้าร่วมประชุม (เปิดในเบราว์เซอร์ได้เลย ไม่ต้องติดตั้งแอป)\n"
                + buildShareLink(roomId);
    }

    /** Intent เปิดเมนูแชร์ของ Android (LINE, Messenger, SMS ฯลฯ) */
    public static Intent createShareIntent(String roomId) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "เชิญเข้าร่วมประชุม");
        send.putExtra(Intent.EXTRA_TEXT, buildInviteText(roomId));
        return Intent.createChooser(send, "แชร์ลิงก์เข้าห้อง");
    }

    /** สุ่ม Room ID รูปแบบ "k7m-2qx-9fd" */
    public static String generateRoomId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0 && i % 3 == 0) sb.append('-');
            sb.append(ROOM_ID_CHARS.charAt(RANDOM.nextInt(ROOM_ID_CHARS.length())));
        }
        return sb.toString();
    }
}
