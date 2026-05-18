// SPDX-License-Identifier: LGPL-2.1-or-later
//
// Native side of the streamer:
//
//   appsrc(name=mysrc, caps: byte-stream/au HEVC)
//     -> h265parse
//     -> queue
//     -> rtph265pay (config-interval=1, pt=96, send VPS/SPS/PPS periodically)
//
// Hosted via gst-rtsp-server at rtsp://<device-ip>:<port><mount>.

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include <gst/gst.h>
#include <gst/app/app.h>
#include <gst/rtsp-server/rtsp-server.h>

#define LOG_TAG "rtsp_server"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// All access to these globals must hold g_lock.
static pthread_mutex_t   g_lock        = PTHREAD_MUTEX_INITIALIZER;
static GstElement       *g_appsrc      = NULL;  // strong ref while alive
static GstRTSPServer    *g_server      = NULL;
static guint             g_server_id   = 0;     // glib source id from attach
static GMainLoop        *g_loop        = NULL;
static GMainContext     *g_loop_ctx    = NULL;  // private context for the loop
static pthread_t         g_loop_thread;
static int               g_loop_thread_started = 0;

// PTS bookkeeping (in GstClockTime nanoseconds).
static GstClockTime      g_first_pts   = GST_CLOCK_TIME_NONE;
static GstClockTime      g_last_pts    = 0;

// -------------------------------------------------------------------------
// media-configure: grab the appsrc out of the pipeline whenever a client
// triggers a new media instance, and wire its caps + live-source settings.
// -------------------------------------------------------------------------
static void on_media_unprepared(GstRTSPMedia *media, gpointer user_data) {
    (void) media; (void) user_data;
    pthread_mutex_lock(&g_lock);
    if (g_appsrc) {
        gst_object_unref(g_appsrc);
        g_appsrc = NULL;
    }
    g_first_pts = GST_CLOCK_TIME_NONE;
    pthread_mutex_unlock(&g_lock);
    LOGI("media unprepared, appsrc released");
}

static void on_media_configure(GstRTSPMediaFactory *factory,
                               GstRTSPMedia *media,
                               gpointer user_data) {
    (void) factory; (void) user_data;

    GstElement *pipeline = gst_rtsp_media_get_element(media);
    GstElement *appsrc   = gst_bin_get_by_name(GST_BIN(pipeline), "mysrc");
    gst_object_unref(pipeline);

    if (!appsrc) {
        LOGE("media-configure: appsrc 'mysrc' not found");
        return;
    }

    GstCaps *caps = gst_caps_from_string(
        "video/x-h265, stream-format=(string)byte-stream, alignment=(string)au");

    g_object_set(appsrc,
                 "stream-type",  0,                  // GST_APP_STREAM_TYPE_STREAM
                 "format",       GST_FORMAT_TIME,
                 "is-live",      TRUE,
                 "do-timestamp", FALSE,              // we provide PTS ourselves
                 "block",        FALSE,
                 "max-bytes",    (guint64) (4 * 1024 * 1024),
                 "caps",         caps,
                 NULL);
    gst_caps_unref(caps);

    g_signal_connect(media, "unprepared",
                     G_CALLBACK(on_media_unprepared), NULL);

    pthread_mutex_lock(&g_lock);
    if (g_appsrc) {
        gst_object_unref(g_appsrc);
    }
    g_appsrc = appsrc; // transfer ref
    g_first_pts = GST_CLOCK_TIME_NONE;
    pthread_mutex_unlock(&g_lock);

    LOGI("media-configure: appsrc wired");
}

// -------------------------------------------------------------------------
// glib main loop thread
// -------------------------------------------------------------------------
static void *loop_thread_main(void *unused) {
    (void) unused;
    g_main_context_push_thread_default(g_loop_ctx);
    g_main_loop_run(g_loop);
    g_main_context_pop_thread_default(g_loop_ctx);
    return NULL;
}

// -------------------------------------------------------------------------
// JNI: nativeStartRtspServer(int port, String mountPath)
// -------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_j2cheng_cam2stream_CameraRtspServer_nativeStartRtspServer(
        JNIEnv *env, jobject thiz, jint port, jstring jmount) {
    (void) thiz;

    pthread_mutex_lock(&g_lock);
    if (g_server) {
        pthread_mutex_unlock(&g_lock);
        LOGI("RTSP server already running");
        return;
    }

    if (!gst_is_initialized()) {
        gst_init(NULL, NULL);
    }

    g_loop_ctx = g_main_context_new();
    g_main_context_push_thread_default(g_loop_ctx);

    g_server = gst_rtsp_server_new();
    {
        char portbuf[16];
        snprintf(portbuf, sizeof(portbuf), "%d", (int) port);
        g_object_set(g_server, "service", portbuf, NULL);
    }

    GstRTSPMountPoints   *mounts  = gst_rtsp_server_get_mount_points(g_server);
    GstRTSPMediaFactory  *factory = gst_rtsp_media_factory_new();

    gst_rtsp_media_factory_set_launch(factory,
        "( appsrc name=mysrc is-live=true do-timestamp=false format=time "
        "  ! h265parse config-interval=1 "
        "  ! queue max-size-buffers=10 max-size-time=0 max-size-bytes=0 leaky=downstream "
        "  ! rtph265pay name=pay0 config-interval=1 pt=96 )");

    // Send-only: don't open client->server RTCP RR sockets we won't read.
    gst_rtsp_media_factory_set_transport_mode(factory,
                                              GST_RTSP_TRANSPORT_MODE_PLAY);
    gst_rtsp_media_factory_set_shared(factory, TRUE);

    g_signal_connect(factory, "media-configure",
                     G_CALLBACK(on_media_configure), NULL);

    const char *mount = (*env)->GetStringUTFChars(env, jmount, NULL);
    gst_rtsp_mount_points_add_factory(mounts, mount, factory);
    (*env)->ReleaseStringUTFChars(env, jmount, mount);
    g_object_unref(mounts);

    g_server_id = gst_rtsp_server_attach(g_server, g_loop_ctx);
    if (g_server_id == 0) {
        LOGE("gst_rtsp_server_attach failed (port %d busy?)", (int) port);
    }

    g_loop = g_main_loop_new(g_loop_ctx, FALSE);
    g_main_context_pop_thread_default(g_loop_ctx);

    if (pthread_create(&g_loop_thread, NULL, loop_thread_main, NULL) == 0) {
        g_loop_thread_started = 1;
        LOGI("RTSP server listening on port %d", (int) port);
    } else {
        LOGE("Failed to spawn RTSP main loop thread");
    }

    pthread_mutex_unlock(&g_lock);
}

// -------------------------------------------------------------------------
// JNI: nativePushH265Data
// -------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_j2cheng_cam2stream_CameraRtspServer_nativePushH265Data(
        JNIEnv *env, jobject thiz,
        jbyteArray data, jint size, jlong pts_us,
        jboolean is_keyframe, jboolean is_codec_config) {
    (void) thiz;

    if (size <= 0 || data == NULL) {
        return;
    }

    pthread_mutex_lock(&g_lock);
    GstElement *appsrc = g_appsrc ? gst_object_ref(g_appsrc) : NULL;
    pthread_mutex_unlock(&g_lock);

    if (!appsrc) {
        return; // no client yet, drop
    }

    jbyte *src = (*env)->GetByteArrayElements(env, data, NULL);
    if (!src) {
        gst_object_unref(appsrc);
        return;
    }

    GstBuffer *buf = gst_buffer_new_memdup(src, (gsize) size);
    (*env)->ReleaseByteArrayElements(env, data, src, JNI_ABORT);

    if (!buf) {
        gst_object_unref(appsrc);
        return;
    }

    GstClockTime pts = (GstClockTime) pts_us * GST_USECOND;

    if (is_codec_config) {
        // SPS/VPS/PPS: header buffer, no useful timestamp.
        GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_HEADER);
        GST_BUFFER_PTS(buf)      = GST_CLOCK_TIME_NONE;
        GST_BUFFER_DTS(buf)      = GST_CLOCK_TIME_NONE;
        GST_BUFFER_DURATION(buf) = GST_CLOCK_TIME_NONE;
    } else {
        pthread_mutex_lock(&g_lock);
        if (!GST_CLOCK_TIME_IS_VALID(g_first_pts)) {
            g_first_pts = pts;
        }
        // Java side already anchored to its own zero, but re-anchor here too
        // in case of restart without Java reset (defense in depth).
        GstClockTime rel = (pts >= g_first_pts) ? (pts - g_first_pts) : 0;
        g_last_pts = rel;
        pthread_mutex_unlock(&g_lock);

        GST_BUFFER_PTS(buf)      = rel;
        GST_BUFFER_DTS(buf)      = rel;
        GST_BUFFER_DURATION(buf) = GST_CLOCK_TIME_NONE;

        if (!is_keyframe) {
            GST_BUFFER_FLAG_SET(buf, GST_BUFFER_FLAG_DELTA_UNIT);
        }
    }

    GstFlowReturn ret = gst_app_src_push_buffer(GST_APP_SRC(appsrc), buf);
    if (ret != GST_FLOW_OK && ret != GST_FLOW_FLUSHING) {
        LOGE("push-buffer returned %d", (int) ret);
    }

    gst_object_unref(appsrc);
}

// -------------------------------------------------------------------------
// JNI: nativeStopRtspServer
// -------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_j2cheng_cam2stream_CameraRtspServer_nativeStopRtspServer(
        JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;

    pthread_mutex_lock(&g_lock);

    if (g_appsrc) {
        gst_app_src_end_of_stream(GST_APP_SRC(g_appsrc));
        gst_object_unref(g_appsrc);
        g_appsrc = NULL;
    }

    if (g_server_id != 0 && g_loop_ctx) {
        GSource *src = g_main_context_find_source_by_id(g_loop_ctx, g_server_id);
        if (src) {
            g_source_destroy(src);
        }
        g_server_id = 0;
    }

    GMainLoop    *loop   = g_loop;     g_loop    = NULL;
    GMainContext *ctx    = g_loop_ctx; g_loop_ctx = NULL;
    GstRTSPServer *srv   = g_server;   g_server  = NULL;
    int joined = g_loop_thread_started; g_loop_thread_started = 0;

    pthread_mutex_unlock(&g_lock);

    if (loop) {
        g_main_loop_quit(loop);
    }
    if (joined) {
        pthread_join(g_loop_thread, NULL);
    }
    if (loop) {
        g_main_loop_unref(loop);
    }
    if (srv) {
        g_object_unref(srv);
    }
    if (ctx) {
        g_main_context_unref(ctx);
    }
    LOGI("RTSP server stopped");
}
