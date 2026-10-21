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

## Penandatanganan APK (penting untuk update)
APK ditandatangani dengan kunci tetap `app/alethra-debug.keystore` (dan `versionCode` naik otomatis di CI), jadi build baru
bisa langsung menimpa versi lama. **Jangan ganti/hapus berkas ini**, atau update akan "bentrok" lagi.
Kunci ini sengaja dibuat untuk APK debug pribadi dan ikut ada di repo; untuk rilis publik, buat keystore sendiri
dan simpan lewat GitHub Secrets.

## Build otomatis (GitHub Actions)
Push ke `main` → workflow **Build Alethra AI APK** menghasilkan artifact **`Alethra-AI-debug.apk`**
(Actions → run terbaru → Artifacts).

## Model (unduh atau impor dari Pengaturan, disimpan di penyimpanan internal app)
Boleh memasang **beberapa** model untuk tiap jenis, lalu pilih satu yang dipakai (tombol radio di Pengaturan).
Model yang baru selesai diunduh/diimpor otomatis menjadi model aktif; ganti kapan saja tanpa unduh ulang.

- **LLM (.gguf)** – preset: `Qwen2.5-7B-Instruct-Q4_K_M` (~4,7 GB, rekomendasi untuk RAM 12 GB), Gemma 3 4B / 12B, Llama 3.2 3B. Konteks 4096 token.
- **Suara (GGML untuk whisper.cpp)** – preset:
  - `Whisper Small • Indonesia` (maleo-ai, ~264 MB) – rekomendasi.
  - `Whisper Medium • Indonesia` (turunan `cahya/whisper-medium-id`, ~514 MB) – paling akurat, paling berat.
  - `Whisper Tiny • Indonesia` (~43 MB) – paling ringan.
  - `Whisper Small • Multibahasa` (~470 MB, OpenAI) – untuk bahasa selain Indonesia.
  Model `.en` tidak mengenali bahasa Indonesia. Model fine-tune Indonesia menghasilkan teks huruf kecil tanpa tanda baca.
- **Impor berkas** – salin file ke ponsel, lalu Pengaturan → *Impor berkas*; file ikut masuk daftar model.

Unduhan bisa dilanjutkan jika terputus (selama sumbernya sama) dan bisa dibatalkan lewat tombol **Batalkan**.
Model lama dari versi 1.x otomatis dipindahkan ke daftar sebagai "Model terpasang sebelumnya".

## Tampilan
Pengaturan → **Tampilan**: pilih mode (ikuti sistem / terang / gelap) dan palet warna (Ungu, Samudra, Hutan, Senja, Mawar, Grafit).
Pilihan disimpan dan juga berlaku untuk floating assistant. Palet ditambah/diubah di `ui/Theme.kt` (daftar `Palettes`).

## Floating assistant
Pengaturan → aktifkan. Perlu izin: tampil di atas aplikasi lain, mikrofon, notifikasi.
Ketuk gelembung untuk membuka panel; seret untuk memindahkan.

## Catatan
- Versi `llama.cpp` / `whisper.cpp` dipin lewat variabel `LLAMA_CPP_REF` / `WHISPER_CPP_REF` (skrip & workflow).
  API llama.cpp sering berubah; bila ganti versi, sesuaikan `llama/src/main/cpp/llama_jni.cpp`.
- `llama.cpp` dan `whisper.cpp` masing-masing membawa ggml sendiri, jadi dipisah ke modul `:llama` dan `:whisper`.
- Flag CPU `-march=armv8.2-a+dotprod+fp16` (di `CMakeLists.txt`) mengasumsikan SoC modern (hampir semua perangkat 12 GB RAM).
