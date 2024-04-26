//
// Created by Judge on 12/23/2021.
//
#include <thread>
#include <string>
#include <errno.h>
#include <android/hardware_buffer.h>
#include <fcntl.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include "log.h"
#include <GLES3/gl32.h>

static JavaVM* jvm;
static jobject activity;

extern "C"
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    if(jvm == nullptr) {
        jvm = vm;
    }
    return JNI_VERSION_1_4;
}

extern "C"
JNIEXPORT void JNICALL
Java_pojlib_util_VLoader_setAndroidInitInfo(JNIEnv *env, jclass clazz, jobject ctx) {
    ctx = env->NewGlobalRef(ctx);
    activity = ctx;
    env->GetJavaVM(&jvm);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLCtx(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(eglGetCurrentContext());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLCfg(JNIEnv* env, jclass clazz) {
    EGLConfig cfg;
    EGLint num_configs;

    static const EGLint attribs[] = {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            // Minecraft required on initial 24
            EGL_DEPTH_SIZE, 24,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_NONE
    };

    eglChooseConfig(eglGetCurrentDisplay(), attribs, &cfg, 1, &num_configs);
    return reinterpret_cast<jlong>(cfg);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getEGLDisp(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(eglGetCurrentDisplay());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getJVMPtr(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(jvm);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_util_VLoader_getActivityPtr(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(activity);
}