/* jconfigint.h for Android ARM64 build (generated manually) */

/* libjpeg-turbo build number */
#define BUILD  "20260404"

/* How to hide global symbols. */
#define HIDDEN  __attribute__((visibility("hidden")))

/* Compiler's inline keyword — leave as-is (clang supports it) */
#undef inline

/* How to obtain function inlining. */
#define INLINE  __attribute__((always_inline)) inline

/* How to obtain thread-local storage */
#define THREAD_LOCAL  __thread

/* Define to the full name of this package. */
#define PACKAGE_NAME  "libjpeg-turbo"

/* Version number of package */
#define VERSION  "3.1.90"

/* The size of `size_t' on 64-bit ARM */
#define SIZEOF_SIZE_T  8

/* GCC/Clang provides __builtin_ctzl; sizeof(unsigned long) == sizeof(size_t) on LP64 */
#define HAVE_BUILTIN_CTZL

/* No <intrin.h> on Android/Linux */
/* #undef HAVE_INTRIN_H */

#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif

/*
 * 8-bit sample values (standard JPEG baseline).
 */
#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif

#undef C_ARITH_CODING_SUPPORTED
#undef D_ARITH_CODING_SUPPORTED
#undef WITH_SIMD

#if BITS_IN_JSAMPLE == 8

/* No arithmetic coding */
/* #define C_ARITH_CODING_SUPPORTED 1 */
/* #define D_ARITH_CODING_SUPPORTED 1 */

/* No SIMD acceleration — pure-C fallback paths used */
/* #define WITH_SIMD 1 */

/* SIMD_ARCHITECTURE = NONE (-1) — no hardware-specific SIMD */
#define SIMD_ARCHITECTURE  -1

#endif

/* #undef WITH_PROFILE */
