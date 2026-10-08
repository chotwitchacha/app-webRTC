package com.example.deeplink_webrtc.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * หัวใจของระบบ Meeting แบบ Mesh:
 * - สร้าง PeerConnectionFactory + กล้อง/ไมค์ของเรา (local tracks)
 * - มี PeerConnection 1 ตัว ต่อ ผู้เข้าร่วม 1 คน
 * - คนที่ "เข้าห้องทีหลัง" เป็นฝ่ายสร้าง Offer ไปหาทุกคนที่อยู่ก่อน (ป้องกัน offer ชนกัน)
 *
 * การทำงานกับ PeerConnection ทั้งหมดรันบน executor thread เดียว เพื่อไม่ให้ state ชนกัน
 * callback ของ Listener ถูกเรียกจาก background thread -> ฝั่ง UI ต้อง runOnUiThread เอง
 */
public class MeetingManager implements SignalingClient.Listener {

    public interface Listener {
        void onJoined(String selfId, boolean isHost);
        void onRecordingChanged(boolean active);
        void onRemoteVideoTrack(String peerId, VideoTrack track);
        void onPeerLeft(String peerId);
        void onCameraSwitched(boolean isFrontCamera);
        void onError(String message);
        void onDisconnected();
    }

    private static final String TAG = "MeetingManager";
    private static final String STREAM_ID = "local_stream";
    // Mesh = ต้องอัปโหลดวิดีโอแยกให้ทุกคน จึงใช้ความละเอียดไม่สูงมาก
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;
    private static final int VIDEO_FPS = 24;

    private final Context appContext;
    private final Listener listener;
    private final EglBase eglBase;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<PeerConnection.IceServer> iceServers = new ArrayList<>();
    private final HostTokenStore hostTokenStore;

    // เข้าถึงจาก executor thread เท่านั้น
    private final Map<String, PeerConnection> peers = new HashMap<>();
    private final Map<String, List<IceCandidate>> pendingCandidates = new HashMap<>();

    private PeerConnectionFactory factory;
    private volatile SignalingClient signaling;

    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private boolean capturing = false;

    private volatile boolean released = false;

    public MeetingManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.eglBase = EglBase.create();
        this.hostTokenStore = new HostTokenStore(appContext);

        // STUN ฟรีของ Google: ใช้ได้เมื่ออยู่เครือข่ายทั่วไป
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        // ถ้าต่อข้ามเครือข่าย 4G/NAT เข้มงวดแล้ววิดีโอไม่ขึ้น ต้องเพิ่ม TURN server เช่น coturn:
        // iceServers.add(PeerConnection.IceServer.builder("turn:your-turn-server:3478")
        //         .setUsername("user").setPassword("pass").createIceServer());

        initFactory();
    }

    public EglBase.Context getEglContext() {
        return eglBase.getEglBaseContext();
    }

    // ------------------------------------------------------------------
    // 1) สร้าง PeerConnectionFactory
    // ------------------------------------------------------------------
    private void initFactory() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .createInitializationOptions());

        AudioDeviceModule adm = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        adm.release(); // factory ถือ reference ไว้แล้ว
    }

    // ------------------------------------------------------------------
    // 2) เปิดกล้อง + ไมค์ (เรียกหลังได้ permission แล้ว)
    // ------------------------------------------------------------------
    /** @return VideoTrack ของกล้องเรา (เอาไปแสดงใน SurfaceViewRenderer) หรือ null ถ้าไม่มีกล้อง */
    public VideoTrack startLocalMedia() {
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);

        videoCapturer = createCameraCapturer();
        if (videoCapturer == null) {
            Log.w(TAG, "No camera found, audio only");
            return null;
        }
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);
        capturing = true;

        localVideoTrack = factory.createVideoTrack("video0", videoSource);
        return localVideoTrack;
    }

    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(appContext)
                ? new Camera2Enumerator(appContext)
                : new Camera1Enumerator(true);
        String[] names = enumerator.getDeviceNames();

        for (String name : names) { // หากล้องหน้าก่อน
            if (enumerator.isFrontFacing(name)) {
                VideoCapturer c = enumerator.createCapturer(name, null);
                if (c != null) return c;
            }
        }
        for (String name : names) {
            VideoCapturer c = enumerator.createCapturer(name, null);
            if (c != null) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 3) เข้าห้อง
    // ------------------------------------------------------------------
    public void joinRoom(String signalingUrl, String roomId) {
        signaling = new SignalingClient(this);
        signaling.connect(signalingUrl, roomId, hostTokenStore.get(roomId));
    }

    /**
     * ย้ายไปห้องใหม่โดยไม่ต้องปิด/เปิดกล้องและไมค์ใหม่ (ใช้ตอนกดลิงก์ห้องอื่นขณะประชุมอยู่)
     * ปิด connection กับทุกคนในห้องเดิม แล้วต่อ Signaling ใหม่ด้วย Room ID ใหม่
     */
    public void switchRoom(String signalingUrl, String newRoomId) {
        if (released) return;
        SignalingClient old = signaling;
        signaling = null;
        if (old != null) old.close(); // หลัง close() ข้อความจากห้องเดิมจะถูกทิ้ง

        // executor ทำงานตามลำดับ: งานจากห้องเดิมที่ค้างอยู่จะรันก่อน แล้วถูกปิดทิ้งในงานนี้
        post(() -> {
            for (String peerId : new ArrayList<>(peers.keySet())) {
                closePeer(peerId);
                listener.onPeerLeft(peerId);
            }
            pendingCandidates.clear();

            SignalingClient client = new SignalingClient(this);
            signaling = client;
            client.connect(signalingUrl, newRoomId, hostTokenStore.get(newRoomId));
        });
    }

    // ------------------------------------------------------------------
    // ควบคุมไมค์/กล้อง
    // ------------------------------------------------------------------
    public void setMicEnabled(boolean enabled) {
        if (!released && localAudioTrack != null) localAudioTrack.setEnabled(enabled);
    }

    public void setCameraEnabled(boolean enabled) {
        // setEnabled(false) = ส่งเฟรมดำให้คนอื่น
        if (!released && localVideoTrack != null) localVideoTrack.setEnabled(enabled);
    }

    public void switchCamera() {
        if (released || !(videoCapturer instanceof CameraVideoCapturer)) return;
        ((CameraVideoCapturer) videoCapturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override public void onCameraSwitchDone(boolean isFrontCamera) {
                listener.onCameraSwitched(isFrontCamera);
            }
            @Override public void onCameraSwitchError(String error) {
                Log.e(TAG, "switchCamera error: " + error);
            }
        });
    }

    /** เรียกตอนแอปไป background (Android ไม่ให้ใช้กล้องเบื้องหลัง) */
    public void pauseCapture() {
        if (released || videoCapturer == null || !capturing) return;
        try {
            videoCapturer.stopCapture();
            capturing = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void resumeCapture() {
        if (released || videoCapturer == null || capturing) return;
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);
        capturing = true;
    }

    // ------------------------------------------------------------------
    // SignalingClient.Listener (ถูกเรียกจาก OkHttp thread -> โยนเข้า executor)
    // ------------------------------------------------------------------
    /** รหัสผู้สร้างห้องที่เก็บไว้ (null = ไม่ใช่ผู้สร้างห้องนี้) ใช้เปิดหน้าเว็บในฐานะผู้สร้างห้อง */
    @Nullable
    public String getHostToken(String roomId) {
        return hostTokenStore.get(roomId);
    }

    @Override
    public void onJoined(String roomId, String selfId, List<String> existingPeerIds,
                         boolean isHost, @Nullable String newHostToken, boolean recordingActive) {
        post(() -> {
            Log.d(TAG, "Joined " + roomId + " as " + selfId + (isHost ? " (host)" : "")
                    + ", existing peers: " + existingPeerIds);
            if (newHostToken != null) hostTokenStore.put(roomId, newHostToken);
            listener.onJoined(selfId, isHost);
            listener.onRecordingChanged(recordingActive);
            // เราเข้ามาทีหลัง -> เราเป็นคนสร้าง Offer ไปหาทุกคน
            for (String peerId : existingPeerIds) {
                PeerConnection pc = getOrCreatePeer(peerId);
                if (pc != null) createOffer(peerId, pc);
            }
        });
    }

    @Override
    public void onPeerJoined(String peerId) {
        // ไม่ต้องทำอะไร: คนที่เข้ามาใหม่จะส่ง Offer มาหาเราเอง
        Log.d(TAG, "Peer joined: " + peerId);
    }

    @Override
    public void onPeerLeft(String peerId) {
        post(() -> {
            closePeer(peerId);
            listener.onPeerLeft(peerId);
        });
    }

    @Override
    public void onOffer(String fromId, SessionDescription offer) {
        post(() -> {
            PeerConnection pc = getOrCreatePeer(fromId);
            if (pc == null) return;
            pc.setRemoteDescription(new SimpleSdpObserver("setRemote(offer)") {
                @Override public void onSetSuccess() {
                    post(() -> {
                        if (peers.get(fromId) != pc) return;
                        drainPendingCandidates(fromId, pc);
                        createAnswer(fromId, pc);
                    });
                }
            }, offer);
        });
    }

    @Override
    public void onAnswer(String fromId, SessionDescription answer) {
        post(() -> {
            PeerConnection pc = peers.get(fromId);
            if (pc == null) return;
            pc.setRemoteDescription(new SimpleSdpObserver("setRemote(answer)") {
                @Override public void onSetSuccess() {
                    post(() -> {
                        if (peers.get(fromId) == pc) drainPendingCandidates(fromId, pc);
                    });
                }
            }, answer);
        });
    }

    @Override
    public void onRemoteIceCandidate(String fromId, IceCandidate candidate) {
        post(() -> {
            PeerConnection pc = peers.get(fromId);
            // candidate อาจมาถึงก่อน offer/answer -> เก็บไว้ก่อน
            if (pc == null || pc.getRemoteDescription() == null) {
                List<IceCandidate> list = pendingCandidates.get(fromId);
                if (list == null) {
                    list = new ArrayList<>();
                    pendingCandidates.put(fromId, list);
                }
                list.add(candidate);
            } else {
                pc.addIceCandidate(candidate);
            }
        });
    }

    @Override
    public void onRecordingChanged(boolean active) {
        if (!released) listener.onRecordingChanged(active);
    }

    @Override
    public void onSignalingError(String message) {
        if (!released) listener.onError(message);
    }

    @Override
    public void onSignalingClosed() {
        if (!released) listener.onDisconnected();
    }

    // ------------------------------------------------------------------
    // PeerConnection helpers (รันบน executor)
    // ------------------------------------------------------------------
    private PeerConnection getOrCreatePeer(String peerId) {
        PeerConnection existing = peers.get(peerId);
        if (existing != null) return existing;

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        PeerConnection pc = factory.createPeerConnection(config, new SimplePeerObserver() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                SignalingClient s = signaling;
                if (s != null) s.sendIceCandidate(peerId, candidate);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.d(TAG, "ICE [" + peerId + "] -> " + state);
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                MediaStreamTrack track = receiver.track();
                // Audio track จะเล่นออกลำโพงอัตโนมัติ ส่วน Video ต้องส่งไปให้ UI แสดง
                if (track instanceof VideoTrack && !released) {
                    listener.onRemoteVideoTrack(peerId, (VideoTrack) track);
                }
            }
        });

        if (pc == null) {
            Log.e(TAG, "createPeerConnection failed for " + peerId);
            return null;
        }

        List<String> streamIds = Collections.singletonList(STREAM_ID);
        if (localAudioTrack != null) pc.addTrack(localAudioTrack, streamIds);
        if (localVideoTrack != null) pc.addTrack(localVideoTrack, streamIds);

        peers.put(peerId, pc);
        return pc;
    }

    private void createOffer(String peerId, PeerConnection pc) {
        pc.createOffer(new SimpleSdpObserver("createOffer") {
            @Override public void onCreateSuccess(SessionDescription offer) {
                post(() -> {
                    if (peers.get(peerId) != pc) return;
                    pc.setLocalDescription(new SimpleSdpObserver("setLocal(offer)") {
                        @Override public void onSetSuccess() {
                            SignalingClient s = signaling;
                            if (s != null) s.sendOffer(peerId, offer);
                        }
                    }, offer);
                });
            }
        }, new MediaConstraints());
    }

    private void createAnswer(String peerId, PeerConnection pc) {
        pc.createAnswer(new SimpleSdpObserver("createAnswer") {
            @Override public void onCreateSuccess(SessionDescription answer) {
                post(() -> {
                    if (peers.get(peerId) != pc) return;
                    pc.setLocalDescription(new SimpleSdpObserver("setLocal(answer)") {
                        @Override public void onSetSuccess() {
                            SignalingClient s = signaling;
                            if (s != null) s.sendAnswer(peerId, answer);
                        }
                    }, answer);
                });
            }
        }, new MediaConstraints());
    }

    private void drainPendingCandidates(String peerId, PeerConnection pc) {
        List<IceCandidate> list = pendingCandidates.remove(peerId);
        if (list == null) return;
        for (IceCandidate c : list) pc.addIceCandidate(c);
    }

    private void closePeer(String peerId) {
        pendingCandidates.remove(peerId);
        PeerConnection pc = peers.remove(peerId);
        if (pc != null) pc.dispose();
    }

    private void post(Runnable task) {
        if (released) return;
        try {
            executor.execute(() -> {
                if (!released) task.run();
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 4) ออกจากห้อง + คืนทรัพยากรทั้งหมด
    //    ต้องเรียกหลังจาก UI ปล่อย SurfaceViewRenderer แล้ว
    // ------------------------------------------------------------------
    public void release() {
        if (released) return;
        released = true;

        if (signaling != null) {
            signaling.close();
            signaling = null;
        }

        executor.execute(() -> {
            for (PeerConnection pc : peers.values()) pc.dispose();
            peers.clear();
            pendingCandidates.clear();

            if (videoCapturer != null) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                videoCapturer.dispose();
            }
            if (localVideoTrack != null) localVideoTrack.dispose();
            if (videoSource != null) videoSource.dispose();
            if (surfaceTextureHelper != null) surfaceTextureHelper.dispose();
            if (localAudioTrack != null) localAudioTrack.dispose();
            if (audioSource != null) audioSource.dispose();
            if (factory != null) factory.dispose();
            eglBase.release();
            Log.d(TAG, "Released all WebRTC resources");
        });
        executor.shutdown();
    }
}
