#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_alethra_ai_whisper_WhisperBridge_load(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alethra_ai_whisper_WhisperBridge_free(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_alethra_ai_whisper_WhisperBridge_transcribe(JNIEnv *env, jobject, jlong handle,
                                                     jfloatArray audio, jstring jlang, jint threads) {
    std::string out;
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) {
        jsize n = env->GetArrayLength(audio);
        jfloat *data = env->GetFloatArrayElements(audio, nullptr);
        const char *lang = env->GetStringUTFChars(jlang, nullptr);

        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.language = lang;
        p.n_threads = threads;
        p.translate = false;
        p.print_realtime = false;
        p.print_progress = false;
        p.print_timestamps = false;
        p.print_special = false;
        p.no_context = true;
        p.suppress_blank = true;
        p.temperature = 0.0f;

        if (whisper_full(ctx, p, data, n) == 0) {
            int ns = whisper_full_n_segments(ctx);
            for (int i = 0; i < ns; i++) out += whisper_full_get_segment_text(ctx, i);
        }
        env->ReleaseStringUTFChars(jlang, lang);
        env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    }
    jbyteArray res = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(res, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}
