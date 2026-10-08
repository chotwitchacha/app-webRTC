package com.example.deeplink_webrtc;

import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.deeplink_webrtc.deeplink.DeepLinkHelper;
import com.example.deeplink_webrtc.webrtc.MeetingManager;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * หน้าห้องประชุม: แสดงวิดีโอทุกคนเป็น Grid
 * 1 คน = เต็มจอ, 2 คน = บน/ล่าง, 3-4 คน = 2x2, 5-6 คน = 2x3
 */
public class MeetingActivity extends AppCompatActivity implements MeetingManager.Listener {

    public static final String EXTRA_ROOM_ID = "room_id";
    private static final String LOCAL_ID = "local";

    /** ห้องที่กำลังประชุมอยู่ (null = ไม่ได้อยู่ในห้อง) ให้ MainActivity เช็กตอนมีลิงก์เข้ามา */
    private static volatile String activeRoomId;

    @Nullable
    public static String getActiveRoomId() {
        return activeRoomId;
    }

    /** 1 ช่องวิดีโอ = กรอบ + SurfaceViewRenderer + ป้ายชื่อ */
    private static class Tile {
        final FrameLayout root;
        final SurfaceViewRenderer renderer;
        VideoTrack track;

        Tile(FrameLayout root, SurfaceViewRenderer renderer) {
            this.root = root;
            this.renderer = renderer;
        }
    }

    private final Map<String, Tile> tiles = new LinkedHashMap<>(); // local อยู่ลำดับแรกเสมอ

    private GridLayout videoGrid;
    private TextView statusText;
    private TextView recIndicator;
    private Button btnRecord;
    private Button btnMic, btnCamera;

    private MeetingManager meetingManager;
    private String roomId;
    private boolean micOn = true;
    private boolean cameraOn = true;
    private boolean destroyed = false;
    private boolean isHost = false;
    private boolean recordingActive = false;

    private AudioManager audioManager;
    private int previousAudioMode;
    private boolean previousSpeakerOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meeting);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        if (!DeepLinkHelper.isValidRoomId(roomId)) {
            finish();
            return;
        }
        activeRoomId = roomId;

        videoGrid = findViewById(R.id.videoGrid);
        statusText = findViewById(R.id.statusText);
        btnMic = findViewById(R.id.btnMic);
        btnCamera = findViewById(R.id.btnCamera);
        Button btnSwitch = findViewById(R.id.btnSwitchCamera);
        Button btnLeave = findViewById(R.id.btnLeave);
        Button btnInvite = findViewById(R.id.btnInvite);
        recIndicator = findViewById(R.id.recIndicator);
        btnRecord = findViewById(R.id.btnRecord);

        setupAudio();

        meetingManager = new MeetingManager(this, this);
        VideoTrack localVideo = meetingManager.startLocalMedia();
        addTile(LOCAL_ID, "คุณ", localVideo, true); // กล้องหน้าควร mirror
        meetingManager.joinRoom(Config.SIGNALING_URL, roomId);
        statusText.setText("กำลังเชื่อมต่อห้อง " + roomId + "...");

        btnMic.setOnClickListener(v -> {
            micOn = !micOn;
            meetingManager.setMicEnabled(micOn);
            btnMic.setText(micOn ? "ปิดไมค์" : "เปิดไมค์");
        });
        btnCamera.setOnClickListener(v -> {
            cameraOn = !cameraOn;
            meetingManager.setCameraEnabled(cameraOn);
            btnCamera.setText(cameraOn ? "ปิดกล้อง" : "เปิดกล้อง");
        });
        btnSwitch.setOnClickListener(v -> meetingManager.switchCamera());
        btnLeave.setOnClickListener(v -> finish());
        btnInvite.setOnClickListener(v -> startActivity(DeepLinkHelper.createShareIntent(roomId)));
        btnRecord.setOnClickListener(v -> showRecordDialog());
    }

    /**
     * ถูกเรียกเมื่อ MainActivity เปิดห้องขณะหน้านี้ยังเปิดอยู่ (CLEAR_TOP | SINGLE_TOP)
     * ห้องเดิม = แค่กลับมาหน้านี้, ห้องใหม่ = ย้ายห้องโดยไม่ต้องปิด/เปิดกล้องใหม่
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newRoomId = intent.getStringExtra(EXTRA_ROOM_ID);
        if (!DeepLinkHelper.isValidRoomId(newRoomId) || newRoomId.equals(roomId)) return;

        roomId = newRoomId;
        activeRoomId = newRoomId;
        isHost = false;
        btnRecord.setVisibility(android.view.View.GONE);
        statusText.setText("กำลังย้ายไปห้อง " + newRoomId + "...");
        meetingManager.switchRoom(Config.SIGNALING_URL, newRoomId);
    }

    // ---------------- Lifecycle ----------------
    @Override
    protected void onStart() {
        super.onStart();
        if (meetingManager != null) meetingManager.resumeCapture();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (meetingManager != null && !isFinishing()) meetingManager.pauseCapture();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (roomId != null && roomId.equals(activeRoomId)) activeRoomId = null;
        // ลำดับสำคัญ: ปล่อย renderer ก่อน แล้วค่อย release WebRTC (ซึ่งจะปล่อย EglBase ทีหลังสุด)
        for (Tile t : tiles.values()) releaseTile(t);
        tiles.clear();
        if (videoGrid != null) videoGrid.removeAllViews();
        if (meetingManager != null) meetingManager.release();
        restoreAudio();
        super.onDestroy();
    }

    // ---------------- Audio routing ----------------
    @SuppressWarnings("deprecation")
    private void setupAudio() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        previousAudioMode = audioManager.getMode();
        previousSpeakerOn = audioManager.isSpeakerphoneOn();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
    }

    @SuppressWarnings("deprecation")
    private void restoreAudio() {
        if (audioManager == null) return;
        audioManager.setMode(previousAudioMode);
        audioManager.setSpeakerphoneOn(previousSpeakerOn);
    }

    // ---------------- Video tiles ----------------
    private void addTile(String id, String label, VideoTrack track, boolean mirror) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF202124);

        SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
        renderer.init(meetingManager.getEglContext(), null);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(mirror);
        root.addView(renderer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        name.setBackgroundColor(0x80000000);
        name.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        nameLp.setMargins(dp(6), dp(6), dp(6), dp(6));
        root.addView(name, nameLp);

        Tile tile = new Tile(root, renderer);
        if (track != null) {
            track.addSink(renderer);
            tile.track = track;
        }
        tiles.put(id, tile);
        relayoutGrid();
    }

    private void removeTile(String id) {
        Tile tile = tiles.remove(id);
        if (tile == null) return;
        releaseTile(tile);
        relayoutGrid();
    }

    private void releaseTile(Tile tile) {
        if (tile.track != null) {
            try {
                tile.track.removeSink(tile.renderer);
            } catch (Exception ignored) {
                // track อาจถูก dispose ไปแล้วตอน PeerConnection ปิด
            }
            tile.track = null;
        }
        tile.renderer.release();
    }

    private void relayoutGrid() {
        int count = tiles.size();
        int columns = count <= 2 ? 1 : 2;
        int rows = Math.max(1, (int) Math.ceil(count / (double) columns));

        videoGrid.removeAllViews(); // ต้องลบก่อน ไม่งั้นเปลี่ยน columnCount แล้วจะ crash
        videoGrid.setColumnCount(columns);
        videoGrid.setRowCount(rows);

        int index = 0;
        for (Tile tile : tiles.values()) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(index / columns, 1f),
                    GridLayout.spec(index % columns, 1f));
            lp.width = 0;
            lp.height = 0;
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            videoGrid.addView(tile.root, lp);
            index++;
        }
        updateStatus();
    }

    private void updateStatus() {
        statusText.setText("ห้อง: " + roomId + "  •  ผู้เข้าร่วม " + tiles.size() + " คน"
                + (isHost ? "\nคุณเป็นผู้สร้างห้อง" : ""));
    }

    // ---------------- บันทึกการประชุม ----------------
    // การบันทึกทำในเบราว์เซอร์ (รวมภาพทุกคนลงไฟล์เดียว) แอปจึงพาผู้สร้างห้องไปเปิดห้องเดิมในเบราว์เซอร์
    // โดยส่งรหัสผู้สร้างห้องไปทาง #host=... ซึ่งไม่ถูกส่งไปที่เซิร์ฟเวอร์
    private void showRecordDialog() {
        String token = meetingManager.getHostToken(roomId);
        if (token == null) return;
        String fragment = "#host=" + Uri.encode(token);
        String roomUrl = Config.WEB_BASE_URL + "/room/" + Uri.encode(roomId) + fragment;
        String recordingsUrl = Config.WEB_BASE_URL + "/recordings/" + Uri.encode(roomId) + fragment;

        new AlertDialog.Builder(this)
                .setTitle("บันทึกการประชุม")
                .setMessage("การบันทึกทำผ่านเบราว์เซอร์\n\n"
                        + "กด \"ไปที่เบราว์เซอร์\" แอปจะออกจากห้อง แล้วเปิดห้องเดิมในเบราว์เซอร์ "
                        + "ในฐานะผู้สร้างห้อง จากนั้นกดปุ่ม \"บันทึก\" ที่ด้านล่างของหน้าเว็บ")
                .setPositiveButton("ไปที่เบราว์เซอร์", (d, w) -> {
                    openInBrowser(roomUrl);
                    finish();
                })
                .setNeutralButton("ดูไฟล์บันทึก", (d, w) -> openInBrowser(recordingsUrl))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void openInBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "ไม่พบเบราว์เซอร์ในเครื่อง", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------- MeetingManager.Listener (background thread) ----------------
    @Override
    public void onJoined(String selfId, boolean isHost) {
        runOnUiThread(() -> {
            if (destroyed) return;
            if (!isHost) {
                // แอปนี้ใช้สำหรับผู้สร้างห้องเท่านั้น ถ้าไม่ได้เป็นผู้สร้าง (เช่น Room ID ชนกับห้องคนอื่น
                // หรือลบแอปแล้วรหัสผู้สร้างห้องหาย) ให้ออกจากห้อง
                onError("คุณไม่ใช่ผู้สร้างห้อง " + roomId + " กรุณาสร้างห้องใหม่");
                return;
            }
            this.isHost = true;
            btnRecord.setVisibility(isHost ? android.view.View.VISIBLE : android.view.View.GONE);
            updateStatus();
        });
    }

    @Override
    public void onRecordingChanged(boolean active) {
        runOnUiThread(() -> {
            if (destroyed) return;
            if (active && !recordingActive) {
                Toast.makeText(this, "การประชุมนี้กำลังถูกบันทึก", Toast.LENGTH_LONG).show();
            }
            recordingActive = active;
            recIndicator.setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }

    @Override
    public void onRemoteVideoTrack(String peerId, VideoTrack track) {
        runOnUiThread(() -> {
            if (destroyed) return;
            Tile existing = tiles.get(peerId);
            if (existing != null) {
                if (existing.track != null) {
                    try { existing.track.removeSink(existing.renderer); } catch (Exception ignored) {}
                }
                track.addSink(existing.renderer);
                existing.track = track;
            } else {
                String label = "ผู้เข้าร่วม " + peerId.substring(0, Math.min(4, peerId.length()));
                addTile(peerId, label, track, false);
            }
        });
    }

    @Override
    public void onPeerLeft(String peerId) {
        runOnUiThread(() -> {
            if (!destroyed) removeTile(peerId);
        });
    }

    @Override
    public void onCameraSwitched(boolean isFrontCamera) {
        runOnUiThread(() -> {
            Tile local = tiles.get(LOCAL_ID);
            if (!destroyed && local != null) local.renderer.setMirror(isFrontCamera);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            if (destroyed) return;
            Toast.makeText(this, "การเชื่อมต่อหลุด", Toast.LENGTH_LONG).show();
            finish();
        });
    }
}
