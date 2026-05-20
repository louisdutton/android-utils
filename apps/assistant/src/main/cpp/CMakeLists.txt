cmake_minimum_required(VERSION 3.22)
project(assistant_whisper)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_ANDROID_STL_TYPE c++_static)

set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_DEBUG "-O1 -ggdb")
set(CMAKE_CXX_FLAGS_DEBUG "-O1 -ggdb")

add_compile_options(
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-vla-cxx-extension
    -fexceptions
    -DHAVE_PTHREAD
)

if(${ANDROID_ABI} MATCHES "armeabi-v7a")
    add_compile_options(-mfpu=neon)
endif()

set(KEYBOARD_JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../keyboard/native/jni)

add_library(
    assistant_whisper
    SHARED
    whisper_jni_common.cpp
    ${KEYBOARD_JNI_DIR}/src/jni_utils.cpp
    ${KEYBOARD_JNI_DIR}/org_futo_voiceinput_WhisperGGML.cpp
    ${KEYBOARD_JNI_DIR}/src/ggml/ggml.c
    ${KEYBOARD_JNI_DIR}/src/ggml/ggml-alloc.c
    ${KEYBOARD_JNI_DIR}/src/ggml/ggml-backend.c
    ${KEYBOARD_JNI_DIR}/src/ggml/ggml-quants.c
    ${KEYBOARD_JNI_DIR}/src/ggml/whisper.cpp
)

target_include_directories(
    assistant_whisper
    PRIVATE
    ${KEYBOARD_JNI_DIR}
    ${KEYBOARD_JNI_DIR}/src
    ${KEYBOARD_JNI_DIR}/src/ggml
)

target_link_libraries(assistant_whisper log dl)
