cmake_minimum_required(VERSION 3.22.1)
project(nuke_zygisk LANGUAGES C CXX ASM)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only Android arm64-v8a is supported")
endif()
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if(NOT DEFINED DEPS_ROOT)
    message(FATAL_ERROR "Pass DEPS_ROOT from tools/build_native.py")
endif()

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(DEX_BUILDER_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory("${DEPS_ROOT}/lsplant/lsplant/src/main/jni" lsplant EXCLUDE_FROM_ALL)
add_subdirectory("${DEPS_ROOT}/dobby" dobby EXCLUDE_FROM_ALL)
# The pinned upstream C logger includes android/log.h within a function. Preinclude
# that header so NDK 28's inline helpers are declared at translation-unit scope.
set_source_files_properties("${DEPS_ROOT}/dobby/external/logging/logging.c"
    DIRECTORY "${DEPS_ROOT}/dobby"
    PROPERTIES COMPILE_OPTIONS "-include;android/log.h")

# xDL's upstream target is shared; compile its unmodified sources into our one library.
file(GLOB XDL_SOURCES CONFIGURE_DEPENDS "${DEPS_ROOT}/xdl/xdl/src/main/cpp/*.c")
add_library(nuke_xdl STATIC ${XDL_SOURCES})
target_include_directories(nuke_xdl PUBLIC "${DEPS_ROOT}/xdl/xdl/src/main/cpp/include")
target_compile_features(nuke_xdl PRIVATE c_std_17)
target_compile_options(nuke_xdl PRIVATE -ffunction-sections -fdata-sections)

add_library(nuke_zygisk SHARED module.cpp)
target_include_directories(nuke_zygisk PRIVATE "${DEPS_ROOT}/zygisk/module/jni")
target_link_libraries(nuke_zygisk PRIVATE lsplant_static dobby nuke_xdl log dl)
target_compile_options(nuke_zygisk PRIVATE -Wall -Wextra -Werror -ffunction-sections -fdata-sections)
target_link_options(nuke_zygisk PRIVATE
    -Wl,--no-undefined -Wl,--gc-sections -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
