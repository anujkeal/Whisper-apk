#include <jni.h>
#include <string>
#include <algorithm>
#include <thread>
#include <cstdio>
#include <cctype>
#include "whisper.h"

static std::string jstringToString(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::string stamp(int64_t centiseconds) {
    centiseconds = std::max<int64_t>(0, centiseconds);
    const int64_t totalSeconds = centiseconds / 100;
    const int h = static_cast<int>(totalSeconds / 3600);
    const int m = static_cast<int>((totalSeconds % 3600) / 60);
    const int s = static_cast<int>(totalSeconds % 60);
    const int cs = static_cast<int>(centiseconds % 100);
    char out[24];
    std::snprintf(out, sizeof(out), "%02d:%02d:%02d.%02d", h, m, s, cs);
    return out;
}

static void appendWord(std::string &out, const std::string &word, int64_t t0, int64_t t1) {
    if (word.empty()) return;
    if (t0 < 0) t0 = 0;
    if (t1 <= t0) t1 = t0 + 1;
    out += "[" + stamp(t0) + " --> " + stamp(t1) + "] " + word + "\n";
}

static std::string segmentAsWords(whisper_context *ctx, int segment) {
    std::string output;
    std::string word;
    int64_t wordStart = -1;
    int64_t wordEnd = -1;
    const int tokenCount = whisper_full_n_tokens(ctx, segment);
    bool sawWhitespace = false;

    for (int j = 0; j < tokenCount; ++j) {
        const whisper_token id = whisper_full_get_token_id(ctx, segment, j);
        if (id >= whisper_token_eot(ctx)) continue;

        const char *piece = whisper_full_get_token_text(ctx, segment, j);
        if (!piece) continue;
        const std::string token(piece);
        const whisper_token_data data = whisper_full_get_token_data(ctx, segment, j);

        for (unsigned char ch : token) {
            if (std::isspace(ch)) {
                sawWhitespace = true;
                appendWord(output, word, wordStart, wordEnd);
                word.clear();
                wordStart = -1;
                wordEnd = -1;
                continue;
            }
            if (word.empty()) {
                wordStart = data.t0;
            }
            word.push_back(static_cast<char>(ch));
            wordEnd = data.t1;
        }
    }
    appendWord(output, word, wordStart, wordEnd);

    // Languages without whitespace (for example Chinese or Japanese) cannot be
    // segmented into linguistic words by whitespace. Fall back to timed model tokens.
    if (!sawWhitespace && output.empty()) {
        for (int j = 0; j < tokenCount; ++j) {
            const whisper_token id = whisper_full_get_token_id(ctx, segment, j);
            if (id >= whisper_token_eot(ctx)) continue;
            const char *piece = whisper_full_get_token_text(ctx, segment, j);
            if (!piece || !*piece) continue;
            const whisper_token_data data = whisper_full_get_token_data(ctx, segment, j);
            appendWord(output, piece, data.t0, data.t1);
        }
    }
    return output;
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

    const jsize count = env->GetArrayLength(audio);
    if (count <= 0) return env->NewStringUTF("The selected audio contains no decodable samples.");

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (!samples) return env->NewStringUTF("Could not access decoded audio samples.");
    const std::string lang = jstringToString(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    const unsigned int cores = std::max(1u, std::thread::hardware_concurrency());
    params.n_threads = static_cast<int>(std::min(4u, cores));
    params.language = (lang.empty() || lang == "auto") ? "auto" : lang.c_str();
    params.translate = false;
    params.no_context = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_nst = true;

    // Beam search improves recognition robustness at the cost of speed.
    params.beam_search.beam_size = 5;
    params.temperature = 0.0f;
    params.temperature_inc = 0.2f;

    // Whisper's native token aligner supplies per-token timing. The Kotlin layer
    // requests word mode; this flag is not a fake max_len-based segmentation.
    params.token_timestamps = (wordMode == 1);
    params.thold_pt = 0.01f;
    params.thold_ptsum = 0.01f;

    const int result = whisper_full(ctx, params, samples, count);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (result != 0) return env->NewStringUTF("Transcription failed. Try a smaller model or a shorter audio file.");

    std::string output;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (!text || std::string(text).find_first_not_of(" \t\r\n") == std::string::npos) continue;

        if (wordMode == 1) {
            output += segmentAsWords(ctx, i);
        } else {
            output += "[" + stamp(whisper_full_get_segment_t0(ctx, i)) + " --> " +
                      stamp(whisper_full_get_segment_t1(ctx, i)) + "] " + text + "\n";
        }
    }

    if (output.empty()) output = "No speech was detected.";
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_keal_whisper_WhisperNative_releaseModel(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}
