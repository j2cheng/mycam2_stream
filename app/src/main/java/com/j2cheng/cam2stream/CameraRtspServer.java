package com.j2cheng.cam2stream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Zero-copy capture/encode bridge:
 *
 *   Camera2 (TEMPLATE_RECORD)
 *     -> MediaCodec input Surface  (HW BufferQueue, no CPU copy of pixels)
 *     -> HEVC bitstream out
 *     -> nativePushH265Data() into GStreamer appsrc
 *
 * Only the already-compressed access units cross the JNI boundary.
 */
public final class CameraRtspServer {

    private static final String TAG = "CameraRtspServer";

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_HEVC; // "video/hevc"
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int BITRATE = 4_500_000; // 4.5 Mbps
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL_SEC = 1;
    private static final int RTSP_PORT = 8554;
    private static final String RTSP_MOUNT = "/live";

    private final Context appContext;
    private final HandlerThread cameraThread = new HandlerThread("CameraRtspServer-Camera");
    private Handler cameraHandler;

    private MediaCodec mediaCodec;
    private Surface encoderInputSurface;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private Thread outputThread;
    private volatile boolean running;

    // Anchors MediaCodec's monotonic presentationTimeUs (derived from Camera2
    // sensor timestamps, in nanoseconds) so the first pushed PTS is 0.
    private long firstFrameTimeUs = -1;

    public CameraRtspServer(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public String getRtspPath() {
        return RTSP_MOUNT;
    }

    public int getRtspPort() {
        return RTSP_PORT;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Picks the back-facing camera id, falling back to the first camera.
     * Useful for the service's CameraManager.AvailabilityCallback so it can
     * know which id to watch.
     */
    public static String pickBackCameraId(@NonNull CameraManager mgr)
            throws CameraAccessException, IOException {
        for (String id : mgr.getCameraIdList()) {
            Integer facing = mgr.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
            if (facing != null && facing
                    == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = mgr.getCameraIdList();
        if (ids.length == 0) {
            throw new IOException("No cameras on device");
        }
        return ids[0];
    }

    /**
     * Starts MediaCodec, opens Camera2, and brings up the native RTSP server.
     * Safe to call from the main thread; all camera work happens on a
     * dedicated background thread.
     */
    public void start() throws IOException, CameraAccessException {
        if (running) {
            Log.i(TAG, "start(): already running, ignoring");
            return;
        }
        Log.i(TAG, "start(): bringing up streamer");
        running = true;
        firstFrameTimeUs = -1;

        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        Log.i(TAG, "start(): configuring HEVC encoder " + WIDTH + "x" + HEIGHT
                + "@" + FRAME_RATE + "fps, bitrate=" + BITRATE);
        configureEncoder();

        // Bring up GStreamer first so a client connecting immediately doesn't
        // miss the first access unit. Native side silently drops buffers until
        // a client triggers media-configure, so ordering vs. encoder start is
        // not safety-critical, only latency.
        Log.i(TAG, "start(): starting native RTSP server on port " + RTSP_PORT
                + " mount=" + RTSP_MOUNT);
        nativeStartRtspServer(RTSP_PORT, RTSP_MOUNT);

        startOutputDrainLoop();

        CameraManager cameraManager =
                (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            throw new IOException("CameraManager unavailable");
        }
        String cameraId = pickBackCameraId(cameraManager);
        Log.i(TAG, "start(): opening camera id=" + cameraId);
        openCameraAndBindSurface(cameraManager, cameraId);
    }

    public void stop() {
        if (!running) {
            Log.i(TAG, "stop(): not running, ignoring");
            return;
        }
        Log.i(TAG, "stop(): tearing down streamer");
        running = false;

        if (captureSession != null) {
            try { captureSession.close(); } catch (Throwable ignored) {}
            captureSession = null;
            Log.i(TAG, "stop(): capture session closed");
        }
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Throwable ignored) {}
            cameraDevice = null;
            Log.i(TAG, "stop(): camera device closed");
        }
        if (outputThread != null) {
            outputThread.interrupt();
            try { outputThread.join(500); } catch (InterruptedException ignored) {}
            outputThread = null;
        }
        if (mediaCodec != null) {
            try { mediaCodec.stop(); } catch (Throwable ignored) {}
            try { mediaCodec.release(); } catch (Throwable ignored) {}
            mediaCodec = null;
            Log.i(TAG, "stop(): encoder released");
        }
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }

        nativeStopRtspServer();
        Log.i(TAG, "stop(): native RTSP server stopped");

        cameraThread.quitSafely();
        Log.i(TAG, "stop(): done");
    }

    // ---------------------------------------------------------------------
    // Setup helpers
    // ---------------------------------------------------------------------

    private void configureEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC);
        // Annex-B byte-stream output, what h265parse expects with stream-format=byte-stream.
        // MediaCodec on Android always emits Annex-B for video/hevc.

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderInputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();
    }

    @SuppressLint("MissingPermission")
    private void openCameraAndBindSurface(CameraManager manager, String id)
            throws CameraAccessException {
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Log.i(TAG, "[cam] onOpened id=" + camera.getId());
                cameraDevice = camera;
                try {
                    final CaptureRequest.Builder builder =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(encoderInputSurface);
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            new Range<>(FRAME_RATE, FRAME_RATE));

                    camera.createCaptureSession(
                            Collections.singletonList(encoderInputSurface),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession session) {
                                    Log.i(TAG, "[cam] capture session configured");
                                    captureSession = session;
                                    try {
                                        session.setRepeatingRequest(
                                                builder.build(), null, cameraHandler);
                                        Log.i(TAG, "[cam] repeating request submitted, frames flowing into encoder");
                                    } catch (CameraAccessException e) {
                                        Log.e(TAG, "[cam] setRepeatingRequest failed", e);
                                    }
                                }

                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                    Log.e(TAG, "[cam] capture session configure failed");
                                    stop();
                                }
                            }, cameraHandler);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "[cam] createCaptureRequest failed", e);
                }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(TAG, "[cam] onDisconnected id=" + camera.getId()
                        + " -- another app likely took the camera; stopping streamer");
                stop();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(TAG, "[cam] onError id=" + camera.getId() + " error=" + error
                        + " (" + cameraErrorName(error) + ") -- stopping streamer");
                stop();
            }
        }, cameraHandler);
    }

    private static String cameraErrorName(int e) {
        switch (e) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:        return "IN_USE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:   return "MAX_IN_USE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:      return "DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:        return "DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:       return "SERVICE";
            default: return "UNKNOWN";
        }
    }

    // ---------------------------------------------------------------------
    // MediaCodec output drain -> JNI push
    // ---------------------------------------------------------------------

    private void startOutputDrainLoop() {
        outputThread = new Thread(() -> {
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running && !Thread.currentThread().isInterrupted()) {
                int idx;
                try {
                    idx = mediaCodec.dequeueOutputBuffer(info, 10_000);
                } catch (IllegalStateException e) {
                    // codec was released
                    break;
                }
                if (idx < 0) {
                    continue; // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_FORMAT_CHANGED / etc.
                }
                ByteBuffer outBuf = mediaCodec.getOutputBuffer(idx);
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);

                    // We intentionally push CODEC_CONFIG (VPS/SPS/PPS) buffers too;
                    // h265parse needs them to advertise caps to rtph265pay.
                    boolean isCodecConfig =
                            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean isKeyframe =
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                    if (firstFrameTimeUs == -1 && !isCodecConfig) {
                        firstFrameTimeUs = info.presentationTimeUs;
                    }
                    long ptsUs = isCodecConfig ? 0L
                            : (info.presentationTimeUs - firstFrameTimeUs);

                    byte[] chunk = new byte[info.size];
                    outBuf.get(chunk);
                    nativePushH265Data(chunk, info.size, ptsUs, isKeyframe, isCodecConfig);
                }
                try {
                    mediaCodec.releaseOutputBuffer(idx, false);
                } catch (IllegalStateException ignored) {
                    break;
                }
            }
        }, "CameraRtspServer-Drain");
        outputThread.start();
    }

    // ---------------------------------------------------------------------
    // JNI
    // ---------------------------------------------------------------------

    private native void nativeStartRtspServer(int port, String mountPath);
    private native void nativeStopRtspServer();
    private native void nativePushH265Data(byte[] data, int size, long ptsUs,
                                           boolean isKeyframe, boolean isCodecConfig);
}
