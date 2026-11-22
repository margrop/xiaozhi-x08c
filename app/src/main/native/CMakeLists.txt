cmake_minimum_required(VERSION 3.10.2)

project(x08copus LANGUAGES CXX)

# Build Opus 1.5.2 from the vendored official sources as a static library.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
# Walk from app/src/main/native up to the repository root. Repeated DIRECTORY
# normalization is used instead of ".." so no parent-directory segments remain
# in the path (purely lexical and friendly to exotic filesystem mounts).
get_filename_component(_dir "${CMAKE_CURRENT_SOURCE_DIR}" DIRECTORY)
get_filename_component(_dir "${_dir}" DIRECTORY)
get_filename_component(_dir "${_dir}" DIRECTORY)
get_filename_component(X08C_ROOT "${_dir}" DIRECTORY)
add_subdirectory(
        "${X08C_ROOT}/third_party/opus"
        "${CMAKE_CURRENT_BINARY_DIR}/opus")

add_library(x08copus SHARED opus_jni.cpp)

target_compile_features(x08copus PRIVATE cxx_std_17)

target_link_libraries(x08copus PRIVATE opus)
