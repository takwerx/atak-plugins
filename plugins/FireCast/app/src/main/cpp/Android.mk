LOCAL_PATH := $(call my-dir)

# The Fire Lab's behave library (public domain, see behave/README-firecast.md) and
# one JNI file over its surface fire module. No other native dependencies.
include $(CLEAR_VARS)
LOCAL_MODULE := firecast
LOCAL_CPPFLAGS := -std=c++14 -O2 -fexceptions -frtti -fvisibility=hidden
LOCAL_C_INCLUDES := $(LOCAL_PATH)/behave
LOCAL_SRC_FILES := firecast_jni.cpp $(subst $(LOCAL_PATH)/,,$(wildcard $(LOCAL_PATH)/behave/*.cpp))
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
