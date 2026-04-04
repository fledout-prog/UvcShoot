/*
 * jdprecision_stubs.c
 *
 * Stub implementations of 12-bit (j12*), 16-bit (j16*), and lossless
 * decoder init functions.  These are referenced by jdmaster.c when run-time
 * data precision negotiation is active, but they are NEVER called for
 * standard 8-bit MJPEG streams (such as those produced by UVC cameras).
 *
 * They exist solely to satisfy the linker.  Calling any of them is a
 * programming error and will abort the process.
 */

#define JPEG_INTERNALS
#include "jinclude.h"
#include "jpeglib.h"
/* NOTE: jpegint.h has no include guards; do NOT include it here.
 * The signatures below match declarations in jpegint.h. */

#define PRECISION_STUB_BODY(cinfo) \
    ERREXIT((j_common_ptr)(cinfo), JERR_BAD_PRECISION)

/* 12-bit decoder init stubs */
GLOBAL(void) j12init_d_main_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_d_coef_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_d_post_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_inverse_dct(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_upsampler(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_color_deconverter(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_1pass_quantizer(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_2pass_quantizer(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_merged_upsampler(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_d_diff_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j12init_lossless_decompressor(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

/* 16-bit decoder init stubs */
GLOBAL(void) j16init_d_main_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j16init_d_post_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j16init_upsampler(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j16init_color_deconverter(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j16init_d_diff_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) j16init_lossless_decompressor(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

/* Lossless decoder init stubs (8-bit path; lossless files not compiled) */
GLOBAL(void) jinit_d_diff_controller(j_decompress_ptr cinfo, boolean need_full_buffer)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) jinit_lhuff_decoder(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }

GLOBAL(void) jinit_lossless_decompressor(j_decompress_ptr cinfo)
    { PRECISION_STUB_BODY(cinfo); }
