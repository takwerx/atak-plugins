#include <cstdio>
#include <cmath>
#include <csignal>
#include <unistd.h>
#include <random>
#include "jni.h"
extern "C" {
jdoubleArray Java_com_atakmap_android_firecast_model_Behave_surfaceRun(JNIEnv*, jclass, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
jdoubleArray Java_com_atakmap_android_firecast_model_Behave_crownRun(JNIEnv*, jclass, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
jdouble Java_com_atakmap_android_firecast_model_Behave_spotDistance(JNIEnv*, jclass, jdouble, jdouble, jdouble);
}
static char last[512];
static void hang(int) { fprintf(stderr, "HANG: %s\n", last); _exit(3); }
int main() {
    signal(SIGALRM, hang);
    JNIEnv env;
    const int fuels[] = {1,2,3,4,5,6,7,8,9,10,11,12,13,101,102,103,104,105,106,107,108,109,121,122,123,124,141,142,143,144,145,146,147,148,149,161,162,163,164,165,181,182,183,184,185,186,187,188,189,201,202,203,204,220};
    std::mt19937_64 rng(7);
    long realCalls = 0, realRefused = 0, edgeCalls = 0, edgeRefused = 0, bad = 0;
    for (int f : fuels) {
        for (int i = 0; i < 4000; i++) {   // realistic: what the plugin sends
            std::uniform_real_distribution<double> m(2, 40), live(30, 300), w(0, 60), d(0, 360), sl(0, 60), cc(0, 100), ch(0, 150), cb(0, 30), bd(0.01, 0.4), fo(60, 300);
            double h = ch(rng), base = std::min(h, cb(rng));
            int wh = i % 3;
            snprintf(last, sizeof last, "real fm=%d", f);
            alarm(10);
            jdoubleArray a = Java_com_atakmap_android_firecast_model_Behave_surfaceRun(&env, nullptr, f, m(rng), m(rng), m(rng), live(rng), live(rng), w(rng), wh, d(rng), sl(rng), d(rng), cc(rng), h, 0.5);
            realCalls++; if (!a) realRefused++; else { for (int k = 0; k < a->n; k++) if (!std::isfinite(a->v[k])) { bad++; break; } delete a; }
            a = Java_com_atakmap_android_firecast_model_Behave_crownRun(&env, nullptr, f, m(rng), m(rng), m(rng), live(rng), live(rng), fo(rng), w(rng), wh, d(rng), sl(rng), d(rng), cc(rng), h, base, bd(rng));
            realCalls++; if (!a) realRefused++; else { for (int k = 0; k < a->n; k++) if (!std::isfinite(a->v[k])) { bad++; break; } delete a; }
        }
        // the edges of the limits: everything at its bound at once
        const double M[] = {0.5, 500}, W[] = {0, 200}, S[] = {0, 90}, C[] = {0, 100}, H[] = {0, 500}, B[] = {0, 2}, F[] = {30, 500};
        for (int mask = 0; mask < 128; mask++) {
            double mm = M[mask & 1], ww = W[(mask >> 1) & 1], ss = S[(mask >> 2) & 1], cc2 = C[(mask >> 3) & 1], hh = H[(mask >> 4) & 1], bb = B[(mask >> 5) & 1], ff = F[(mask >> 6) & 1];
            snprintf(last, sizeof last, "edge fm=%d m=%g wind=%g slope=%g cc=%g h=%g cbd=%g fol=%g", f, mm, ww, ss, cc2, hh, bb, ff);
            alarm(10);
            jdoubleArray a = Java_com_atakmap_android_firecast_model_Behave_surfaceRun(&env, nullptr, f, mm, mm, mm, mm, mm, ww, 1, 270, ss, 180, cc2, hh, 1);
            edgeCalls++; if (!a) edgeRefused++; else delete a;
            a = Java_com_atakmap_android_firecast_model_Behave_crownRun(&env, nullptr, f, mm, mm, mm, mm, mm, ff, ww, 1, 270, ss, 180, cc2, hh, hh / 2, bb);
            edgeCalls++; if (!a) edgeRefused++; else delete a;
            Java_com_atakmap_android_firecast_model_Behave_spotDistance(&env, nullptr, 1000 * (mask & 1), ww, hh);
        }
    }
    alarm(0);
    printf("realistic: %ld calls, %ld refused, %ld with NaN/inf out\nat the limits: %ld calls, %ld refused, no hang\n", realCalls, realRefused, bad, edgeCalls, edgeRefused);
}
