#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <csignal>
#include <unistd.h>
#include <random>
#include <limits>
#include "jni.h"
extern "C" {
jdoubleArray Java_com_atakmap_android_firecast_model_Behave_surfaceRun(JNIEnv*, jclass, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
jdouble Java_com_atakmap_android_firecast_model_Behave_spotDistance(JNIEnv*, jclass, jdouble, jdouble, jdouble);
jdoubleArray Java_com_atakmap_android_firecast_model_Behave_crownRun(JNIEnv*, jclass, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
jstring Java_com_atakmap_android_firecast_model_Behave_fuelName(JNIEnv*, jclass, jint);
}
static char last[512];
static void hang(int) { fprintf(stderr, "HANG (call over 10 s): %s\n", last); _exit(3); }
static std::mt19937_64 rng(20261031);
static const double NaN = std::numeric_limits<double>::quiet_NaN(), INF = std::numeric_limits<double>::infinity();
static double pick(int kind, double lo, double hi) {
    static const double odd[] = { NaN, INF, -INF, 0, -0.0, -1, 1e-300, -1e-300, 1e300, -1e300, 1e6, -1e6, 
        std::numeric_limits<double>::denorm_min(), std::numeric_limits<double>::max(), std::numeric_limits<double>::lowest() };
    std::uniform_real_distribution<double> u(lo, hi);
    if (kind == 0) return u(rng);                       // realistic
    if (kind == 1) return odd[rng() % (sizeof odd / sizeof *odd)];  // garbage
    return (rng() % 4 == 0) ? odd[rng() % (sizeof odd / sizeof *odd)] : u(rng) * ((rng() % 2) ? 100 : 1); // mixed, out of range
}
int main(int argc, char** argv) {
    const long N = argc > 1 ? atol(argv[1]) : 200000;
    signal(SIGALRM, hang);
    JNIEnv env;
    long calls = 0, nulls = 0, nonfinite = 0;
    // Every fuel model code the int can carry near the edges, and all 0..300.
    int fm_edges[] = { std::numeric_limits<int>::min(), -1, 0, 1, 13, 14, 90, 91, 99, 100, 101, 204, 219, 220, 221, 255, 256, 257, 1000, std::numeric_limits<int>::max() };
    for (int f = -5; f <= 300; f++) {
        alarm(10); snprintf(last, sizeof last, "fuelName(%d)", f);
        jstring s = Java_com_atakmap_android_firecast_model_Behave_fuelName(&env, nullptr, f); delete s; calls++;
    }
    for (int f : fm_edges) { alarm(10); snprintf(last, sizeof last, "fuelName(%d)", f); delete Java_com_atakmap_android_firecast_model_Behave_fuelName(&env, nullptr, f); calls++; }
    for (long i = 0; i < N; i++) {
        const int kind = i % 3;
        int fm = (i % 7 == 0) ? fm_edges[rng() % 20] : (int)(rng() % 260);
        double m1 = pick(kind, 2, 40), m10 = pick(kind, 2, 40), m100 = pick(kind, 2, 40), mh = pick(kind, 30, 300), mw = pick(kind, 30, 300);
        double fol = pick(kind, 60, 300), wind = pick(kind, 0, 80), from = pick(kind, -720, 720), slope = pick(kind, 0, 89), asp = pick(kind, -360, 720);
        double cc = pick(kind, 0, 100), ch = pick(kind, 0, 200), cbh = pick(kind, 0, 100), cbd = pick(kind, 0, 0.6), cr = pick(kind, 0, 1);
        int wh = (int)(rng() % 3) - (i % 11 == 0 ? 5 : 0);
        snprintf(last, sizeof last, "fm=%d m=%g,%g,%g,%g,%g fol=%g wind=%g wh=%d from=%g slope=%g asp=%g cc=%g ch=%g cbh=%g cbd=%g cr=%g",
                 fm, m1, m10, m100, mh, mw, fol, wind, wh, from, slope, asp, cc, ch, cbh, cbd, cr);
        alarm(10);
        jdoubleArray a = Java_com_atakmap_android_firecast_model_Behave_surfaceRun(&env, nullptr, fm, m1, m10, m100, mh, mw, wind, wh, from, slope, asp, cc, ch, cr);
        if (!a) nulls++; else { for (int k = 0; k < a->n; k++) if (!std::isfinite(a->v[k])) { nonfinite++; break; } delete a; }
        alarm(10);
        a = Java_com_atakmap_android_firecast_model_Behave_crownRun(&env, nullptr, fm, m1, m10, m100, mh, mw, fol, wind, wh, from, slope, asp, cc, ch, cbh, cbd);
        if (!a) nulls++; else { for (int k = 0; k < a->n; k++) if (!std::isfinite(a->v[k])) { nonfinite++; break; } delete a; }
        alarm(10);
        double sp = Java_com_atakmap_android_firecast_model_Behave_spotDistance(&env, nullptr, pick(kind, 0, 200), wind, ch);
        if (!std::isfinite(sp)) nonfinite++;
        calls += 3;
        if (i % 50000 == 0 && i) fprintf(stderr, "  %ld rounds...\n", i);
    }
    alarm(0);
    printf("DONE: %ld calls, %ld refused (null), %ld answers with a NaN/inf value, no crash, no hang\n", calls, nulls, nonfinite);
}
