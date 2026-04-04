#include <jni.h>
#include <string>
#include <mutex>
#include <vector>
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

#define LOG_TAG "UVC_NATIVE"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;

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

    jobject frameListenerGlobalRef = nullptr;

    uint32_t frameLogCounter = 0;
};

static bool getEnvForCallback(JNIEnv** env, bool* didAttach) {
    *didAttach = false;
    if (!gJvm) return false;

    const jint getEnvRes = gJvm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6);
    if (getEnvRes == JNI_OK) return true;

    if (getEnvRes == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(env, nullptr) != JNI_OK) {
            return false;
        }
        *didAttach = true;
        return true;
    }

    return false;
}

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
        LOGD("frameListenerGlobalRef cleared");
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
        LOGD("uvcDeviceHandle closed");
    }

    if (ctx->uvcDevice) {
        uvc_unref_device(ctx->uvcDevice);
        ctx->uvcDevice = nullptr;
        LOGD("uvcDevice unref");
    }

    if (ctx->uvcContext) {
        uvc_exit(ctx->uvcContext);
        ctx->uvcContext = nullptr;
        ctx->usbContext = nullptr;
        LOGD("uvcContext exited");
    }

    if (ctx->usbContext) {
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
        LOGD("usbContext exited");
    }
}

static void mjpegFrameCallback(uvc_frame_t* frame, void* user_ptr) {
    auto* ctx = reinterpret_cast<NativeContext*>(user_ptr);
    if (!ctx || !frame || !frame->data || frame->data_bytes == 0) return;

    JNIEnv* env = nullptr;
    bool didAttach = false;
    if (!getEnvForCallback(&env, &didAttach)) {
        LOGE("Failed to get JNIEnv in frame callback");
        return;
    }

    jobject listener = nullptr;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        if (!ctx->streamRunning || !ctx->frameListenerGlobalRef) {
            if (didAttach) gJvm->DetachCurrentThread();
            return;
        }
        listener = ctx->frameListenerGlobalRef;
        ctx->frameLogCounter++;
        if ((ctx->frameLogCounter % 30u) == 0u) {
            LOGD(
                    "MJPEG frame callback: format=%u width=%u height=%u bytes=%zu seq=%u",
                    frame->frame_format,
                    frame->width,
                    frame->height,
                    frame->data_bytes,
                    frame->sequence
            );
        }
    }

    jclass listenerClass = env->GetObjectClass(listener);
    if (!listenerClass) {
        if (didAttach) gJvm->DetachCurrentThread();
        return;
    }

    jmethodID onFrameMethod = env->GetMethodID(listenerClass, "onMjpegFrame", "([BII)V");
    if (!onFrameMethod) {
        env->DeleteLocalRef(listenerClass);
        if (didAttach) gJvm->DetachCurrentThread();
        return;
    }

    jbyteArray jpegBytes = env->NewByteArray(static_cast<jsize>(frame->data_bytes));
    if (!jpegBytes) {
        env->DeleteLocalRef(listenerClass);
        if (didAttach) gJvm->DetachCurrentThread();
        return;
    }

    env->SetByteArrayRegion(
            jpegBytes,
            0,
            static_cast<jsize>(frame->data_bytes),
            reinterpret_cast<const jbyte*>(frame->data)
    );

    env->CallVoidMethod(listener, onFrameMethod, jpegBytes, static_cast<jint>(frame->width), static_cast<jint>(frame->height));

    env->DeleteLocalRef(jpegBytes);
    env->DeleteLocalRef(listenerClass);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Exception while delivering MJPEG frame to Java");
    }

    if (didAttach) {
        gJvm->DetachCurrentThread();
    }
}

extern "C"
jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_uvcshoot_NativeBridge_getNativeVersion(JNIEnv* env, jobject /* this */) {
    std::string value = "uvcshoot-native-v10";
    return env->NewStringUTF(value.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeInit(JNIEnv* env, jobject /* this */) {
    auto* ctx = new NativeContext();
    LOGD("nativeInit -> ctx=%p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeRelease(
        JNIEnv* env,
        jobject /* this */,
        jlong handle
) {
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

    LOGD("nativeRelease -> ctx=%p", ctx);
    delete ctx;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetSurface(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jobject surface
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = nullptr;
        LOGD("Previous surface released");
    }

    if (surface == nullptr) {
        LOGD("nativeSetSurface -> null surface");
        return;
    }

    ctx->window = ANativeWindow_fromSurface(env, surface);
    LOGD("nativeSetSurface -> new window=%p", ctx->window);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetFrameListener(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jobject listener
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    clearFrameListenerLocked(env, ctx);

    if (listener) {
        ctx->frameListenerGlobalRef = env->NewGlobalRef(listener);
        LOGD("frameListenerGlobalRef set");
    } else {
        LOGD("frameListenerGlobalRef set to null");
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeSetUsbDeviceInfo(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jint fileDescriptor,
        jint vendorId,
        jint productId,
        jstring busDeviceName
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) {
        LOGE("nativeSetUsbDeviceInfo: ctx is null");
        return JNI_FALSE;
    }

    const char* nameChars = env->GetStringUTFChars(busDeviceName, nullptr);
    if (!nameChars) {
        LOGE("nativeSetUsbDeviceInfo: device name conversion failed");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    ctx->usbFd = static_cast<int>(fileDescriptor);
    ctx->vendorId = static_cast<int>(vendorId);
    ctx->productId = static_cast<int>(productId);
    ctx->deviceName = nameChars;

    env->ReleaseStringUTFChars(busDeviceName, nameChars);

    LOGD(
            "nativeSetUsbDeviceInfo -> fd=%d vendorId=%d productId=%d name=%s",
            ctx->usbFd,
            ctx->vendorId,
            ctx->productId,
            ctx->deviceName.c_str()
    );

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeOpenUsbCamera(
        JNIEnv* env,
        jobject /* this */,
        jlong handle
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) {
        LOGE("nativeOpenUsbCamera: ctx is null");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (ctx->usbFd < 0) {
        LOGE("nativeOpenUsbCamera: invalid usbFd=%d", ctx->usbFd);
        return JNI_FALSE;
    }

    releaseUvcLocked(ctx);
    closeOwnedUsbFdLocked(ctx);

    int duplicatedFd = dup(ctx->usbFd);
    if (duplicatedFd < 0) {
        LOGE(
                "nativeOpenUsbCamera: dup failed for fd=%d errno=%d (%s)",
                ctx->usbFd,
                errno,
                strerror(errno)
        );
        ctx->cameraOpened = false;
        return JNI_FALSE;
    }

    int flags = fcntl(duplicatedFd, F_GETFD);
    if (flags < 0) {
        LOGE(
                "nativeOpenUsbCamera: fcntl(F_GETFD) failed fd=%d errno=%d (%s)",
                duplicatedFd,
                errno,
                strerror(errno)
        );
        close(duplicatedFd);
        ctx->cameraOpened = false;
        return JNI_FALSE;
    }

    ctx->ownedUsbFd = duplicatedFd;
    ctx->cameraOpened = true;

    LOGD(
            "nativeOpenUsbCamera: success originalFd=%d duplicatedFd=%d vendorId=%d productId=%d name=%s",
            ctx->usbFd,
            ctx->ownedUsbFd,
            ctx->vendorId,
            ctx->productId,
            ctx->deviceName.c_str()
    );

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeProbeAndOpenUvc(
        JNIEnv* env,
        jobject /* this */,
        jlong handle
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) {
        LOGE("nativeProbeAndOpenUvc: ctx is null");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (!ctx->cameraOpened || ctx->ownedUsbFd < 0) {
        LOGE("nativeProbeAndOpenUvc: USB camera not ready");
        return JNI_FALSE;
    }

    releaseUvcLocked(ctx);

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    libusb_context* usbCtx = nullptr;
    int res = libusb_init(&usbCtx);
    if (res != 0) {
        LOGE("libusb_init failed: %d", res);
        return JNI_FALSE;
    }

    ctx->usbContext = usbCtx;
    LOGD("libusb_init OK");

    uvc_context_t* uvcCtx = nullptr;
    uvc_error_t uvcRes = uvc_init(&uvcCtx, ctx->usbContext);
    if (uvcRes < 0) {
        LOGE("uvc_init failed: %d", uvcRes);
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
        return JNI_FALSE;
    }

    ctx->uvcContext = uvcCtx;
    LOGD("uvc_init OK");

    ctx->uvcContext->own_usb_ctx = 1;
    LOGD("Forced own_usb_ctx = 1");

    uvc_device_handle_t* devh = nullptr;
    uvcRes = uvc_wrap(ctx->ownedUsbFd, ctx->uvcContext, &devh);
    if (uvcRes < 0 || !devh) {
        LOGE("uvc_wrap failed: %d", uvcRes);
        uvc_exit(ctx->uvcContext);
        ctx->uvcContext = nullptr;
        libusb_exit(ctx->usbContext);
        ctx->usbContext = nullptr;
        return JNI_FALSE;
    }

    ctx->uvcDeviceHandle = devh;
    ctx->uvcDevice = uvc_get_device(devh);

    LOGD("uvc_wrap OK: handle=%p device=%p", ctx->uvcDeviceHandle, ctx->uvcDevice);

    if (ctx->uvcContext->open_devices == ctx->uvcDeviceHandle && ctx->uvcDeviceHandle->next == NULL) {
        uvc_start_handler_thread(ctx->uvcContext);
        LOGD("uvc_start_handler_thread forced");
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeStartMjpegStream(
        JNIEnv* env,
        jobject /* this */,
        jlong handle,
        jint width,
        jint height,
        jint fps
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) {
        LOGE("nativeStartMjpegStream: ctx is null");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    if (!ctx->uvcDeviceHandle) {
        LOGE("nativeStartMjpegStream: uvcDeviceHandle is null");
        return JNI_FALSE;
    }

    stopStreamLocked(ctx);

    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            ctx->uvcDeviceHandle,
            &ctx->streamCtrl,
            UVC_FRAME_FORMAT_MJPEG,
            width,
            height,
            fps
    );

    if (res < 0) {
        LOGE(
                "uvc_get_stream_ctrl_format_size MJPEG failed: %d for %dx%d@%d",
                res,
                width,
                height,
                fps
        );
        return JNI_FALSE;
    }

    LOGD("MJPEG stream ctrl acquired for %dx%d@%d", width, height, fps);

    res = uvc_start_streaming(
            ctx->uvcDeviceHandle,
            &ctx->streamCtrl,
            mjpegFrameCallback,
            ctx,
            0
    );

    if (res < 0) {
        LOGE("uvc_start_streaming MJPEG failed: %d", res);
        return JNI_FALSE;
    }

    ctx->streamRunning = true;
    LOGD("uvc_start_streaming MJPEG OK");

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeStopStream(
        JNIEnv* env,
        jobject /* this */,
        jlong handle
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);
    stopStreamLocked(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_uvcshoot_NativeBridge_nativeCloseUsbCamera(
        JNIEnv* env,
        jobject /* this */,
        jlong handle
) {
    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return;

    std::lock_guard<std::mutex> lock(ctx->mutex);

    releaseUvcLocked(ctx);
    closeOwnedUsbFdLocked(ctx);
    ctx->cameraOpened = false;

    LOGD("nativeCloseUsbCamera");
}