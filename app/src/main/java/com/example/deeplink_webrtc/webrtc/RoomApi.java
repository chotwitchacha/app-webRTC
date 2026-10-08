package com.example.deeplink_webrtc.webrtc;

import android.os.Handler;
import android.os.Looper;

import com.example.deeplink_webrtc.Config;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * เรียก API ของเซิร์ฟเวอร์เพื่อสร้างห้อง + ลิงก์เชิญ (POST /api/rooms)
 * ห้องถูกสร้างบนเซิร์ฟเวอร์ทันที คนที่ได้รับลิงก์จึงเข้าได้เลย แม้ผู้สร้างยังไม่ได้เริ่มประชุม
 * callback ทั้งหมดถูกเรียกบน UI thread
 */
public final class RoomApi {

    public interface CreateCallback {
        void onCreated(String roomId, String link);
        void onError(String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // เซิร์ฟเวอร์ฟรีของ Render อาจต้องตื่นก่อน จึงรอได้นานกว่าปกติ
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final HostTokenStore tokenStore;
    private final Handler main = new Handler(Looper.getMainLooper());

    public RoomApi(HostTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    public void createRoom(CreateCallback callback) {
        String body;
        try {
            body = new JSONObject().put("createKey", Config.CREATE_ROOM_KEY).toString();
        } catch (JSONException e) {
            callback.onError("สร้างคำขอไม่สำเร็จ");
            return;
        }

        Request request = new Request.Builder()
                .url(Config.WEB_BASE_URL + "/api/rooms")
                .post(RequestBody.create(body, JSON))
                .build();

        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> callback.onError("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่"));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String text = r.body() != null ? r.body().string() : "";
                    JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                    if (!r.isSuccessful()) {
                        String msg = r.code() == 403
                                ? "รหัสสร้างห้อง (CREATE_ROOM_KEY) ไม่ตรงกับเซิร์ฟเวอร์"
                                : json.optString("error", "สร้างห้องไม่สำเร็จ (" + r.code() + ")");
                        main.post(() -> callback.onError(msg));
                        return;
                    }
                    String roomId = json.getString("roomId");
                    String hostToken = json.getString("hostToken");
                    String link = json.optString("link",
                            Config.WEB_BASE_URL + "/join/" + roomId);
                    // เก็บรหัสผู้สร้างห้อง ตอนเริ่มประชุมแอปจะส่งรหัสนี้ไป จึงเป็นผู้สร้างห้อง
                    tokenStore.put(roomId, hostToken);
                    main.post(() -> callback.onCreated(roomId, link));
                } catch (IOException | JSONException e) {
                    main.post(() -> callback.onError("ข้อมูลจากเซิร์ฟเวอร์ไม่ถูกต้อง"));
                }
            }
        });
    }
}