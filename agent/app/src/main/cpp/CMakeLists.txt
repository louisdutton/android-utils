cmake_minimum_required(VERSION 3.22.1)
project(agent_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Fetch whisper.cpp
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.2
    GIT_SHALLOW TRUE
)

# Fetch llama.cpp
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b4568
    GIT_SHALLOW TRUE
)

# Configure whisper.cpp build options
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Configure llama.cpp build options
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper llama)

# Find JNI
find_package(JNI REQUIRED)

# Create our JNI library
add_library(agent_jni SHARED
    whisper_jni.cpp
    llama_jni.cpp
)

target_include_directories(agent_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${whisper_SOURCE_DIR}
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/common
)

target_link_libraries(agent_jni
    whisper
    llama
    common
    android
    log
)
