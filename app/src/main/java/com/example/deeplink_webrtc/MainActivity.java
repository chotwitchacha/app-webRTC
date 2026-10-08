package com.example.deeplink_webrtc;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.deeplink_webrtc.deeplink.DeepLinkHelper;
import com.example.deeplink_webrtc.webrtc.HostTokenStore;
import com.example.deeplink_webrtc.webrtc.RoomApi;

import java.util.Map;

/**
 * หน้าแรกของฝั่งผู้สร้างห้อง:
 * - สร้างลิงก์ห้องประชุม (เซิร์ฟเวอร์สร้างห้องให้ทันที) แล้วคัดลอก/แชร์ให้คนอื่นเข้าผ่านเบราว์เซอร์
 * - กลับเข้าห้องล่าสุดที่ตัวเองสร้างไว้
 * แอปนี้ไม่มีช่องกรอก Room ID ของคนอื่น (คนที่ได้รับลิงก์เข้าผ่านเว็บเท่านั้น)
 */
public class MainActivity extends AppCompatActivity {

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };
    private static final String PREFS = "main";
    private static final String KEY_LAST_ROOM = "last_room";

    private SharedPreferences prefs;
    private Button lastRoomButton;
    private TextView lastRoomLabel;
    private Button createButton;
    private View linkCard;
    private TextView roomIdText, linkText;
    private RoomApi roomApi;
    private String createdRoomId;
    private String createdLink;
    private String pendingRoomId;
    private AlertDialog currentDialog;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        roomApi = new RoomApi(new HostTokenStore(this));

        createButton = findViewById(R.id.createButton);
        linkCard = findViewById(R.id.linkCard);
        roomIdText = findViewById(R.id.roomIdText);
        linkText = findViewById(R.id.linkText);
        lastRoomButton = findViewById(R.id.lastRoomButton);
        lastRoomLabel = findViewById(R.id.lastRoomLabel);

        createButton.setOnClickListener(v -> onCreateRoomClicked());
        findViewById(R.id.copyButton).setOnClickListener(v -> copyLink());
        findViewById(R.id.shareButton).setOnClickListener(v -> {
            if (createdRoomId != null) startActivity(DeepLinkHelper.createShareIntent(createdRoomId));
        });
        findViewById(R.id.startButton).setOnClickListener(v -> {
            if (createdRoomId != null) requestJoin(createdRoomId);
        });
        lastRoomButton.setOnClickListener(v -> {
            String last = prefs.getString(KEY_LAST_ROOM, null);
            if (DeepLinkHelper.isValidRoomId(last)) requestJoin(last);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLastRoom();
    }

    @Override
    protected void onDestroy() {
        if (currentDialog != null) currentDialog.dismiss();
        super.onDestroy();
    }

    private void refreshLastRoom() {
        String last = prefs.getString(KEY_LAST_ROOM, null);
        boolean has = DeepLinkHelper.isValidRoomId(last) && !last.equals(createdRoomId);
        lastRoomButton.setVisibility(has ? View.VISIBLE : View.GONE);
        lastRoomLabel.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) lastRoomLabel.setText("ห้องล่าสุดของคุณ: " + last);
    }

    // ------------------------------------------------------------------
    // สร้างลิงก์ห้องประชุม
    // ------------------------------------------------------------------
    private void onCreateRoomClicked() {
        createButton.setEnabled(false);
        createButton.setText("กำลังสร้างลิงก์...");
        roomApi.createRoom(new RoomApi.CreateCallback() {
            @Override
            public void onCreated(String roomId, String link) {
                if (isFinishing()) return;
                createButton.setEnabled(true);
                createButton.setText("สร้างลิงก์ห้องประชุมใหม่");
                createdRoomId = roomId;
                createdLink = link;
                prefs.edit().putString(KEY_LAST_ROOM, roomId).apply();
                roomIdText.setText("Room ID: " + roomId);
                linkText.setText(link);
                linkCard.setVisibility(View.VISIBLE);
                refreshLastRoom();
            }

            @Override
            public void onError(String message) {
                if (isFinishing()) return;
                createButton.setEnabled(true);
                createButton.setText("สร้างลิงก์ห้องประชุม");
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void copyLink() {
        if (createdLink == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ลิงก์ห้องประชุม", createdLink));
        Toast.makeText(this, "คัดลอกลิงก์แล้ว", Toast.LENGTH_SHORT).show();
    }

    private void showDialog(AlertDialog.Builder builder) {
        if (currentDialog != null) currentDialog.dismiss();
        currentDialog = builder.show();
    }

    // ------------------------------------------------------------------
    // Permission + เปิดห้อง
    // ------------------------------------------------------------------
    private void requestJoin(String roomId) {
        pendingRoomId = roomId;
        if (hasAllPermissions()) {
            openMeeting();
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS);
        }
    }

    private boolean hasAllPermissions() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (hasAllPermissions()) {
            openMeeting();
            return;
        }

        boolean canAskAgain = false;
        for (String p : REQUIRED_PERMISSIONS) {
            if (shouldShowRequestPermissionRationale(p)) canAskAgain = true;
        }

        if (canAskAgain) {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle("ต้องการสิทธิ์กล้องและไมโครโฟน")
                    .setMessage("แอปต้องใช้กล้องและไมโครโฟนเพื่อส่งภาพและเสียงของคุณในห้องประชุม")
                    .setPositiveButton("ลองอีกครั้ง", (d, w) -> permissionLauncher.launch(REQUIRED_PERMISSIONS))
                    .setNegativeButton("ยกเลิก", null));
        } else {
            showDialog(new AlertDialog.Builder(this)
                    .setTitle("สิทธิ์ถูกปิดอยู่")
                    .setMessage("กรุณาเปิดสิทธิ์กล้องและไมโครโฟนในหน้าตั้งค่าแอป")
                    .setPositiveButton("ไปที่ตั้งค่า", (d, w) -> openAppSettings())
                    .setNegativeButton("ยกเลิก", null));
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void openMeeting() {
        prefs.edit().putString(KEY_LAST_ROOM, pendingRoomId).apply();
        Intent intent = new Intent(this, MeetingActivity.class);
        intent.putExtra(MeetingActivity.EXTRA_ROOM_ID, pendingRoomId);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }
}