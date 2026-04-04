/* jconfig.h for Android (ARM64, no SIMD, generated manually) */

#define JPEG_LIB_VERSION  62
#define LIBJPEG_TURBO_VERSION  3.1.90
#define LIBJPEG_TURBO_VERSION_NUMBER  3001090

/* Arithmetic coding not compiled in */
/* #define C_ARITH_CODING_SUPPORTED 1 */
/* #define D_ARITH_CODING_SUPPORTED 1 */

/* In-memory source/destination managers */
#define MEM_SRCDST_SUPPORTED  1

/* No SIMD */
/* #define WITH_SIMD 1 */

#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif

/* RIGHT_SHIFT_IS_UNSIGNED not defined (correct for Linux/Android gcc) */
