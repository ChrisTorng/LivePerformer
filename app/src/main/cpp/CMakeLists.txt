cmake_minimum_required(VERSION 3.22.1)
project(liveperformer LANGUAGES CXX)

add_library(liveperformer SHARED NativeEngine.cpp)
find_package(oboe REQUIRED CONFIG)
target_include_directories(liveperformer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(liveperformer PRIVATE oboe::oboe log dl android)
target_compile_options(liveperformer PRIVATE -Wall -Wextra -Werror=return-type)
