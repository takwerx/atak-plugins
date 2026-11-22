// Stand-in for Android's jni.h: the four calls firecast_jni.cpp makes, nothing else.
#pragma once
#include <cstring>
#include <string>
typedef int jint; typedef double jdouble; typedef void* jclass;
struct _jdoubleArray { double v[64]; int n; };
typedef _jdoubleArray* jdoubleArray;
struct _jstring { std::string s; };
typedef _jstring* jstring;
#define JNIEXPORT
#define JNICALL
struct JNIEnv {
    int arrays = 0;
    jdoubleArray NewDoubleArray(int n) { if (n < 0 || n > 64) return nullptr; arrays++; auto* a = new _jdoubleArray(); a->n = n; return a; }
    void SetDoubleArrayRegion(jdoubleArray a, int start, int len, const double* src) {
        if (!a || start < 0 || len < 0 || start + len > a->n) { fprintf(stderr, "BAD SetDoubleArrayRegion %d+%d of %d\n", start, len, a ? a->n : -1); abort(); }
        memcpy(a->v + start, src, len * sizeof(double)); }
    jstring NewStringUTF(const char* s) { if (!s) { fprintf(stderr, "NULL string\n"); abort(); } return new _jstring{s}; }
};
