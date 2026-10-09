cmake_minimum_required(VERSION 3.20)
project(inferencestack_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# ONNX Runtime prebuilt package (include/ + lib/). Pinned in the Dockerfile (1.31.0).
set(ORT_ROOT "" CACHE PATH "Path to an extracted onnxruntime release")
find_path(ORT_INCLUDE onnxruntime_cxx_api.h HINTS ${ORT_ROOT}/include REQUIRED)
find_library(ORT_LIB onnxruntime HINTS ${ORT_ROOT}/lib REQUIRED)

include(FetchContent)
FetchContent_Declare(json GIT_REPOSITORY https://github.com/nlohmann/json.git GIT_TAG v3.11.3 GIT_SHALLOW TRUE)
FetchContent_Declare(httplib GIT_REPOSITORY https://github.com/yhirose/cpp-httplib.git GIT_TAG v0.18.3 GIT_SHALLOW TRUE)
FetchContent_Declare(googletest GIT_REPOSITORY https://github.com/google/googletest.git GIT_TAG v1.15.2 GIT_SHALLOW TRUE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(json httplib googletest)

add_library(engine_core src/model.cpp src/image.cpp src/server.cpp)
target_include_directories(engine_core PUBLIC include ${ORT_INCLUDE} PRIVATE third_party)
target_link_libraries(engine_core PUBLIC ${ORT_LIB} nlohmann_json::nlohmann_json httplib::httplib)
target_compile_options(engine_core PUBLIC -Wall -Wextra)

add_executable(engine src/main.cpp)
target_link_libraries(engine PRIVATE engine_core)

enable_testing()
add_executable(engine_tests tests/test_image.cpp tests/test_model.cpp tests/test_http.cpp tests/test_util.cpp)
target_include_directories(engine_tests PRIVATE third_party tests)
target_link_libraries(engine_tests PRIVATE engine_core GTest::gtest_main)
include(GoogleTest)
gtest_discover_tests(engine_tests DISCOVERY_TIMEOUT 60)
set_target_properties(engine engine_tests PROPERTIES BUILD_RPATH "${ORT_ROOT}/lib" INSTALL_RPATH "$ORIGIN")
