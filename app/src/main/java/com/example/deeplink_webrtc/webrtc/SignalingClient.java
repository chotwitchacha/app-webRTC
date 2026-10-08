package com.example.deeplink_webrtc.webrtc;

import android.util.Log;

import androidx.annotation.Nullable;

import com.example.deeplink_webrtc.Config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * จัดการการเชื่อมต่อ WebSocket กับ Signaling Server
 * หน้าที่: ส่ง/รับ join, offer, answer, ICE candidate ระหว่าง peer
 * หมายเหตุ: callback ทั้งหมดถูกเรียกจาก thread ของ OkHttp (ไม่ใช่ UI thread)
 */
public class SignalingClient {

    public interface Listener {
        /**
         * @param isHost         เราเป็นผู้สร้างห้องหรือไม่
         * @param newHostToken   ไม่เป็น null เมื่อเราเป็นคนแรกที่เข้าห้อง (ต้องเก็บไว้)
         * @param recordingActive ห้องกำลังถูกบันทึกอยู่หรือไม่
         */
        void onJoined(String roomId, String selfId, List<String> existingPeerIds,
                      boolean isHost, @Nullable String newHostToken, boolean recordingActive);
        void onRecordingChanged(boolean active);
        void onPeerJoined(String peerId);
        void onPeerLeft(String peerId);
        void onOffer(String fromId, SessionDescription sdp);
        void onAnswer(String fromId, SessionDescription sdp);
        void onRemoteIceCandidate(String fromId, IceCandidate candidate);
        void onSignalingError(String message);
        void onSignalingClosed();
    }

    private static final String TAG = "SignalingClient";

    private final Listener listener;
    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private String roomId;
    private volatile boolean closedByUser = false;

    public SignalingClient(Listener listener) {
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS) // keep-alive กันโดนตัด connection
                .build();
    }

    public void connect(String url, String roomId, @Nullable String hostToken) {
        this.roomId = roomId;
        Request request = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "Connected, joining room " + roomId);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "join");
                    msg.put("room", roomId);
                    if (hostToken != null) msg.put("hostToken", hostToken);
                    // แอปนี้เป็นฝั่งผู้สร้างห้อง: ขอสร้างห้องถ้ายังไม่มี (เว็บ/คนรับลิงก์ทำแบบนี้ไม่ได้)
                    msg.put("create", true);
                    msg.put("createKey", Config.CREATE_ROOM_KEY);
                    ws.send(msg.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "join message error", e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (closedByUser) return; // ปิดไปแล้ว (เช่นย้ายห้อง) ไม่ต้องสนใจข้อความที่ค้างอยู่
                handleMessage(text);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "Closed: " + code + " " + reason);
                if (!closedByUser) listener.onSignalingClosed();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                if (!closedByUser) {
                    listener.onSignalingError("เชื่อมต่อ Signaling Server ไม่ได้: " + t.getMessage());
                }
            }
        });
    }

    private void handleMessage(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            switch (type) {
                case "joined": {
                    List<String> ids = new ArrayList<>();
                    JSONArray arr = m.optJSONArray("peers");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
                    }
                    String newToken = m.isNull("hostToken") ? null : m.optString("hostToken", null);
                    listener.onJoined(roomId, m.getString("id"), ids,
                            m.optBoolean("isHost", false), newToken, m.optBoolean("recording", false));
                    break;
                }
                case "peer-joined":
                    listener.onPeerJoined(m.getString("id"));
                    break;
                case "peer-left":
                    listener.onPeerLeft(m.getString("id"));
                    break;
                case "offer":
                    listener.onOffer(m.getString("from"),
                            new SessionDescription(SessionDescription.Type.OFFER, m.getString("sdp")));
                    break;
                case "answer":
                    listener.onAnswer(m.getString("from"),
                            new SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")));
                    break;
                case "candidate": {
                    String sdpMid = m.isNull("sdpMid") ? null : m.getString("sdpMid");
                    IceCandidate candidate = new IceCandidate(
                            sdpMid, m.getInt("sdpMLineIndex"), m.getString("candidate"));
                    listener.onRemoteIceCandidate(m.getString("from"), candidate);
                    break;
                }
                case "recording":
                    listener.onRecordingChanged(m.optBoolean("active", false));
                    break;
                case "error":
                    listener.onSignalingError(m.optString("message", "Unknown error"));
                    break;
                default:
                    Log.w(TAG, "Unknown message type: " + type);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid message: " + text, e);
        }
    }

    public void sendOffer(String toId, SessionDescription sdp) {
        sendSdp("offer", toId, sdp);
    }

    public void sendAnswer(String toId, SessionDescription sdp) {
        sendSdp("answer", toId, sdp);
    }

    private void sendSdp(String type, String toId, SessionDescription sdp) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", type);
            m.put("to", toId);
            m.put("sdp", sdp.description);
            send(m);
        } catch (JSONException e) {
            Log.e(TAG, "sendSdp error", e);
        }
    }

    public void sendIceCandidate(String toId, IceCandidate candidate) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "candidate");
            m.put("to", toId);
            m.put("sdpMid", candidate.sdpMid);
            m.put("sdpMLineIndex", candidate.sdpMLineIndex);
            m.put("candidate", candidate.sdp);
            send(m);
        } catch (JSONException e) {
            Log.e(TAG, "sendIceCandidate error", e);
        }
    }

    private void send(JSONObject msg) {
        WebSocket ws = webSocket;
        if (ws != null) ws.send(msg.toString()); // OkHttp WebSocket.send() เป็น thread-safe
    }

    public void close() {
        closedByUser = true;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.send(new JSONObject().put("type", "leave").toString());
            } catch (JSONException ignored) {
            }
            ws.close(1000, "bye");
        }
    }
}
