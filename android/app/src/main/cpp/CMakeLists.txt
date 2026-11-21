cmake_minimum_required(VERSION 3.22.1)
project(handy_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whisper")

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}/ggml" "${CMAKE_CURRENT_BINARY_DIR}/ggml")

# Always optimize GGML and Whisper at -O3 even in Android debug APKs so
# on-device transcription runs at full real-time speed on mobile CPUs.
target_compile_options(ggml PRIVATE -O3)
if(TARGET ggml-base)
    target_compile_options(ggml-base PRIVATE -O3)
endif()
if(TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE -O3)
endif()

add_library(
    handy_whisper
    SHARED
    "${WHISPER_DIR}/src/whisper.cpp"
    "${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/handy_whisper_jni.cpp"
)

target_compile_definitions(
    handy_whisper
    PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="1.9.5"
)

target_compile_options(
    handy_whisper
    PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(
    handy_whisper
    PRIVATE
    -Wl,--gc-sections
)

target_include_directories(
    handy_whisper
    PRIVATE
    "${WHISPER_DIR}"
    "${WHISPER_DIR}/include"
    "${WHISPER_DIR}/src"
    "${WHISPER_DIR}/ggml/include"
    "${WHISPER_DIR}/ggml/src"
    "${WHISPER_DIR}/ggml/src/ggml-cpu"
)

find_library(LOG_LIB log)
target_link_libraries(handy_whisper PRIVATE ggml ${LOG_LIB} android)
