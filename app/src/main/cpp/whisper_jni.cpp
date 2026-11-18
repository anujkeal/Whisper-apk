#include <jni.h>
#include <string>
#include <algorithm>
#include <thread>
#include <cstdio>
#include "whisper.h"

static std::string jstringToString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::string stamp(int64_t centiseconds) {
    int64_t seconds = std::max<int64_t>(0, centiseconds / 100);
    int h = static_cast<int>(seconds / 3600);
    int m = static_cast<int>((seconds % 3600) / 60);
    int s = static_cast<int>(seconds % 60);
    char out[16];
    std::snprintf(out, sizeof(out), "%02d:%02d:%02d", h, m, s);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_keal_whisper_WhisperNative_initModel(JNIEnv *env, jobject, jstring path) {
    std::string modelPath = jstringToString(env, path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(modelPath.c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_keal_whisper_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle,
                                                jfloatArray audio, jstring language, jint wordMode) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx || !audio) return env->NewStringUTF("Whisper model or audio is unavailable.");

    jsize count = env->GetArrayLength(audio);
    if (count <= 0) return env->NewStringUTF("The selected audio contains no decodable samples.");
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    std::string lang = jstringToString(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(2u, std::min(4u, std::thread::hardware_concurrency()));
    params.language = (lang.empty() || lang == "auto") ? "auto" : lang.c_str();
    params.translate = false;
    params.no_context = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.max_len = wordMode == 1 ? 1 : 0;

    int result = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (result != 0) return env->NewStringUTF("Transcription failed. Try a smaller model or shorter audio.");

    std::string output;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (!text) continue;
        std::string line(text);
        if (line.find_first_not_of(" \t\r\n") == std::string::npos) continue;
        output += "[" + stamp(whisper_full_get_segment_t0(ctx, i)) + "] " + line + "\n";
    }
    if (output.empty()) output = "No speech was detected.";
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_keal_whisper_WhisperNative_releaseModel(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}
