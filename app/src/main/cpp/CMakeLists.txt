cmake_minimum_required(VERSION 3.22)
project(recall_guard LANGUAGES C CXX)

set(CMAKE_C_STANDARD 18)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(recall_guard SHARED recall_guard.cpp)
target_link_libraries(recall_guard PRIVATE log android dl)
target_compile_options(recall_guard PRIVATE -Wall -Wextra -Wno-unused-parameter)
