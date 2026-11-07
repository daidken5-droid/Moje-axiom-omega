cmake_minimum_required(VERSION 3.22.1)
project(omega LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --------------------------------------------------------------------------
# llama.cpp – připnutý na konkrétní vydání a commit (reprodukovatelný build).
# Lokálně lze podstrčit stažený zdroj: -DFETCHCONTENT_SOURCE_DIR_LLAMA=/cesta/k/llama.cpp
# --------------------------------------------------------------------------
set(OMEGA_LLAMA_TAG "b12032")
set(OMEGA_LLAMA_COMMIT "dde879bc4f954b809892fd0d660515f42536394a")

include(FetchContent)
FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${OMEGA_LLAMA_TAG}
        GIT_SHALLOW TRUE
        GIT_PROGRESS FALSE
)

# Volby llama.cpp (stejné jako oficiální příklad examples/llama.android)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # Více variant CPU backendu (ARMv8.0 … v8.6 + i8mm/SVE) se načte za běhu podle telefonu.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_OPENMP ON CACHE BOOL "" FORCE)
    else()
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    endif()
endif()

FetchContent_MakeAvailable(llama)

if(NOT FETCHCONTENT_SOURCE_DIR_LLAMA)
    find_package(Git QUIET)
    if(GIT_FOUND)
        execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse HEAD
                WORKING_DIRECTORY ${llama_SOURCE_DIR}
                OUTPUT_VARIABLE OMEGA_FETCHED_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE)
        if(NOT OMEGA_FETCHED_COMMIT STREQUAL OMEGA_LLAMA_COMMIT)
            message(FATAL_ERROR "llama.cpp ${OMEGA_LLAMA_TAG}: očekávaný commit ${OMEGA_LLAMA_COMMIT}, staženo ${OMEGA_FETCHED_COMMIT}")
        endif()
    endif()
endif()

add_library(omega SHARED omega_jni.cpp)
target_include_directories(omega PRIVATE
        ${llama_SOURCE_DIR}/include
        ${llama_SOURCE_DIR}/common
        ${llama_SOURCE_DIR}/ggml/include)

if(ANDROID)
    target_link_libraries(omega PRIVATE llama llama-common ggml android log)
    # 16 KB stránky (Android 15+)
    target_link_options(omega PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(omega PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(omega PRIVATE llama llama-common ggml)
endif()
