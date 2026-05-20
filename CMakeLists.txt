cmake_minimum_required(VERSION 3.22.1)
project(ara-android)

# ARA_JNILIBS_DIR — where libaraengine.so and libcrsqlite.so live.
# Defaults to alongside this CMakeLists (sdk/v1/android/src/main/jniLibs).
# Override from the calling project's build.gradle.kts:
#   cmake { arguments("-DARA_JNILIBS_DIR=${projectDir}/src/main/jniLibs") }
if(NOT DEFINED ARA_JNILIBS_DIR)
    set(ARA_JNILIBS_DIR "${CMAKE_SOURCE_DIR}/src/main/jniLibs")
endif()

# ── ara_jni (JNI bridge compiled by NDK) ─────────────────────────────────────
add_library(ara_jni SHARED jni/ara_jni.c)

target_include_directories(ara_jni PRIVATE jni)

# Link against libaraengine.so by name (-L/-l) so the linker records
# "libaraengine.so" in DT_NEEDED, not the full build-time path.
target_link_directories(ara_jni PRIVATE "${ARA_JNILIBS_DIR}/${ANDROID_ABI}")

target_link_libraries(ara_jni
    -laraengine
    android
    log
)
