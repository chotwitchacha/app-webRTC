package com.example.deeplink_webrtc.webrtc;

import android.util.Log;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

/** SdpObserver ที่ implement ทุกเมธอดไว้แล้ว เลือก override เฉพาะที่ต้องใช้ */
public class SimpleSdpObserver implements SdpObserver {
    private static final String TAG = "SdpObserver";
    private final String label;

    public SimpleSdpObserver(String label) {
        this.label = label;
    }

    @Override public void onCreateSuccess(SessionDescription sdp) {}
    @Override public void onSetSuccess() {}

    @Override public void onCreateFailure(String error) {
        Log.e(TAG, label + " create failed: " + error);
    }

    @Override public void onSetFailure(String error) {
        Log.e(TAG, label + " set failed: " + error);
    }
}
