cmake_minimum_required(VERSION 3.22.1)

project("uvcshoot")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# =========================
# libjpeg-turbo
# =========================
set(JPEG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG ON CACHE BOOL "" FORCE)
set(WITH_JAVA OFF CACHE BOOL "" FORCE)
set(WITH_SIMD ON CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build
    EXCLUDE_FROM_ALL
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
find_library(jnigraphics-lib jnigraphics)

target_include_directories(uvcshoot PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/libusb
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo-build
)

target_compile_definitions(uvcshoot PRIVATE
    ANDROID
)

target_link_libraries(uvcshoot
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    libusb
    libuvc
    turbojpeg-static
)
