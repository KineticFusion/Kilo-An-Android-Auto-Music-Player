cmake_minimum_required(VERSION 3.22.1)
project("kilomixer" LANGUAGES CXX)

# C++20 with optimizations for real-time audio
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build configuration
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Compiler flags for audio DSP
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++20 -fexceptions -frtti -O3 -ffast-math -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -DNDEBUG -DREALTIME_AUDIO")

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Find Oboe via Prefab (from com.google.oboe:oboe Gradle dependency)
find_package(oboe REQUIRED CONFIG)

# System libraries
find_library(log_library log)
find_library(OpenSLES_library OpenSLES)

# Native mixer library - only .cpp files (headers are header-only)
add_library(kilomixer SHARED
    native_mixer.cpp
    jni_bridge.cpp
)

target_link_libraries(kilomixer
    oboe::oboe
    ${log_library}
    ${OpenSLES_library}
    android
)

# 16KB ELF page alignment for Android 15+ (required for 16KB page size devices)
target_link_options(kilomixer PRIVATE "-Wl,-z,max-page-size=16384")

# JNI headers
target_include_directories(kilomixer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ANDROID_NDK}/sysroot/usr/include
)