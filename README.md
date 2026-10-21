# Alethra AI

Asisten AI pribadi yang berjalan **sepenuhnya offline** di Android: chat teks & suara, floating assistant,
riwayat lokal, dan jawaban yang dibacakan. Tanpa API key cloud.

| Komponen | Teknologi |
|---|---|
| Bahasa / UI | Kotlin, Jetpack Compose (Material 3) |
| LLM | llama.cpp (JNI, arm64-v8a) |
| Speech-to-text | whisper.cpp (JNI) |
| Text-to-speech | Android `TextToSpeech` |
| Database | Room |
| Build / CI | Gradle (Kotlin DSL), GitHub Actions |

Package: `com.alethra.ai` · Target: Android 8.0+ (API 26), arm64, RAM 12 GB.

## Build lokal
```bash
bash scripts/fetch-native.sh          # unduh llama.cpp & whisper.cpp (versi di dalam skrip)
gradle wrapper --gradle-version 8.9  # sekali saja, jika ingin ./gradlew
./gradlew :app:assembleDebug
```
Butuh JDK 17, Android SDK 34, NDK `27.2.12479018`, CMake `3.22.1`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Build otomatis (GitHub Actions)
Push ke `main` → workflow **Build Alethra AI APK** menghasilkan artifact **`Alethra-AI-debug.apk`**
(Actions → run terbaru → Artifacts).

## Model (diimpor dari Pengaturan, disimpan di penyimpanan internal app)
- **LLM (.gguf)** – rekomendasi untuk RAM 12 GB: `Qwen2.5-7B-Instruct-Q4_K_M` (~4,7 GB) atau `Gemma-3-4B-it-Q4_K_M`. Konteks 4096 token.
- **Whisper** – `ggml-small.bin` (~470 MB, multibahasa; model `.en` tidak mengenali bahasa Indonesia).

Salin file ke ponsel, lalu Pengaturan → *Impor berkas*.

Atau tekan **Unduh** di Pengaturan (butuh internet hanya untuk mengunduh; setelah itu 100% offline; unduhan bisa dilanjutkan jika terputus). Sumber: `bartowski/Qwen2.5-7B-Instruct-GGUF` dan `ggerganov/whisper.cpp` di Hugging Face.

## Floating assistant
Pengaturan → aktifkan. Perlu izin: tampil di atas aplikasi lain, mikrofon, notifikasi.
Ketuk gelembung untuk membuka panel; seret untuk memindahkan.

## Catatan
- Versi `llama.cpp` / `whisper.cpp` dipin lewat variabel `LLAMA_CPP_REF` / `WHISPER_CPP_REF` (skrip & workflow).
  API llama.cpp sering berubah; bila ganti versi, sesuaikan `llama/src/main/cpp/llama_jni.cpp`.
- `llama.cpp` dan `whisper.cpp` masing-masing membawa ggml sendiri, jadi dipisah ke modul `:llama` dan `:whisper`.
- Flag CPU `armv8.2-a+dotprod+fp16` mengasumsikan SoC modern (hampir semua perangkat 12 GB RAM).
