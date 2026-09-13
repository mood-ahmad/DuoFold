cmake_minimum_required(VERSION 3.22.1)
project(duofoldvk)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

add_library(duofoldvk SHARED duofoldvk.cpp)
target_compile_options(duofoldvk PRIVATE -std=c++17)
target_link_libraries(duofoldvk android log vulkan)
