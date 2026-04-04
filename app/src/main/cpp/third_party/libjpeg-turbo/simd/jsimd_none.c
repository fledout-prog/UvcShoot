/*
 * jsimd_none.c — no-op SIMD stubs for Android builds without SIMD acceleration.
 *
 * All jsimd_set_*() functions return 0, which causes libjpeg-turbo to fall
 * back to its pure-C implementations.  The actual SIMD worker functions are
 * empty; they are never called when the corresponding jsimd_set_*() returns 0.
 */

/* Required before any libjpeg-turbo internal headers to unlock MULTIPLIER etc. */
#define JPEG_INTERNALS

#include <stdio.h>
#include "../src/jinclude.h"
#include "../src/jpeglib.h"
#include "../src/jdct.h"
#include "../src/jchuff.h"
#include "jsimdconst.h"

/* CPU capability query — always report no SIMD */
GLOBAL(unsigned int) jpeg_simd_cpu_support(void) { return JSIMD_NONE; }

/* ---- Color conversion ---- */
GLOBAL(unsigned int) jsimd_set_rgb_ycc(j_compress_ptr cinfo)    { return 0; }
GLOBAL(unsigned int) jsimd_set_rgb_gray(j_compress_ptr cinfo)   { return 0; }
GLOBAL(void) jsimd_color_convert(j_compress_ptr cinfo,
    JSAMPARRAY input_buf, JSAMPIMAGE output_buf,
    JDIMENSION output_row, int num_rows) {}

GLOBAL(unsigned int) jsimd_set_ycc_rgb(j_decompress_ptr cinfo)    { return 0; }
GLOBAL(unsigned int) jsimd_set_ycc_rgb565(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_color_deconvert(j_decompress_ptr cinfo,
    JSAMPIMAGE input_buf, JDIMENSION input_row,
    JSAMPARRAY output_buf, int num_rows) {}

/* ---- Downsampling ---- */
GLOBAL(unsigned int) jsimd_set_h2v1_downsample(j_compress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v1_downsample(j_compress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY output_data) {}

GLOBAL(unsigned int) jsimd_set_h2v2_downsample(j_compress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v2_downsample(j_compress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY output_data) {}

/* ---- Plain upsampling ---- */
GLOBAL(unsigned int) jsimd_set_h2v1_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v1_upsample(j_decompress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY *output_data_ptr) {}

GLOBAL(unsigned int) jsimd_set_h2v2_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v2_upsample(j_decompress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY *output_data_ptr) {}

/* ---- Fancy upsampling ---- */
GLOBAL(unsigned int) jsimd_set_h2v1_fancy_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v1_fancy_upsample(j_decompress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY *output_data_ptr) {}

GLOBAL(unsigned int) jsimd_set_h2v2_fancy_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v2_fancy_upsample(j_decompress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY *output_data_ptr) {}

/* h1v2 is ARM-specific in jsimd.h; define unconditionally so we always have it */
GLOBAL(unsigned int) jsimd_set_h1v2_fancy_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h1v2_fancy_upsample(j_decompress_ptr cinfo,
    jpeg_component_info *compptr,
    JSAMPARRAY input_data, JSAMPARRAY *output_data_ptr) {}

/* ---- Merged upsampling / color conversion ---- */
GLOBAL(unsigned int) jsimd_set_h2v1_merged_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v1_merged_upsample(j_decompress_ptr cinfo,
    JSAMPIMAGE input_buf, JDIMENSION in_row_group_ctr,
    JSAMPARRAY output_buf) {}

GLOBAL(unsigned int) jsimd_set_h2v2_merged_upsample(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_h2v2_merged_upsample(j_decompress_ptr cinfo,
    JSAMPIMAGE input_buf, JDIMENSION in_row_group_ctr,
    JSAMPARRAY output_buf) {}

/* ---- Huffman encoding ---- */
GLOBAL(unsigned int) jsimd_set_huff_encode_one_block(j_compress_ptr cinfo) { return 0; }

/* ---- Progressive Huffman encoding ---- */
GLOBAL(unsigned int) jsimd_set_encode_mcu_AC_first_prepare(j_compress_ptr cinfo,
    void (**method)(const JCOEF *, const int *, int, int, UJCOEF *, size_t *)) { return 0; }

GLOBAL(unsigned int) jsimd_set_encode_mcu_AC_refine_prepare(j_compress_ptr cinfo,
    int (**method)(const JCOEF *, const int *, int, int, UJCOEF *, size_t *)) { return 0; }

/* ---- Sample conversion ---- */
GLOBAL(unsigned int) jsimd_set_convsamp(j_compress_ptr cinfo,
    convsamp_method_ptr *method) { return 0; }

GLOBAL(unsigned int) jsimd_set_convsamp_float(j_compress_ptr cinfo,
    float_convsamp_method_ptr *method) { return 0; }

/* ---- Forward DCT ---- */
GLOBAL(unsigned int) jsimd_set_fdct_islow(j_compress_ptr cinfo,
    forward_DCT_method_ptr *method) { return 0; }

GLOBAL(unsigned int) jsimd_set_fdct_ifast(j_compress_ptr cinfo,
    forward_DCT_method_ptr *method) { return 0; }

GLOBAL(unsigned int) jsimd_set_fdct_float(j_compress_ptr cinfo,
    float_DCT_method_ptr *method) { return 0; }

/* ---- Quantization ---- */
GLOBAL(unsigned int) jsimd_set_quantize(j_compress_ptr cinfo,
    quantize_method_ptr *method) { return 0; }

GLOBAL(unsigned int) jsimd_set_quantize_float(j_compress_ptr cinfo,
    float_quantize_method_ptr *method) { return 0; }

/* ---- Inverse DCT ---- */
GLOBAL(unsigned int) jsimd_set_idct_islow(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_idct_islow(j_decompress_ptr cinfo,
    jpeg_component_info *compptr, JCOEFPTR coef_block,
    JSAMPARRAY output_buf, JDIMENSION output_col) {}

GLOBAL(unsigned int) jsimd_set_idct_ifast(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_idct_ifast(j_decompress_ptr cinfo,
    jpeg_component_info *compptr, JCOEFPTR coef_block,
    JSAMPARRAY output_buf, JDIMENSION output_col) {}

GLOBAL(unsigned int) jsimd_set_idct_float(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_idct_float(j_decompress_ptr cinfo,
    jpeg_component_info *compptr, JCOEFPTR coef_block,
    JSAMPARRAY output_buf, JDIMENSION output_col) {}

GLOBAL(unsigned int) jsimd_set_idct_2x2(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_idct_2x2(j_decompress_ptr cinfo,
    jpeg_component_info *compptr, JCOEFPTR coef_block,
    JSAMPARRAY output_buf, JDIMENSION output_col) {}

GLOBAL(unsigned int) jsimd_set_idct_4x4(j_decompress_ptr cinfo) { return 0; }
GLOBAL(void) jsimd_idct_4x4(j_decompress_ptr cinfo,
    jpeg_component_info *compptr, JCOEFPTR coef_block,
    JSAMPARRAY output_buf, JDIMENSION output_col) {}
