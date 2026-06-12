cmake_minimum_required(VERSION 3.22.1)
project(geopulse_fusion LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(geopulse-fusion SHARED
  src/main/cpp/kalman.cpp
  src/main/cpp/kalman_cv.cpp
  src/main/cpp/fusion.cpp
  src/main/cpp/jni_bridge.cpp)

target_include_directories(geopulse-fusion PRIVATE src/main/cpp)

find_library(log-lib log)
target_link_libraries(geopulse-fusion ${log-lib})

target_compile_options(geopulse-fusion PRIVATE -O2 -Wall)

# Android 15 (API 35) requires shared libs to support 16 KB memory page sizes.
target_link_options(geopulse-fusion PRIVATE "-Wl,-z,max-page-size=16384")
