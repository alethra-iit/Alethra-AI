#!/usr/bin/env bash
# Mengunduh llama.cpp & whisper.cpp ke dalam modul native.
# Ubah versi lewat environment variable bila perlu.
set -euo pipefail
cd "$(dirname "$0")/.."
LLAMA_CPP_REF="${LLAMA_CPP_REF:-b6000}"
WHISPER_CPP_REF="${WHISPER_CPP_REF:-v1.7.6}"

clone() { # url ref dir
  if [ -f "$3/CMakeLists.txt" ]; then echo "$3 sudah ada, lewati"; return; fi
  git clone --depth 1 --branch "$2" "$1" "$3"
}
clone https://github.com/ggml-org/llama.cpp   "$LLAMA_CPP_REF"   llama/src/main/cpp/llama.cpp
clone https://github.com/ggml-org/whisper.cpp "$WHISPER_CPP_REF" whisper/src/main/cpp/whisper.cpp
