// Host-side RAW dump for the Camera JPEG match calibration (docs/adr/0008). It decodes a RAW with the vendored
// LibRaw and the SAME parameters as the app (core/image/src/main/cpp/reveal_image_jni.cpp, configure()): camera
// RGB, no white balance, no matrix, no curve, 16 bit, half size. It writes a small text header with the colour
// data RawInfoMapper reads, then the packed RGB16 samples (little endian). Never run on files that are not copies.
//
// Build and run: scripts/calibration/rawdump.sh <in.ARW> <out.rdump>
#include <cstdio>
#include <cstdint>
#include <cmath>
#include "libraw/libraw.h"

int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: rawdump in.raw out.rdump\n"); return 2; }
    LibRaw raw;
    auto &p = raw.imgdata.params;
    p.use_camera_wb = 0;
    p.use_auto_wb = 0;
    p.user_mul[0] = p.user_mul[1] = p.user_mul[2] = p.user_mul[3] = 1.0f;
    p.no_auto_bright = 1;
    p.no_auto_scale = 0;
    p.gamm[0] = 1.0;
    p.gamm[1] = 1.0;
    p.output_bps = 16;
    p.output_color = 0;
    p.use_camera_matrix = 1;
    p.highlight = 0;
    p.adjust_maximum_thr = 0.0f;
    p.bright = 1.0f;
    p.half_size = 1;
    p.user_qual = 0;
    p.user_flip = 0;
    p.med_passes = 0;
    p.fbdd_noiserd = 0;
    int rc;
    if ((rc = raw.open_file(argv[1])) != LIBRAW_SUCCESS) { fprintf(stderr, "open: %s\n", libraw_strerror(rc)); return 1; }
    auto &c = raw.imgdata.color;
    // Colour data as snapshotOpen() reads it, right after open (dcraw_process overwrites pre_mul with user_mul).
    double rgbCam[12], preMul[4], camMul[4];
    for (int r = 0; r < 3; r++) for (int k = 0; k < 4; k++) rgbCam[r * 4 + k] = c.rgb_cam[r][k];
    for (int k = 0; k < 4; k++) { preMul[k] = c.pre_mul[k]; camMul[k] = c.cam_mul[k]; }
    if ((rc = raw.unpack()) != LIBRAW_SUCCESS) { fprintf(stderr, "unpack: %s\n", libraw_strerror(rc)); return 1; }
    // Levels as snapshotLevels() reads them after unpack.
    double black = c.black, maximum = c.maximum;
    double cblack[4], linearMax[4];
    for (int i = 0; i < 4; i++) { cblack[i] = c.cblack[i]; linearMax[i] = c.linear_max[i]; }
    if ((rc = raw.dcraw_process()) != LIBRAW_SUCCESS) { fprintf(stderr, "process: %s\n", libraw_strerror(rc)); return 1; }
    int w = 0, h = 0, colors = 0, bps = 0;
    raw.get_mem_image_format(&w, &h, &colors, &bps);
    FILE *f = fopen(argv[2], "wb");
    if (!f) return 1;
    fprintf(f, "RDUMP1\n");
    fprintf(f, "size %d %d\n", w, h);
    fprintf(f, "flip %d\n", raw.imgdata.sizes.flip);
    fprintf(f, "rgb_cam");
    for (int r = 0; r < 3; r++) for (int k = 0; k < 4; k++) fprintf(f, " %.17g", rgbCam[r * 4 + k]);
    fprintf(f, "\npre_mul");
    for (int k = 0; k < 4; k++) fprintf(f, " %.17g", preMul[k]);
    fprintf(f, "\ncam_mul");
    for (int k = 0; k < 4; k++) fprintf(f, " %.17g", camMul[k]);
    fprintf(f, "\nblack %.17g\ncblack %.17g %.17g %.17g %.17g\nmaximum %.17g\nlinear_max %.17g %.17g %.17g %.17g\n",
            black, cblack[0], cblack[1], cblack[2], cblack[3], maximum, linearMax[0], linearMax[1], linearMax[2],
            linearMax[3]);
    fprintf(f, "baseline_exposure %.17g\n",
            (c.dng_levels.parsedfields & LIBRAW_DNGFM_BASELINEEXPOSURE) ? c.dng_levels.baseline_exposure : NAN);
    fprintf(f, "model %s\n", raw.imgdata.idata.model);
    fprintf(f, "data\n");
    auto *img = raw.imgdata.image;
    for (size_t i = 0; i < (size_t)w * h; i++) {
        uint16_t px[3] = {img[i][0], img[i][1], img[i][2]};
        fwrite(px, sizeof(uint16_t), 3, f);
    }
    fclose(f);
    return 0;
}
