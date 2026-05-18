package com.j2cheng.cam2stream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Headless foreground service that owns the Camera2 + MediaCodec capture
 * pipeline and the native RTSP server.
 *
 * Lifecycle is driven by {@link CameraManager.AvailabilityCallback}:
 *
 *   - When the back-facing camera becomes available, we start the streamer.
 *   - When it becomes unavailable while we are NOT streaming (another app
 *     grabbed it), we just log and wait.
 *   - When it becomes unavailable while we ARE streaming, the signal is
 *     almost always caused by us holding the camera, so we ignore it.
 *     Real eviction by a higher-priority client is signaled via
 *     CameraDevice.StateCallback.onDisconnected / onError inside
 *     CameraRtspServer, which calls stop() internally. The availability
 *     callback then fires onCameraAvailable again when the camera is free
 *     and we restart automatically.
 *
 * No window or popup is ever shown.
 */
public final class StreamerService extends Service {

    private static final String TAG = "StreamerService";

    public static final String ACTION_START = "com.j2cheng.cam2stream.action.START";
    public static final String ACTION_STOP  = "com.j2cheng.cam2stream.action.STOP";

    private static final String CHANNEL_ID = "streamer-fg";
    private static final int    NOTIF_ID   = 0xC4F3;

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("appnative");
    }

    private CameraManager cameraManager;
    private HandlerThread cbThread;
    private Handler       cbHandler;

    /** Camera id whose availability we track. */
    private String targetCameraId;

    /** Live streamer instance; null when not running. */
    private CameraRtspServer server;

    private boolean gstInitialized;

    /** True between ACTION_START and ACTION_STOP / onDestroy. */
    private boolean serviceActive;

    private final CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(@NonNull String cameraId) {
                    Log.i(TAG, "[avail] onCameraAvailable id=" + cameraId
                            + " (target=" + targetCameraId
                            + ", streamerRunning=" + isStreamerRunning() + ")");

                    if (!cameraId.equals(targetCameraId)) {
                        return;
                    }
                    if (!serviceActive) {
                        Log.i(TAG, "[avail] service not active, ignoring");
                        return;
                    }
                    if (isStreamerRunning()) {
                        Log.i(TAG, "[avail] streamer already running, no-op");
                        return;
                    }
                    Log.i(TAG, "[avail] >>> target camera available, starting streamer");
                    startStreamer();
                }

                @Override
                public void onCameraUnavailable(@NonNull String cameraId) {
                    Log.i(TAG, "[avail] onCameraUnavailable id=" + cameraId
                            + " (target=" + targetCameraId
                            + ", streamerRunning=" + isStreamerRunning() + ")");

                    if (!cameraId.equals(targetCameraId)) {
                        return;
                    }
                    if (isStreamerRunning()) {
                        // Almost certainly us. Real eviction is reported via
                        // CameraDevice.StateCallback inside CameraRtspServer.
                        Log.i(TAG, "[avail] target camera unavailable while we own it"
                                + " -- ignoring (StateCallback drives actual stop)");
                    } else {
                        Log.i(TAG, "[avail] target camera unavailable and we are idle"
                                + " -- waiting for it to become free");
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "[svc] onCreate");
        createNotificationChannel();

        try {
            org.freedesktop.gstreamer.GStreamer.init(this);
            gstInitialized = true;
            Log.i(TAG, "[svc] GStreamer initialized");
        } catch (Exception e) {
            Log.e(TAG, "[svc] GStreamer.init failed", e);
        }

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        cbThread = new HandlerThread("StreamerService-Cam");
        cbThread.start();
        cbHandler = new Handler(cbThread.getLooper());

        try {
            targetCameraId = CameraRtspServer.pickBackCameraId(cameraManager);
            Log.i(TAG, "[svc] target camera id resolved: " + targetCameraId);
        } catch (Exception e) {
            Log.e(TAG, "[svc] cannot resolve target camera id", e);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        Log.i(TAG, "[svc] onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            stopAll("ACTION_STOP");
            return START_NOT_STICKY;
        }

        // Move to foreground first (required to use the camera on Android 14+).
        Notification n = buildNotification("Waiting for camera...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }
        Log.i(TAG, "[svc] foreground started, notif id=" + NOTIF_ID);

        if (!gstInitialized) {
            Log.e(TAG, "[svc] GStreamer not initialized, aborting");
            updateNotification("GStreamer init failed");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (targetCameraId == null) {
            Log.e(TAG, "[svc] no target camera id, aborting");
            updateNotification("No camera found");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!serviceActive) {
            serviceActive = true;
            Log.i(TAG, "[svc] registering CameraManager.AvailabilityCallback"
                    + " (target=" + targetCameraId + ")");
            // Registration triggers onCameraAvailable/onCameraUnavailable for
            // every current camera, so we react to current state immediately.
            cameraManager.registerAvailabilityCallback(availabilityCallback, cbHandler);
        } else {
            Log.i(TAG, "[svc] already active, leaving availability watcher registered");
        }

        // START_STICKY: Android restarts the service if killed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[svc] onDestroy");
        stopAll("onDestroy");
        if (cbThread != null) {
            cbThread.quitSafely();
            cbThread = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------

    private boolean isStreamerRunning() {
        return server != null && server.isRunning();
    }

    private void startStreamer() {
        // Each cycle uses a fresh CameraRtspServer because its HandlerThread
        // cannot be restarted once quit.
        CameraRtspServer s = new CameraRtspServer(this);
        try {
            s.start();
            server = s;
            updateNotification("Streaming on " + s.getRtspPort() + s.getRtspPath());
            Log.i(TAG, "[svc] streamer started on port " + s.getRtspPort()
                    + " mount=" + s.getRtspPath());
        } catch (Exception e) {
            Log.e(TAG, "[svc] streamer start failed", e);
            updateNotification("Start failed: " + e.getMessage());
            try { s.stop(); } catch (Throwable ignored) {}
            server = null;
        }
    }

    private void stopStreamer(String reason) {
        if (server == null) {
            Log.i(TAG, "[svc] stopStreamer(" + reason + "): nothing to stop");
            return;
        }
        Log.i(TAG, "[svc] stopStreamer(" + reason + ")");
        try { server.stop(); } catch (Throwable t) { Log.e(TAG, "stop", t); }
        server = null;
        updateNotification("Camera unavailable, waiting...");
    }

    private void stopAll(String reason) {
        Log.i(TAG, "[svc] stopAll(" + reason + ")");
        if (serviceActive) {
            serviceActive = false;
            try {
                cameraManager.unregisterAvailabilityCallback(availabilityCallback);
                Log.i(TAG, "[svc] availability callback unregistered");
            } catch (Throwable t) {
                Log.w(TAG, "[svc] unregisterAvailabilityCallback threw", t);
            }
        }
        stopStreamer("stopAll");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Notification plumbing
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Streamer",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Camera2 -> MediaCodec -> RTSP streaming");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Streamer")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }
}
