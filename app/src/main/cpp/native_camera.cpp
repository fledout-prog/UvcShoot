#include <jni.h>
#include <string>
#include <mutex>
#include <atomic>
#include <vector>
#include <setjmp.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdint>
#include <android/log.h>
#include <android/native_window_jni.h>

#include <libusb.h>

extern "C" {
#include <libuvc/libuvc.h>
#include <libuvc/libuvc_internal.h>
}

#include <stdio.h>
extern "C" {
#include <jpeglib.h>
}

#define LOG_TAG "UVC_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;

/* -----------------------------------------------------------------------
 * Standard JPEG DHT tables for MJPEG frames that omit them.
 * Injected immediately after the SOI marker when no DHT segment is found.
 * ----------------------------------------------------------------------- */
static const uint8_t kStandardDhtSegment[] = {
    /* DC luma (Table 0, Tc=0, Th=0) */
    0xFF, 0xC4, 0x00, 0x1F,
    0x00,
    0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01,
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
    0x08, 0x09, 0x0A, 0x0B,

    /* AC luma (Table 0, Tc=1, Th=0) */
    0xFF, 0xC4, 0x00, 0xB5,
    0x10,
    0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03,
    0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12,
    0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
    0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
    0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
    0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
    0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79,
    0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
    0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
    0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4,
    0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
    0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,

    /* DC chroma (Table 1, Tc=0, Th=1) */
    0xFF, 0xC4, 0x00, 0x1F,
    0x01,
    0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
    0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
    0x08, 0x09, 0x0A, 0x0B,

    /* AC chroma (Table 1, Tc=1, Th=1) */
    0xFF, 0xC4, 0x00, 0xB5,
    0x11,
    0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04,
    0x07, 0x05, 0x04, 0x04, 0x00, 0x01, 0x02, 0x77,
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21,
    0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
    0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34,
    0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
    0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
    0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
    0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96,
    0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4,
    0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2,
    0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9,
    0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
};
static constexpr size_t kStandardDhtSize = sizeof(kStandardDhtSegment);

/* -----------------------------------------------------------------------
 * JPEG marker scan helpers
 * ----------------------------------------------------------------------- */

/** Return true if the JPEG byte stream already contains a DHT (0xC4) segment. */
static bool jpegHasDht(const uint8_t* data, size_t len) {
    if (len < 2) return false;
    size_t i = 2; // skip SOI
    while (i + 3 < len) {
        if (data[i] != 0xFF) break;
        uint8_t marker = data[i + 1];
        if (marker == 0xC4) return true;
        // Standalone markers: SOI(D8), EOI(D9), RST0-7(D0-D7)
        if (marker == 0xD8 || marker == 0xD9 ||
            (marker >= 0xD0 && marker <= 0xD7)) {
            i += 2;
            continue;
        }
        // All other markers carry a 2-byte length field
        if (i + 3 >= len) break;
        uint16_t segLen = (static_cast<uint16_t>(data[i + 2]) << 8) |
                           static_cast<uint16_t>(data[i + 3]);
        if (segLen < 2) break;
        i += 2 + segLen;
    }
    return false;
}

/**
 * Build a normalized JPEG: if DHT tables are missing, inject the standard
 * baseline tables immediately after the SOI marker.
 *
 * Returns a pointer into 'scratch' (which is resized as needed) when
 * injection was performed, or 'src'/'len' when the frame was already complete.
 * The caller must not free the returned pointer.
 */
static const uint8_t* normalizeMjpeg(
        const uint8_t* src, size_t srcLen,
        std::vector<uint8_t>& scratch,
        size_t* outLen)
{
    if (jpegHasDht(src, srcLen)) {
        *outLen = srcLen;
        return src;
    }

    // Inject DHT right after SOI (0xFF 0xD8)
    size_t newLen = srcLen + kStandardDhtSize;
    scratch.resize(newLen);
    scratch[0] = 0xFF;
    scratch[1] = 0xD8; // SOI
    memcpy(scratch.data() + 2, kStandardDhtSegment, kStandardDhtSize);
    memcpy(scratch.data() + 2 + kStandardDhtSize, src + 2, srcLen - 2);

    *outLen = newLen;
    return scratch.data();
}

/* -----------------------------------------------------------------------
 * libjpeg-turbo error handling
 * ----------------------------------------------------------------------- */
struct JpegErrorMgr {
    struct jpeg_error_mgr pub; // must be first
    jmp_buf setjmpBuffer;
};

static void jpegErrorExit(j_common_ptr cinfo) {
    auto* err = reinterpret_cast<JpegErrorMgr*>(cinfo->err);
    // Do NOT call the default output_message — just longjmp
    longjmp(err->setjmpBuffer, 1);
}

/* -----------------------------------------------------------------------
 * NativeContext
 * ----------------------------------------------------------------------- */
struct NativeContext {
    std::mutex mutex;

    ANativeWindow* window = nullptr;

    int usbFd = -1;
    int ownedUsbFd = -1;
    int vendorId = 0;
    int productId = 0;
    std::string deviceName;

    bool cameraOpened = false;
    bool streamRunning = false;

    libusb_context* usbContext = nullptr;
    uvc_context_t* uvcContext = nullptr;
    uvc_device_t* uvcDevice = nullptr;
    uvc_device_handle_t* uvcDeviceHandle = nullptr;
    uvc_stream_ctrl_t streamCtrl{};

    // Per-context scratch buffer for DHT injection (avoids per-frame heap alloc)
    std::vector<uint8_t> dhtScratch;
    // Per-context pixel buffer for decoded RGBX frames (avoids per-frame heap alloc)
    std::vector<uint8_t> pixelBuffer;

    // Frame counter for throttled logging
    uint32_t frameLogCounter = 0;

    // Optional Kotlin frame listener (kept for capture / status events)
    jobject frameListenerGlobalRef = nullptr;
};

/* -----------------------------------------------------------------------
 * Resource management helpers
 * ----------------------------------------------------------------------- */
static void closeOwnedUsbFdLocked(NativeContext* ctx) {
    if (ctx->ownedUsbFd >= 0) {
        close(ctx->ownedUsbFd);
        LOGD("Closed ownedUsbFd=%d", ctx->ownedUsbFd);
        ctx->ownedUsbFd = -1;
    }
}

static void clearFrameListenerLocked(JNIEnv* env, NativeContext* ctx) {
    if (ctx->frameListenerGlobalRef) {
        env->DeleteGlobalRef(ctx->frameListenerGlobalRef);
        ctx->frameListenerGlobalRef = nullptr;
    }
}

static void stopStreamLocked(NativeContext* ctx) {
    if (ctx->uvcDeviceHandle && ctx->streamRunning) {
        uvc_stop_streaming(ctx->uvcDeviceHandle);
        ctx->streamRunning = false;
        LOGD("uvc_stop_streaming done");
    }
}

static void releaseUvcLocked(NativeContext* ctx) {
    stopStreamLocked(ctx);

    if (ctx->uvcDeviceHandle) {
        uvc_close(ctx->uvcDeviceHandle);
        ctx->uvcDeviceHandle = nullptr;
    }
    if (ctx->uvcDevice) {
        uvc_unref_device(ctx->uvcDevice);
        ctx->uvcDevice = nullptr;
    }
    if (ctx->uvcContext) {
        uvc_exit(ctx->uvcContext);
        ctx->uvcContext = nullptr;
        ctx->usbContext = nullptr;
    }
    if (ctx->usbContext) {
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
    }
}

/* -----------------------------------------------------------------------
 * Native MJPEG → ANativeWindow rendering
 * ----------------------------------------------------------------------- */

/**
 * Decode one MJPEG frame with libjpeg-turbo and blit it to the ANativeWindow.
 *
 * Uses a two-stage pipeline:
 *   Stage 1 — Decode JPEG to an intermediate RGBX pixel buffer (no ANativeWindow locked).
 *              libjpeg-turbo errors are handled via setjmp without touching the window.
 *   Stage 2 — Lock the ANativeWindow and copy the pixel buffer into it.
 *              No libjpeg calls in this stage, so it cannot trigger longjmp.
 *
 * Thread-safety: called from the libuvc callback thread only.
 */
static void renderMjpegFrame(NativeContext* ctx,
                             const uint8_t* jpegData,
                             size_t jpegLen)
{
    // --- 1. Obtain the current Surface/window under mutex ---
    ANativeWindow* win = nullptr;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        win = ctx->window;
        if (!ctx->streamRunning) return;
        if (!win) {
            // Surface not yet available; frame is intentionally dropped.
            // Log once every 60 frames to aid diagnosis without spamming.
            if ((ctx->frameLogCounter % 60u) == 0u) {
                LOGD("renderMjpegFrame: no window yet, frame dropped (seq ~%u)",
                     ctx->frameLogCounter);
            }
            return;
        }
        ANativeWindow_acquire(win);
    }

    // --- 2. Normalize: inject DHT tables if missing ---
    size_t normalLen = 0;
    const uint8_t* normalData = normalizeMjpeg(jpegData, jpegLen,
                                               ctx->dhtScratch, &normalLen);

    // --- Stage 1: Decode JPEG to intermediate pixel buffer ---
    struct jpeg_decompress_struct cinfo{};
    JpegErrorMgr jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = jpegErrorExit;

    if (setjmp(jerr.setjmpBuffer)) {
        // libjpeg error during decode — no window lock held, clean up and bail
        jpeg_destroy_decompress(&cinfo);
        ANativeWindow_release(win);
        return;
    }

    jpeg_create_decompress(&cinfo);
    /* jpeg_mem_src takes unsigned char* (not const) per the libjpeg API, but
     * does not modify the buffer.  The const_cast is safe here. */
    jpeg_mem_src(&cinfo,
                 const_cast<unsigned char*>(normalData),
                 static_cast<unsigned long>(normalLen));

    if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
        jpeg_destroy_decompress(&cinfo);
        ANativeWindow_release(win);
        return;
    }

    // Request RGBX output — matches WINDOW_FORMAT_RGBX_8888
    cinfo.out_color_space = JCS_EXT_RGBX;
    jpeg_start_decompress(&cinfo);

    const int imgW = static_cast<int>(cinfo.output_width);
    const int imgH = static_cast<int>(cinfo.output_height);
    const size_t rowBytes = static_cast<size_t>(imgW) * 4; // 4 bytes per RGBX pixel

    // Grow pixel buffer only when needed (reused across frames)
    ctx->pixelBuffer.resize(rowBytes * static_cast<size_t>(imgH));
    uint8_t* pixData = ctx->pixelBuffer.data();

    while (cinfo.output_scanline < cinfo.output_height) {
        uint8_t* row = pixData + cinfo.output_scanline * rowBytes;
        jpeg_read_scanlines(&cinfo, &row, 1);
    }

    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);
    // End of Stage 1 — pixel buffer is fully filled; ANativeWindow still unlocked

    // --- Stage 2: Blit pixel buffer to ANativeWindow ---
    ANativeWindow_setBuffersGeometry(win, imgW, imgH, WINDOW_FORMAT_RGBX_8888);

    ANativeWindow_Buffer buf{};
    if (ANativeWindow_lock(win, &buf, nullptr) == 0) {
        auto* dst = reinterpret_cast<uint8_t*>(buf.bits);
        const size_t dstStride = static_cast<size_t>(buf.stride) * 4;

        if (dstStride == rowBytes) {
            // Strides match — single bulk copy
            memcpy(dst, pixData, rowBytes * static_cast<size_t>(imgH));
        } else {
            // Strides differ — copy row by row
            const uint8_t* src = pixData;
            for (int y = 0; y < imgH; ++y, dst += dstStride, src += rowBytes) {
                memcpy(dst, src, rowBytes);
            }
        }
        ANativeWindow_unlockAndPost(win);
    }

    ANativeWindow_release(win);
}

/* -----------------------------------------------------------------------
 * libuvc MJPEG frame callback
 * ----------------------------------------------------------------------- */
static void mjpegFrameCallback(uvc_frame_t* frame, void* user_ptr) {
    auto* ctx = reinterpret_cast<NativeContext*>(user_ptr);
    if (!ctx || !frame || !frame->data || frame->data_bytes == 0) return;

    // Throttled logging (every 30 frames)
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (!ctx->streamRunning) return;
        ctx->frameLogCounter++;
        if ((ctx->frameLogCounter % 30u) == 0u) {
            LOGD("MJPEG cb: fmt=%u %ux%u bytes=%zu seq=%u",
                 frame->frame_format, frame->width, frame->height,
                 frame->data_bytes, frame->sequence);
        }
    }

    // Render to Surface (no JNI round-trip for preview frames)
    renderMjpegFrame(ctx,
                     reinterpret_cast<const uint8_t*>(frame->data),
                     frame->data_bytes);
}

/* -----------------------------------------------------------------------
 * JNI_OnLoad
 * ----------------------------------------------------------------------- */
extern "C"
jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

/* -----------------------------------------------------------------------
 * JNI exports
 * ----------------------------------------------------------------------- */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_uvcshoot_NativeBridge_getNativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("uvcshoot-native-v20-turbo");
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeInit(JNIEnv*, jobject) {
    auto* ctx = new NativeContext();
    LOGD("nativeInit ctx=%p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeRelease(JNIEnv* env, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        clearFrameListenerLocked(env, ctx);
        releaseUvcLocked(ctx);
        closeOwnedUsbFdLocked(ctx);
        if (ctx->window) {
            ANativeWindow_release(ctx->window);
            ctx->window = nullptr;
        }
        ctx->cameraOpened = false;
    }

    LOGD("nativeRelease ctx=%p", ctx);
    delete ctx;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetSurface(
        JNIEnv* env, jobject, jlong handle, jobject surface)
{
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = nullptr;
        LOGD("nativeSetSurface: previous window released");
    }

    if (!surface) {
        LOGD("nativeSetSurface: null surface");
        return;
    }

    ctx->window = ANativeWindow_fromSurface(env, surface);
    LOGD("nativeSetSurface: new window=%p streamRunning=%d", ctx->window, ctx->streamRunning ? 1 : 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetFrameListener(
        JNIEnv* env, jobject, jlong handle, jobject listener)
{
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);
    clearFrameListenerLocked(env, ctx);

    if (listener) {
        ctx->frameListenerGlobalRef = env->NewGlobalRef(listener);
        LOGD("frameListenerGlobalRef set");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetUsbDeviceInfo(
        JNIEnv* env, jobject, jlong handle,
        jint fileDescriptor, jint vendorId, jint productId, jstring busDeviceName)
{
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return JNI_FALSE;

    const char* nameChars = env->GetStringUTFChars(busDeviceName, nullptr);
    if (!nameChars) return JNI_FALSE;

    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ctx->usbFd = static_cast<int>(fileDescriptor);
        ctx->vendorId = static_cast<int>(vendorId);
        ctx->productId = static_cast<int>(productId);
        ctx->deviceName = nameChars;
    }

    env->ReleaseStringUTFChars(busDeviceName, nameChars);
    LOGD("nativeSetUsbDeviceInfo fd=%d vid=%d pid=%d", fileDescriptor, vendorId, productId);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeOpenUsbCamera(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (ctx->usbFd < 0) {
        LOGE("nativeOpenUsbCamera: invalid usbFd=%d", ctx->usbFd);
        return JNI_FALSE;
    }

    releaseUvcLocked(ctx);
    closeOwnedUsbFdLocked(ctx);

    int dupFd = dup(ctx->usbFd);
    if (dupFd < 0) {
        LOGE("dup failed: errno=%d (%s)", errno, strerror(errno));
        ctx->cameraOpened = false;
        return JNI_FALSE;
    }

    int flags = fcntl(dupFd, F_GETFD);
    if (flags < 0) {
        LOGE("fcntl F_GETFD failed: errno=%d", errno);
        close(dupFd);
        ctx->cameraOpened = false;
        return JNI_FALSE;
    }

    ctx->ownedUsbFd = dupFd;
    ctx->cameraOpened = true;
    LOGD("nativeOpenUsbCamera: originalFd=%d dupFd=%d", ctx->usbFd, dupFd);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeProbeAndOpenUvc(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (!ctx->cameraOpened || ctx->ownedUsbFd < 0) {
        LOGE("nativeProbeAndOpenUvc: USB camera not ready");
        return JNI_FALSE;
    }

    releaseUvcLocked(ctx);

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    libusb_context* usbCtx = nullptr;
    if (libusb_init(&usbCtx) != 0) {
        LOGE("libusb_init failed");
        return JNI_FALSE;
    }
    ctx->usbContext = usbCtx;

    uvc_context_t* uvcCtx = nullptr;
    if (uvc_init(&uvcCtx, ctx->usbContext) < 0) {
        LOGE("uvc_init failed");
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
        return JNI_FALSE;
    }
    ctx->uvcContext = uvcCtx;
    ctx->uvcContext->own_usb_ctx = 1;

    uvc_device_handle_t* devh = nullptr;
    if (uvc_wrap(ctx->ownedUsbFd, ctx->uvcContext, &devh) < 0 || !devh) {
        LOGE("uvc_wrap failed");
        uvc_exit(ctx->uvcContext);
        ctx->uvcContext = nullptr;
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
        return JNI_FALSE;
    }

    ctx->uvcDeviceHandle = devh;
    ctx->uvcDevice = uvc_get_device(devh);
    LOGD("uvc_wrap OK: handle=%p", devh);

    if (ctx->uvcContext->open_devices == ctx->uvcDeviceHandle &&
        ctx->uvcDeviceHandle->next == nullptr) {
        uvc_start_handler_thread(ctx->uvcContext);
        LOGD("uvc_start_handler_thread forced");
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeStartMjpegStream(
        JNIEnv*, jobject, jlong handle, jint width, jint height, jint fps)
{
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (!ctx->uvcDeviceHandle) {
        LOGE("nativeStartMjpegStream: uvcDeviceHandle is null");
        return JNI_FALSE;
    }

    stopStreamLocked(ctx);

    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            ctx->uvcDeviceHandle, &ctx->streamCtrl,
            UVC_FRAME_FORMAT_MJPEG, width, height, fps);
    if (res < 0) {
        LOGE("uvc_get_stream_ctrl_format_size failed: %d (%dx%d@%d)", res, width, height, fps);
        return JNI_FALSE;
    }

    res = uvc_start_streaming(ctx->uvcDeviceHandle, &ctx->streamCtrl,
                              mjpegFrameCallback, ctx, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %d", res);
        return JNI_FALSE;
    }

    ctx->streamRunning = true;
    LOGD("Streaming MJPEG %dx%d@%d fps", width, height, fps);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeStopStream(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    stopStreamLocked(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeCloseUsbCamera(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    releaseUvcLocked(ctx);
    closeOwnedUsbFdLocked(ctx);
    ctx->cameraOpened = false;
    LOGD("nativeCloseUsbCamera");
}
