cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES CXX)

# ----------------------------------------------------------------------------
# whisper.cpp build options — strip everything we don't need on Android NDK
# ----------------------------------------------------------------------------
# Library only: no examples / tests / server.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# OpenMP isn't reliably available in the NDK — disable to avoid link errors.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# Static link only (no extra .so)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# CPU-only on mobile; no GPU backends.
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE        OFF CACHE BOOL "" FORCE)
set(GGML_KOMPUTE           OFF CACHE BOOL "" FORCE)

# Don't let "all warnings" + 3rd-party warnings break our build.
set(WHISPER_ALL_WARNINGS           OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS              OFF CACHE BOOL "" FORCE)
set(WHISPER_FATAL_WARNINGS         OFF CACHE BOOL "" FORCE)
set(GGML_FATAL_WARNINGS            OFF CACHE BOOL "" FORCE)

# Pull in the pinned third_party/whisper.cpp checkout.
# CMAKE_SOURCE_DIR == app/src/main/cpp; repo root is four levels up.
add_subdirectory(
    ${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp
    ${CMAKE_BINARY_DIR}/whisper_cpp_build
)

# ----------------------------------------------------------------------------
# JNI bridge library (libwhisper_jni.so)
# ----------------------------------------------------------------------------
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_compile_features(whisper_jni PRIVATE cxx_std_17)

target_link_libraries(whisper_jni
    PRIVATE
    whisper      # whisper.cpp library target (static)
    log          # __android_log_print
)
