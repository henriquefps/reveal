// JNI layer for dev.hfps.image.raw.RawNative: decode a camera RAW file with LibRaw to LINEAR 16-bit
// camera RGB (no white balance, no colour matrix, no gamma, no auto-brightness) and report every piece
// of colour metadata the develop pipeline needs. See docs/adr/0004-image-core.md.
//
// Input: a file descriptor (a Storage Access Framework document; read with pread, never copied, never
// closed here). Output: a Session handle that owns the processed image until released.
//
// Memory: LibRaw holds the raw mosaic (2 bytes/px) and, after dcraw_process, a 4 x uint16 image
// (8 bytes/px at the output size). The interleaved 3 x uint16 result is packed IN PLACE over that image
// buffer (6 bytes/px never overtake 8), so packing needs no extra buffer.

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <vector>

#include "libraw/libraw.h"

#define LOG_TAG "RevealImage"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int ERR_IO = -1002;
constexpr int ERR_NO_IMAGE = -1003;

// Index layout of the numbers array handed to Kotlin. Mirrored by RawNativeLayout in RawNative.kt.
enum Field {
    F_RAW_WIDTH = 0, F_RAW_HEIGHT, F_LEFT_MARGIN, F_TOP_MARGIN, F_WIDTH, F_HEIGHT,
    F_FLIP, F_COLORS, F_FILTERS, F_CROP_LEFT, F_CROP_TOP, F_CROP_WIDTH, F_CROP_HEIGHT,
    F_BLACK, F_CBLACK0, F_CBLACK1, F_CBLACK2, F_CBLACK3, F_CBLACK_PATTERN_W, F_CBLACK_PATTERN_H,
    F_MAXIMUM, F_DATA_MAXIMUM, F_LINEAR_MAX0, F_LINEAR_MAX1, F_LINEAR_MAX2, F_LINEAR_MAX3,
    F_CAM_MUL0, F_CAM_MUL1, F_CAM_MUL2, F_CAM_MUL3,
    F_PRE_MUL0, F_PRE_MUL1, F_PRE_MUL2, F_PRE_MUL3,
    F_RGB_CAM = 34,       // 3 x 4 floats, row-major (34..45)
    F_CAM_XYZ = 46,       // 4 x 3 floats, row-major (46..57)
    F_BASELINE_EXPOSURE = 58, F_DNG_VERSION, F_RAW_BPS, F_LEVELS_FINAL, F_OUT_WIDTH, F_OUT_HEIGHT,
    F_DNG_WHITE, F_ISO, F_PROCESSED_BLACK_PATTERN,
    F_SONY_FILE_FORMAT, F_SONY_RAW_TYPE,
    F_COUNT
};

// Strings handed to Kotlin, in this order.
enum Str { S_MAKE = 0, S_MODEL, S_NORMALIZED_MAKE, S_NORMALIZED_MODEL, S_SOFTWARE, S_LIBRAW, S_COUNT };

// Reads a file by descriptor with positional reads (the descriptor's own offset is never touched, so
// the SAF provider's descriptor can be shared) through a small block cache: LibRaw's decoders read
// byte by byte in places.
class FdStream : public LibRaw_abstract_datastream {
public:
    FdStream(int fd, int64_t size) : fd_(fd), size_(size) { block_.resize(kBlock); }

    int valid() override { return fd_ >= 0 ? 1 : 0; }

    int read(void *ptr, size_t size, size_t nmemb) override {
        if (size == 0 || nmemb == 0) return 0;
        size_t want = size * nmemb;
        size_t got = readBytes(static_cast<uint8_t *>(ptr), want);
        return static_cast<int>(got / size);
    }

    int seek(INT64 offset, int whence) override {
        int64_t target;
        switch (whence) {
            case SEEK_SET: target = offset; break;
            case SEEK_CUR: target = pos_ + offset; break;
            case SEEK_END: target = size_ + offset; break;
            default: return -1;
        }
        if (target < 0) return -1;
        pos_ = target;
        return 0;
    }

    INT64 tell() override { return pos_; }
    INT64 size() override { return size_; }

    int get_char() override {
        uint8_t c;
        return readBytes(&c, 1) == 1 ? c : -1;
    }

    char *gets(char *s, int n) override {
        if (n <= 0) return nullptr;
        int i = 0;
        while (i < n - 1) {
            int c = get_char();
            if (c < 0) break;
            s[i++] = static_cast<char>(c);
            if (c == '\n') break;
        }
        if (i == 0) return nullptr;
        s[i] = 0;
        return s;
    }

    int scanf_one(const char *fmt, void *out) override {
        char buf[64];
        int64_t save = pos_;
        size_t n = readBytes(reinterpret_cast<uint8_t *>(buf), sizeof(buf) - 1);
        buf[n] = 0;
        pos_ = save;
        return sscanf(buf, fmt, out);
    }

    int eof() override { return pos_ >= size_ ? 1 : 0; }

    bool failed() const { return failed_; }

private:
    static constexpr size_t kBlock = 256 * 1024;

    // Fills block_ with the aligned block containing `offset`.
    bool fill(int64_t offset) {
        int64_t start = offset - (offset % static_cast<int64_t>(kBlock));
        size_t total = 0;
        while (total < kBlock) {
            ssize_t n = pread(fd_, block_.data() + total, kBlock - total, start + total);
            if (n < 0) {
                if (errno == EINTR) continue;
                failed_ = true;
                return false;
            }
            if (n == 0) break;
            total += static_cast<size_t>(n);
        }
        blockStart_ = start;
        blockLen_ = total;
        return true;
    }

    size_t readBytes(uint8_t *dst, size_t want) {
        if (pos_ >= size_) return 0;
        want = static_cast<size_t>(std::min<int64_t>(static_cast<int64_t>(want), size_ - pos_));
        size_t done = 0;
        while (done < want) {
            size_t left = want - done;
            if (left >= kBlock) {  // big reads (raw strips) bypass the cache
                ssize_t n = pread(fd_, dst + done, left, pos_);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) { if (n < 0) failed_ = true; break; }
                done += static_cast<size_t>(n);
                pos_ += n;
                continue;
            }
            if (blockStart_ < 0 || pos_ < blockStart_ || pos_ >= blockStart_ + static_cast<int64_t>(blockLen_)) {
                if (!fill(pos_)) break;
                if (pos_ >= blockStart_ + static_cast<int64_t>(blockLen_)) break;
            }
            size_t within = static_cast<size_t>(pos_ - blockStart_);
            size_t n = std::min(left, blockLen_ - within);
            memcpy(dst + done, block_.data() + within, n);
            done += n;
            pos_ += static_cast<int64_t>(n);
        }
        return done;
    }

    int fd_;
    int64_t size_;
    int64_t pos_ = 0;
    std::vector<uint8_t> block_;
    int64_t blockStart_ = -1;
    size_t blockLen_ = 0;
    bool failed_ = false;
};

struct Callback {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

int progressCallback(void *data, enum LibRaw_progress stage, int iteration, int expected) {
    auto *cb = static_cast<Callback *>(data);
    if (!cb || !cb->listener) return 0;
    jboolean keepGoing = cb->env->CallBooleanMethod(cb->listener, cb->method, (jint)stage, (jint)iteration, (jint)expected);
    if (cb->env->ExceptionCheck()) return 1;
    return keepGoing ? 0 : 1;  // non-zero cancels
}

Callback makeCallback(JNIEnv *env, jobject listener) {
    Callback cb{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.method = env->GetMethodID(cls, "onProgress", "(III)Z");
        if (!cb.method) { env->ExceptionClear(); cb.listener = nullptr; }
    }
    return cb;
}

void throwRaw(JNIEnv *env, int code, const char *what) {
    if (env->ExceptionCheck()) return;  // keep the listener's own exception
    jclass cls = env->FindClass("dev/hfps/image/raw/LibRawException");
    if (!cls) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    char message[256];
    snprintf(message, sizeof(message), "%s: %s (%d)", what, code <= -1000 ? "reveal" : libraw_strerror(code), code);
    jstring jmessage = env->NewStringUTF(message);
    auto ex = static_cast<jthrowable>(env->NewObject(cls, ctor, (jint)code, jmessage));
    env->Throw(ex);
}

struct Session {
    LibRaw raw;
    FdStream *stream = nullptr;
    std::vector<double> nums = std::vector<double>(F_COUNT, 0.0);
    std::vector<std::string> strs = std::vector<std::string>(S_COUNT);
    int width = 0;
    int height = 0;
    bool packed = false;

    ~Session() {
        raw.recycle();
        delete stream;
    }

    // What the file says about itself, readable right after open_datastream (before unpack).
    void snapshotOpen() {
        auto &d = raw.imgdata;
        nums[F_RAW_WIDTH] = d.sizes.raw_width;
        nums[F_RAW_HEIGHT] = d.sizes.raw_height;
        nums[F_LEFT_MARGIN] = d.sizes.left_margin;
        nums[F_TOP_MARGIN] = d.sizes.top_margin;
        nums[F_WIDTH] = d.sizes.width;
        nums[F_HEIGHT] = d.sizes.height;
        nums[F_FLIP] = d.sizes.flip;
        nums[F_COLORS] = d.idata.colors;
        nums[F_FILTERS] = static_cast<double>(d.idata.filters);
        const auto &crop = d.sizes.raw_inset_crops[0];
        bool hasCrop = crop.cwidth > 0 && crop.cheight > 0 && crop.cleft != 0xffff && crop.ctop != 0xffff;
        nums[F_CROP_LEFT] = hasCrop ? crop.cleft : -1;
        nums[F_CROP_TOP] = hasCrop ? crop.ctop : -1;
        nums[F_CROP_WIDTH] = hasCrop ? crop.cwidth : -1;
        nums[F_CROP_HEIGHT] = hasCrop ? crop.cheight : -1;
        for (int i = 0; i < 4; i++) {
            nums[F_CAM_MUL0 + i] = d.color.cam_mul[i];
            nums[F_PRE_MUL0 + i] = d.color.pre_mul[i];
        }
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 4; c++) nums[F_RGB_CAM + r * 4 + c] = d.color.rgb_cam[r][c];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 3; c++) nums[F_CAM_XYZ + r * 3 + c] = d.color.cam_xyz[r][c];
        const auto &dng = d.color.dng_levels;
        nums[F_BASELINE_EXPOSURE] = (dng.parsedfields & LIBRAW_DNGFM_BASELINEEXPOSURE) ? dng.baseline_exposure : NAN;
        nums[F_DNG_VERSION] = d.idata.dng_version;
        nums[F_RAW_BPS] = d.color.raw_bps;
        nums[F_DNG_WHITE] = (dng.parsedfields & LIBRAW_DNGFM_WHITE) ? dng.dng_whitelevel[0] : -1;
        nums[F_ISO] = d.other.iso_speed;
        nums[F_SONY_FILE_FORMAT] = d.makernotes.sony.FileFormat;
        nums[F_SONY_RAW_TYPE] = d.makernotes.sony.SonyRawFileType;
        strs[S_MAKE] = d.idata.make;
        strs[S_MODEL] = d.idata.model;
        strs[S_NORMALIZED_MAKE] = d.idata.normalized_make;
        strs[S_NORMALIZED_MODEL] = d.idata.normalized_model;
        strs[S_SOFTWARE] = d.idata.software;
        strs[S_LIBRAW] = LibRaw::version();
        snapshotLevels();
    }

    // Black and white levels; final once unpack() has run (some formats set them while unpacking).
    void snapshotLevels() {
        auto &c = raw.imgdata.color;
        nums[F_BLACK] = c.black;
        for (int i = 0; i < 4; i++) nums[F_CBLACK0 + i] = c.cblack[i];
        nums[F_CBLACK_PATTERN_W] = c.cblack[4];
        nums[F_CBLACK_PATTERN_H] = c.cblack[5];
        nums[F_MAXIMUM] = c.maximum;
        nums[F_DATA_MAXIMUM] = c.data_maximum;
        for (int i = 0; i < 4; i++) nums[F_LINEAR_MAX0 + i] = c.linear_max[i];
    }
};

void configure(LibRaw &raw, bool halfSize, int demosaic) {
    auto &p = raw.imgdata.params;
    // Camera space, no look: no WB (user_mul all 1 overrides every WB source), no auto brightness, no
    // gamma, 16 bit, no colour conversion (output_color 0 = raw camera RGB), no highlight clipping to
    // the WB-scaled white, no maximum adjustment (the stored white level is the scale).
    p.use_camera_wb = 0;
    p.use_auto_wb = 0;
    p.user_mul[0] = p.user_mul[1] = p.user_mul[2] = p.user_mul[3] = 1.0f;
    p.no_auto_bright = 1;
    p.no_auto_scale = 0;
    p.gamm[0] = 1.0;
    p.gamm[1] = 1.0;
    p.output_bps = 16;
    p.output_color = 0;
    p.use_camera_matrix = 1;  // matrices are only reported, but identify() derives rgb_cam/pre_mul with it
    p.highlight = 0;
    p.adjust_maximum_thr = 0.0f;
    p.bright = 1.0f;
    p.half_size = halfSize ? 1 : 0;
    p.user_qual = demosaic;  // 0 linear, 1 VNG, 2 PPG, 3 AHD, 4 DCB, 11 DHT, 12 AAHD
    p.user_flip = 0;         // orientation is reported, never applied
    p.med_passes = 0;
    p.fbdd_noiserd = 0;
}

jobject makeInfo(JNIEnv *env, Session *s) {
    jclass cls = env->FindClass("dev/hfps/image/raw/RawNativeInfo");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([D[Ljava/lang/String;)V");
    jdoubleArray nums = env->NewDoubleArray(static_cast<jsize>(s->nums.size()));
    env->SetDoubleArrayRegion(nums, 0, static_cast<jsize>(s->nums.size()), s->nums.data());
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray strs = env->NewObjectArray(static_cast<jsize>(s->strs.size()), strCls, nullptr);
    for (size_t i = 0; i < s->strs.size(); i++) {
        jstring js = env->NewStringUTF(s->strs[i].c_str());
        env->SetObjectArrayElement(strs, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return env->NewObject(cls, ctor, nums, strs);
}

// Opens the descriptor into a new Session. Returns null with a pending exception on failure.
Session *openSession(JNIEnv *env, jint fd, bool halfSize, jint demosaic, Callback *cb) {
    auto *s = new Session();
    configure(s->raw, halfSize, demosaic);
    s->raw.set_progress_handler(cb ? progressCallback : nullptr, cb);
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) { throwRaw(env, ERR_IO, "descriptor size"); delete s; return nullptr; }
    s->stream = new FdStream(fd, size);
    int rc = s->raw.open_datastream(s->stream);
    if (rc != LIBRAW_SUCCESS) { throwRaw(env, rc, "open"); delete s; return nullptr; }
    s->snapshotOpen();
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_hfps_image_raw_RawNative_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(LibRaw::version());
}

// Identify only (no unpack): header metadata and colour data from a descriptor.
JNIEXPORT jobject JNICALL
Java_dev_hfps_image_raw_RawNative_nativeReadInfo(JNIEnv *env, jclass, jint fd) {
    Session *s = openSession(env, fd, false, 3, nullptr);
    if (!s) return nullptr;
    jobject info = makeInfo(env, s);
    delete s;
    return info;
}

// Full decode from a descriptor. Returns a Session pointer (0 with a pending LibRawException on failure).
JNIEXPORT jlong JNICALL
Java_dev_hfps_image_raw_RawNative_nativeDecode(JNIEnv *env, jclass, jint fd, jboolean halfSize, jint demosaic,
                                               jobject listener) {
    Callback cb = makeCallback(env, listener);
    Session *s = openSession(env, fd, halfSize, demosaic, listener ? &cb : nullptr);
    if (!s) return 0;
    int rc;
    if ((rc = s->raw.unpack()) != LIBRAW_SUCCESS) { throwRaw(env, rc, "unpack"); delete s; return 0; }
    s->snapshotLevels();
    s->nums[F_LEVELS_FINAL] = 1;
    if ((rc = s->raw.dcraw_process()) != LIBRAW_SUCCESS) { throwRaw(env, rc, "dcraw_process"); delete s; return 0; }
    if (env->ExceptionCheck()) { delete s; return 0; }
    auto &d = s->raw.imgdata;
    s->nums[F_DATA_MAXIMUM] = d.color.data_maximum;  // known once the data has been read
    int width = 0, height = 0, colors = 0, bps = 0;
    s->raw.get_mem_image_format(&width, &height, &colors, &bps);
    if (width <= 0 || height <= 0 || !d.image) { throwRaw(env, ERR_NO_IMAGE, "empty image"); delete s; return 0; }
    s->width = width;
    s->height = height;
    s->nums[F_OUT_WIDTH] = width;
    s->nums[F_OUT_HEIGHT] = height;
    // Pack 4 x uint16 into 3 x uint16 in place (forward; each pixel is read before it is written).
    auto *src = d.image;
    auto *dst = reinterpret_cast<uint16_t *>(d.image);
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    const bool mono = d.idata.colors == 1;
    for (size_t i = 0; i < count; i++) {
        const uint16_t r = src[i][0];
        const uint16_t g = mono ? r : src[i][1];
        const uint16_t b = mono ? r : src[i][2];
        dst[3 * i] = r;
        dst[3 * i + 1] = g;
        dst[3 * i + 2] = b;
    }
    s->packed = true;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jobject JNICALL
Java_dev_hfps_image_raw_RawNative_nativeSessionInfo(JNIEnv *env, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return s ? makeInfo(env, s) : nullptr;
}

JNIEXPORT jintArray JNICALL
Java_dev_hfps_image_raw_RawNative_nativeSessionSize(JNIEnv *env, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return nullptr;
    jintArray out = env->NewIntArray(2);
    jint dims[2] = {s->width, s->height};
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

// Copies the packed RGB16 (width * height * 3 values) into a heap array.
JNIEXPORT void JNICALL
Java_dev_hfps_image_raw_RawNative_nativeCopyToArray(JNIEnv *env, jclass, jlong handle, jshortArray out) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s || !s->packed) { throwRaw(env, ERR_NO_IMAGE, "no image"); return; }
    const jsize values = static_cast<jsize>(static_cast<size_t>(s->width) * s->height * 3);
    if (env->GetArrayLength(out) < values) { throwRaw(env, ERR_NO_IMAGE, "array too small"); return; }
    env->SetShortArrayRegion(out, 0, values, reinterpret_cast<const jshort *>(s->raw.imgdata.image));
}

// A zero-copy direct ByteBuffer over the packed RGB16 (width * height * 3 values, native byte order). It is
// valid until nativeRelease: native memory, not counted against the Java heap (on Android a Java-allocated
// direct buffer is a heap array and is).
JNIEXPORT jobject JNICALL
Java_dev_hfps_image_raw_RawNative_nativeSessionBuffer(JNIEnv *env, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s || !s->packed) { throwRaw(env, ERR_NO_IMAGE, "no image"); return nullptr; }
    const size_t bytes = static_cast<size_t>(s->width) * s->height * 3 * sizeof(uint16_t);
    return env->NewDirectByteBuffer(s->raw.imgdata.image, static_cast<jlong>(bytes));
}

JNIEXPORT void JNICALL
Java_dev_hfps_image_raw_RawNative_nativeRelease(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}

}  // extern "C"
