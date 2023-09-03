//
// Created by Judge on 12/23/2021.
//
#include <thread>
#include <string>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <OpenOVR/openxr_platform.h>
#include <jni.h>
#include <android/sharedmem.h>
#include "log.h"
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/hardware_buffer.h>

static JavaVM* jvm;
XrInstanceCreateInfoAndroidKHR* OpenComposite_Android_Create_Info;
XrGraphicsBindingOpenGLESAndroidKHR* OpenComposite_Android_GLES_Binding_Info;

std::string (*OpenComposite_Android_Load_Input_File)(const char *path);

static GLuint framebuffer;
static std::string load_file(const char *path);

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
    OpenComposite_Android_Load_Input_File = load_file;

    env->GetJavaVM(&jvm);
    ctx = env->NewGlobalRef(ctx);
    OpenComposite_Android_Create_Info = new XrInstanceCreateInfoAndroidKHR{
            XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR,
            nullptr,
            jvm,
            ctx
    };

    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    XrResult res;

    res = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                                (PFN_xrVoidFunction *) (&initializeLoader));

    if(!XR_SUCCEEDED(res)) {
        printf("Error!");
    }

    XrLoaderInitInfoAndroidKHR loaderInitInfoAndroidKhr = {
            XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR,
            nullptr,
            jvm,
            ctx
    };

    initializeLoader((const XrLoaderInitInfoBaseHeaderKHR *) &loaderInitInfoAndroidKhr);
}

extern "C"
JNIEXPORT void JNICALL
Java_pojlib_util_VLoader_setEGLGlobal(JNIEnv* env, jclass clazz, jlong ctx, jlong display, jlong cfg) {
    OpenComposite_Android_GLES_Binding_Info = new XrGraphicsBindingOpenGLESAndroidKHR {
            XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR,
            nullptr,
            (void*)display,
            (void*)cfg,
            (void*)ctx
    };
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_vivecraft_utils_VLoader_createEGLImage(JNIEnv* env, jclass clazz, jlong buffer) {
    auto* nativeBuffer = reinterpret_cast<AHardwareBuffer *>(buffer);
    auto eglGetNativeClientBufferANDROID_p = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID_p(nativeBuffer);

    EGLAttrib args[] = {
            EGL_IMAGE_PRESERVED, EGL_TRUE,
            EGL_NONE
    };
    EGLImage eglImage = eglCreateImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, args);

    EGLenum eglError = eglGetError();
    while(eglError != EGL_SUCCESS) {
        printf("EGL error in createEGLImage! %d.\n", eglError);
        eglError = eglGetError();
    }

    GLenum error = glGetError();
    while(error != GL_NO_ERROR) {
        printf("OpenGLES error in createEGLImage! %d.\n", error);
        error = glGetError();
    }

    return reinterpret_cast<jlong>(eglImage);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_vivecraft_utils_VLoader_createGLImage(JNIEnv* env, jclass clazz, jlong eglImage, jint width, jint height) {
    auto glEGLImageTargetTexture2DOES_p = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));

    GLint image;
    glGenTextures(1, reinterpret_cast<GLuint *>(&image));
    glBindTexture(GL_TEXTURE_2D, image);
    glEGLImageTargetTexture2DOES_p(GL_TEXTURE_2D, (void*) eglImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);

    EGLenum eglError = eglGetError();
    while(eglError != EGL_SUCCESS) {
        printf("EGL error in createGLImage! %d.\n", eglError);
        eglError = eglGetError();
    }

    GLenum error = glGetError();
    while(error != GL_NO_ERROR) {
        printf("OpenGLES error in createGLImage! %d.\n", error);
        error = glGetError();
    }

    return image;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_vivecraft_utils_VLoader_flushFrame(JNIEnv* env, jclass clazz, jint nativeImage, jlong eglImage) {
    glBindTexture(GL_TEXTURE_2D, nativeImage);

}

static std::string load_file(const char *path) {
    // Just read the file from the filesystem, we changed the working directory earlier so
    // Vivecraft can extract it's manifest files.

    printf("Path: %s", path);
    int fd = open(path, O_RDONLY);
    if (!fd) {
        LOGE("Failed to load manifest file %s: %d %s", path, errno, strerror(errno));
    }

    int length = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);

    std::string data;
    data.resize(length);
    if (!read(fd, (void *) data.data(), data.size())) {
        LOGE("Failed to load manifest file %s failed to read: %d %s", path, errno, strerror(errno));
    }

    if (close(fd)) {
        LOGE("Failed to load manifest file %s failed to close: %d %s", path, errno,
             strerror(errno));
    }

    return std::move(data);
}
