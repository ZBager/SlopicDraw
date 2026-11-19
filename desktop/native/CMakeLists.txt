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

set(shader_headers)
foreach(shader IN ITEMS evaluate batch)
  set(header "${CMAKE_CURRENT_BINARY_DIR}/${shader}.inc")
  add_custom_command(OUTPUT "${header}"
    COMMAND "${GLSLC}" --target-env=vulkan1.3 -O -mfmt=num "-I${terni_SOURCE_DIR}/shaders"
            "${CMAKE_CURRENT_SOURCE_DIR}/${shader}.comp" -o "${header}"
    DEPENDS "${shader}.comp" timer.glsl programmer.glsl "${terni_SOURCE_DIR}/shaders/terni/compute.glsl"
    VERBATIM)
  list(APPEND shader_headers "${header}")
endforeach()

foreach(stage IN ITEMS initialize evaluate commit)
  string(TOUPPER "${stage}" stage_define)
  set(header "${CMAKE_CURRENT_BINARY_DIR}/resident_${stage}.inc")
  add_custom_command(OUTPUT "${header}"
    COMMAND "${GLSLC}" --target-env=vulkan1.3 -O -mfmt=num "-D${stage_define}"
            "-I${terni_SOURCE_DIR}/shaders" "${CMAKE_CURRENT_SOURCE_DIR}/resident.comp" -o "${header}"
    DEPENDS resident.comp timer.glsl programmer.glsl "${terni_SOURCE_DIR}/shaders/terni/compute.glsl"
    VERBATIM)
  list(APPEND shader_headers "${header}")
endforeach()

foreach(shader IN ITEMS batch resident)
  set(header "${CMAKE_CURRENT_BINARY_DIR}/${shader}_events.inc")
  add_custom_command(OUTPUT "${header}"
    COMMAND "${GLSLC}" --target-env=vulkan1.3 -O -mfmt=num -DHOST_EVENTS
            "-I${terni_SOURCE_DIR}/shaders" "${CMAKE_CURRENT_SOURCE_DIR}/${shader}.comp" -o "${header}"
    DEPENDS "${shader}.comp" ram.glsl timer.glsl programmer.glsl "${terni_SOURCE_DIR}/shaders/terni/compute.glsl"
    VERBATIM)
  list(APPEND shader_headers "${header}")
endforeach()

add_library(logicdraw_terni SHARED bridge.cpp ${shader_headers})
target_link_libraries(logicdraw_terni PRIVATE Terni::runtime)
target_include_directories(logicdraw_terni PRIVATE ${JNI_INCLUDE_DIRS} "${CMAKE_CURRENT_BINARY_DIR}")
set_target_properties(logicdraw_terni PROPERTIES CXX_VISIBILITY_PRESET hidden)
if(WIN32)
  set_target_properties(logicdraw_terni PROPERTIES PREFIX "")
endif()
install(TARGETS logicdraw_terni RUNTIME DESTINATION . LIBRARY DESTINATION .)
