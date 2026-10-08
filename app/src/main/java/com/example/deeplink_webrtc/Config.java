package com.example.deeplink_webrtc;

public final class Config {
    private Config() {}

    // ---------------- เซิร์ฟเวอร์ ----------------
    // Production (Render): "wss://server-webrtc-w6oc.onrender.com"
    // ทดสอบในเครื่อง: Emulator "ws://10.0.2.2:8080", มือถือจริง "ws://<IP-คอม>:8080"
    public static final String SIGNALING_URL = "wss://server-webrtc-w6oc.onrender.com";

    // หน้าเว็บของเซิร์ฟเวอร์ ใช้สร้างลิงก์เชิญ https://.../join/<roomId>
    // (คนที่ได้รับลิงก์จะเข้าประชุมผ่านเบราว์เซอร์)
    public static final String LINK_HOST = "server-webrtc-w6oc.onrender.com";
    public static final String WEB_BASE_URL = "https://" + LINK_HOST;

    // รหัสสำหรับสร้างห้อง ต้องตรงกับตัวแปร CREATE_ROOM_KEY บนเซิร์ฟเวอร์ (Render -> Environment)
    // ** เปลี่ยนเป็นข้อความสุ่มยาว ๆ ของคุณเอง ทั้งที่นี่และบน Render **
    public static final String CREATE_ROOM_KEY = BuildConfig.CREATE_ROOM_KEY;
}
