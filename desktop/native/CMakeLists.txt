cmake_minimum_required(VERSION 3.21)
project(LogicDrawTerni LANGUAGES CXX)

if(NOT WIN32 AND NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
  message(FATAL_ERROR "The LogicDraw Terni bridge targets Windows and Linux")
endif()

find_package(JNI REQUIRED)
find_package(Vulkan REQUIRED)
find_program(GLSLC glslc HINTS "$ENV{VULKAN_SDK}/Bin" REQUIRED)

include(FetchContent)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(TERNI_RULE_COMPILER OFF CACHE BOOL "" FORCE)
set(TERNI_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(TERNI_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TERNI_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(TERNI_C_API OFF CACHE BOOL "" FORCE)
FetchContent_Declare(terni
  GIT_REPOSITORY https://github.com/ZBager/Terni.git
  GIT_TAG 7b2e534b2b80a147f903c6d85f09806ffd17e318)
FetchContent_MakeAvailable(terni)

set(shader_header "${CMAKE_CURRENT_BINARY_DIR}/evaluate.inc")
add_custom_command(OUTPUT "${shader_header}"
  COMMAND "${GLSLC}" --target-env=vulkan1.3 -O -mfmt=num "-I${terni_SOURCE_DIR}/shaders"
          "${CMAKE_CURRENT_SOURCE_DIR}/evaluate.comp" -o "${shader_header}"
  DEPENDS evaluate.comp "${terni_SOURCE_DIR}/shaders/terni/compute.glsl"
  VERBATIM)

add_library(logicdraw_terni SHARED bridge.cpp "${shader_header}")
target_link_libraries(logicdraw_terni PRIVATE Terni::runtime)
target_include_directories(logicdraw_terni PRIVATE ${JNI_INCLUDE_DIRS} "${CMAKE_CURRENT_BINARY_DIR}")
set_target_properties(logicdraw_terni PROPERTIES CXX_VISIBILITY_PRESET hidden)
if(WIN32)
  set_target_properties(logicdraw_terni PROPERTIES PREFIX "")
endif()
install(TARGETS logicdraw_terni RUNTIME DESTINATION . LIBRARY DESTINATION .)
