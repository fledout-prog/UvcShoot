cmake_minimum_required(VERSION 3.22.1)

project("uvcshoot")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# =========================
# libjpeg-turbo (decoder only, pure-C, no SIMD)
# =========================
set(LIBJPEG_TURBO_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo/src)
set(LIBJPEG_TURBO_SIMD ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo/simd)

add_library(libjpeg-turbo STATIC
    # Common utilities
    ${LIBJPEG_TURBO_SRC}/jaricom.c
    ${LIBJPEG_TURBO_SRC}/jcomapi.c
    ${LIBJPEG_TURBO_SRC}/jerror.c
    ${LIBJPEG_TURBO_SRC}/jmemmgr.c
    ${LIBJPEG_TURBO_SRC}/jmemnobs.c
    ${LIBJPEG_TURBO_SRC}/jutils.c
    ${LIBJPEG_TURBO_SRC}/jpeg_nbits.c
    # Decoder
    ${LIBJPEG_TURBO_SRC}/jdapimin.c
    ${LIBJPEG_TURBO_SRC}/jdapistd.c
    ${LIBJPEG_TURBO_SRC}/jdatasrc.c
    ${LIBJPEG_TURBO_SRC}/jdcoefct.c
    ${LIBJPEG_TURBO_SRC}/jdcolor.c
    ${LIBJPEG_TURBO_SRC}/jddctmgr.c
    ${LIBJPEG_TURBO_SRC}/jdhuff.c
    ${LIBJPEG_TURBO_SRC}/jdinput.c
    ${LIBJPEG_TURBO_SRC}/jdmainct.c
    ${LIBJPEG_TURBO_SRC}/jdmarker.c
    ${LIBJPEG_TURBO_SRC}/jdmaster.c
    ${LIBJPEG_TURBO_SRC}/jdmerge.c
    ${LIBJPEG_TURBO_SRC}/jdpostct.c
    ${LIBJPEG_TURBO_SRC}/jdphuff.c
    ${LIBJPEG_TURBO_SRC}/jdsample.c
    ${LIBJPEG_TURBO_SRC}/jdtrans.c
    ${LIBJPEG_TURBO_SRC}/jidctflt.c
    ${LIBJPEG_TURBO_SRC}/jidctfst.c
    ${LIBJPEG_TURBO_SRC}/jidctint.c
    ${LIBJPEG_TURBO_SRC}/jidctred.c
    ${LIBJPEG_TURBO_SRC}/jquant1.c
    ${LIBJPEG_TURBO_SRC}/jquant2.c
    # Lossless decoder (8-bit path) and multi-precision stubs
    ${LIBJPEG_TURBO_SRC}/jdprecision_stubs.c
    # No-op SIMD stubs (forces pure-C fallback paths)
    ${LIBJPEG_TURBO_SIMD}/jsimd_none.c
)

target_include_directories(libjpeg-turbo PUBLIC
    ${LIBJPEG_TURBO_SRC}
    ${LIBJPEG_TURBO_SIMD}
)

target_compile_definitions(libjpeg-turbo PRIVATE
    ANDROID
    NO_GETENV
)

# =========================
# libusb
# =========================
add_library(libusb STATIC
    third_party/libusb/libusb/core.c
    third_party/libusb/libusb/descriptor.c
    third_party/libusb/libusb/hotplug.c
    third_party/libusb/libusb/io.c
    third_party/libusb/libusb/strerror.c
    third_party/libusb/libusb/sync.c
    third_party/libusb/libusb/os/linux_usbfs.c
    third_party/libusb/libusb/os/events_posix.c
    third_party/libusb/libusb/os/threads_posix.c
)

target_include_directories(libusb PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/android
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/libusb
)

target_compile_definitions(libusb PRIVATE
    HAVE_CONFIG_H
    ANDROID
    __ANDROID__
)

# =========================
# libuvc
# =========================
add_library(libuvc STATIC
    third_party/libuvc/src/ctrl.c
    third_party/libuvc/src/device.c
    third_party/libuvc/src/diag.c
    third_party/libuvc/src/frame.c
    third_party/libuvc/src/init.c
    third_party/libuvc/src/misc.c
    third_party/libuvc/src/stream.c
)

target_include_directories(libuvc PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/libusb
)

target_compile_definitions(libuvc PRIVATE
    ANDROID
)

target_link_libraries(libuvc
    libusb
)

# =========================
# main library
# =========================
add_library(uvcshoot SHARED
    native_camera.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(uvcshoot PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/libusb
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo/simd
)

target_compile_definitions(uvcshoot PRIVATE
    ANDROID
)

target_link_libraries(uvcshoot
    ${log-lib}
    ${android-lib}
    libusb
    libuvc
    libjpeg-turbo
)
