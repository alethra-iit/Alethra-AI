#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "AlethraLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> stop{false};
};

// Panjang prefix string yang berisi karakter UTF-8 utuh (token bisa memotong karakter multibyte).
static size_t utf8_valid_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t p = n - 1;
    int k = 0;
    while (p > 0 && k < 3 && ((unsigned char) s[p] & 0xC0) == 0x80) { p--; k++; }
    unsigned char c = (unsigned char) s[p];
    size_t len = c < 0x80 ? 1 : c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
    return (p + len <= n) ? n : p;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alethra_ai_llama_LlamaBridge_load(JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    static bool inited = false;
    if (!inited) { llama_backend_init(); inited = true; }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("gagal memuat model"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("gagal membuat context"); llama_model_free(model); return 0; }

    auto *h = new Handle();
    h->model = model;
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alethra_ai_llama_LlamaBridge_stop(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Handle *>(handle)->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alethra_ai_llama_LlamaBridge_free(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

// Return: jumlah token dihasilkan, atau kode error negatif:
// -1 template gagal, -2 tokenisasi gagal, -3 prompt terlalu panjang, -4 decode gagal.
extern "C" JNIEXPORT jint JNICALL
Java_com_alethra_ai_llama_LlamaBridge_generate(JNIEnv *env, jobject, jlong handle,
                                               jobjectArray roles, jobjectArray contents,
                                               jint maxTokens, jfloat temperature, jobject cb) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) return -1;
    h->stop = false;

    const int n = env->GetArrayLength(roles);
    std::vector<std::string> rs(n), cs(n);
    for (int i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        const char *r = env->GetStringUTFChars(jr, nullptr);
        rs[i] = r;
        env->ReleaseStringUTFChars(jr, r);
        env->DeleteLocalRef(jr);

        auto jc = (jbyteArray) env->GetObjectArrayElement(contents, i);
        jsize len = env->GetArrayLength(jc);
        cs[i].resize(len);
        env->GetByteArrayRegion(jc, 0, len, reinterpret_cast<jbyte *>(&cs[i][0]));
        env->DeleteLocalRef(jc);
    }
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) msgs[i] = {rs[i].c_str(), cs[i].c_str()};

    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    const char *tmpl = llama_model_chat_template(h->model, nullptr);
    if (!tmpl) tmpl = "chatml";

    std::vector<char> buf(16384);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    if (len < 0) return -1;
    std::string prompt(buf.data(), len);

    int nTok = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, false, true);
    if (nTok <= 0) return -2;
    std::vector<llama_token> toks(nTok);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), toks.data(), toks.size(), false, true) < 0) return -2;

    const int nCtx = (int) llama_n_ctx(h->ctx);
    if (nTok >= nCtx - 16) return -3;
    maxTokens = std::min<int>(maxTokens, nCtx - nTok);

    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cbc = env->GetObjectClass(cb);
    jmethodID mid = env->GetMethodID(cbc, "onToken", "([B)Z");

    auto emit = [&](const std::string &s) -> bool {
        jbyteArray arr = env->NewByteArray((jsize) s.size());
        env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
        jboolean cont = env->CallBooleanMethod(cb, mid, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) return false;
        return cont;
    };

    const int nBatch = 512;
    for (int i = 0; i < nTok; i += nBatch) {
        int c = std::min(nBatch, nTok - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, c);
        if (llama_decode(h->ctx, b) != 0) { llama_sampler_free(smpl); return -4; }
        if (h->stop) { llama_sampler_free(smpl); return 0; }
    }

    std::string pending;
    int produced = 0;
    bool keepGoing = true;
    while (produced < maxTokens && !h->stop && keepGoing) {
        llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;

        char piece[256];
        int pl = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, true);
        if (pl > 0) pending.append(piece, pl);
        size_t ok = utf8_valid_prefix(pending);
        if (ok > 0) {
            keepGoing = emit(pending.substr(0, ok));
            pending.erase(0, ok);
        }
        produced++;
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(h->ctx, b) != 0) break;
    }
    if (!pending.empty() && keepGoing) emit(pending);

    llama_sampler_free(smpl);
    return produced;
}
