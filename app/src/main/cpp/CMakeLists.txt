cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c utils.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log)

# The catalog has a separate pinned engine and JNI namespace. Legacy manual UI remains supported.
set(PILOT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../tv/src/main/cpp)
set(PILOT_ENGINE ${PILOT_ROOT}/engine)
add_library(mobilepilot SHARED ${PILOT_ENGINE}/packets.c ${PILOT_ENGINE}/main.c ${PILOT_ENGINE}/conev.c ${PILOT_ENGINE}/proxy.c ${PILOT_ENGINE}/desync.c ${PILOT_ENGINE}/mpool.c ${PILOT_ENGINE}/extend.c mobile_jni.c ${PILOT_ROOT}/quic_guard.c)
target_include_directories(mobilepilot PRIVATE ${PILOT_ENGINE})
target_compile_options(mobilepilot PRIVATE -std=c99 -O2)
target_compile_definitions(mobilepilot PRIVATE _DEFAULT_SOURCE ANDROID_APP)
target_link_libraries(mobilepilot PRIVATE android log)
target_link_options(mobilepilot PRIVATE "-Wl,--wrap=sendto" "-Wl,--wrap=connect")
