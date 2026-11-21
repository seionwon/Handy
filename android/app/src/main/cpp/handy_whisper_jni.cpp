#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

#define HANDY_JNI_TAG "HandyWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, HANDY_JNI_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HANDY_JNI_TAG, __VA_ARGS__)

namespace {

std::string jstring_to_utf8(JNIEnv *env, jstring input) {
    if (input == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(input, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(input, chars);
    return out;
}

jbyteArray utf8_to_jbytes(JNIEnv *env, const std::string &text) {
    const jsize len = static_cast<jsize>(text.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr == nullptr) {
        return nullptr;
    }
    if (len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(text.data()));
    }
    return arr;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pais_handy_stt_WhisperNative_nativeInitContext(
    JNIEnv *env,
    jobject /* thiz */,
    jstring model_path_str
) {
    const std::string model_path = jstring_to_utf8(env, model_path_str);
    if (model_path.empty()) {
        LOGE("Empty model path passed to nativeInitContext");
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model_path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context from %s", model_path.c_str());
        return 0;
    }
    LOGI("Initialized whisper context successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pais_handy_stt_WhisperNative_nativeFreeContext(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong context_ptr
) {
    if (context_ptr == 0) {
        return;
    }
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    whisper_free(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pais_handy_stt_WhisperNative_nativeTranscribeUtf8(
    JNIEnv *env,
    jobject /* thiz */,
    jlong context_ptr,
    jint num_threads,
    jstring language_str,
    jboolean translate_to_english,
    jstring initial_prompt_str,
    jfloatArray audio_data
) {
    if (context_ptr == 0 || audio_data == nullptr) {
        return utf8_to_jbytes(env, "");
    }
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    const jsize length = env->GetArrayLength(audio_data);
    if (length <= 0) {
        return utf8_to_jbytes(env, "");
    }

    std::vector<float> samples(static_cast<size_t>(length));
    env->GetFloatArrayRegion(audio_data, 0, length, samples.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    const std::string language = jstring_to_utf8(env, language_str);
    const std::string initial_prompt = jstring_to_utf8(env, initial_prompt_str);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(static_cast<int>(num_threads), 8));
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = (translate_to_english == JNI_TRUE);
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    if (language.empty() || language == "auto") {
        params.language = "auto";
        params.detect_language = false;
    } else {
        params.language = language.c_str();
    }

    if (!initial_prompt.empty()) {
        params.initial_prompt = initial_prompt.c_str();
    }

    whisper_reset_timings(ctx);
    const int rc = whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size()));
    if (rc != 0) {
        LOGE("whisper_full failed with error code %d", rc);
        return nullptr;
    }

    std::string combined;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            combined.append(seg);
        }
    }
    return utf8_to_jbytes(env, combined);
}

JNIEXPORT jbyteArray JNICALL
Java_com_pais_handy_stt_WhisperNative_nativeSystemInfoUtf8(
    JNIEnv *env,
    jobject /* thiz */
) {
    const char *info = whisper_print_system_info();
    return utf8_to_jbytes(env, info ? std::string(info) : std::string());
}

} // extern "C"
