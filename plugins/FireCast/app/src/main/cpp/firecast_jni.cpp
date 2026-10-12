// JNI over the Fire Lab's behave surface fire module. One call in, one array out.
#include <jni.h>
#include <string>
#include "fuelModels.h"
#include "surface.h"

namespace {
FuelModels& fuelModels() {
    static FuelModels fm;   // the standard 13 + 40 + the extras, built once
    return fm;
}
}

extern "C" {

// Inputs, in the order the Java side sends them:
//   fuelModel  FBFM40/13 number (LANDFIRE's own codes: 101 GR1 ... 204 SB4)
//   m1, m10, m100, mHerb, mWoody   percent
//   windMph    wind speed, miles per hour, at 20 ft (windHeight 0) or 10 m (windHeight 1)
//   windFrom   degrees clockwise from north the wind comes FROM (probed 2026-10-11:
//              wind from 0 on flat ground sends the fire toward 180)
//   slopeDeg, aspectDeg  degrees
//   coverPct, heightFt, crownRatio   canopy
// Outputs: [ ros ft/min, direction of max spread deg, length/width, flame length ft,
//            fireline intensity BTU/ft/s, A ft per min, B ft per min, C ft per min,
//            backing ros ft/min, flanking ros ft/min, midflame wind mph ]
// A, B, C are the ellipse's semi-minor, semi-major and center-to-focus distances
// per minute of spread; the ignition sits at the rear focus.
JNIEXPORT jdoubleArray JNICALL
Java_com_atakmap_android_firecast_model_Behave_surfaceRun(JNIEnv* env, jclass,
        jint fuelModel, jdouble m1, jdouble m10, jdouble m100, jdouble mHerb, jdouble mWoody,
        jdouble windMph, jint windHeight, jdouble windFrom, jdouble slopeDeg, jdouble aspectDeg,
        jdouble coverPct, jdouble heightFt, jdouble crownRatio) {
    // The fuel model table has 256 slots and the library indexes it unchecked.
    if (fuelModel <= 0 || fuelModel >= 256)
        return nullptr;
    jdoubleArray out = env->NewDoubleArray(11);
    if (out == nullptr)
        return nullptr;
    double v[11] = { 0 };
    try {
        Surface s(fuelModels());
        s.updateSurfaceInputs(fuelModel, m1, m10, m100, mHerb, mWoody, FractionUnits::Percent,
                windMph, SpeedUnits::MilesPerHour,
                windHeight == 1 ? WindHeightInputMode::TenMeter : WindHeightInputMode::TwentyFoot,
                windFrom, WindAndSpreadOrientationMode::RelativeToNorth,
                slopeDeg, SlopeUnits::Degrees, aspectDeg,
                coverPct, FractionUnits::Percent, heightFt, LengthUnits::Feet,
                crownRatio, FractionUnits::Fraction);
        s.doSurfaceRunInDirectionOfMaxSpread();
        v[0] = s.getSpreadRate(SpeedUnits::FeetPerMinute);
        v[1] = s.getDirectionOfMaxSpread();
        v[2] = s.getFireLengthToWidthRatio();
        v[3] = s.getFlameLength(LengthUnits::Feet);
        v[4] = s.getFirelineIntensity(FirelineIntensityUnits::BtusPerFootPerSecond);
        v[5] = s.getEllipticalA(LengthUnits::Feet, 1, TimeUnits::Minutes);
        v[6] = s.getEllipticalB(LengthUnits::Feet, 1, TimeUnits::Minutes);
        v[7] = s.getEllipticalC(LengthUnits::Feet, 1, TimeUnits::Minutes);
        v[8] = s.getBackingSpreadRate(SpeedUnits::FeetPerMinute);
        v[9] = s.getFlankingSpreadRate(SpeedUnits::FeetPerMinute);
        v[10] = s.getMidflameWindspeed(SpeedUnits::MilesPerHour);
    } catch (...) {
        return nullptr;
    }
    env->SetDoubleArrayRegion(out, 0, 11, v);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_atakmap_android_firecast_model_Behave_fuelName(JNIEnv* env, jclass, jint fuelModel) {
    if (fuelModel <= 0 || fuelModel >= 256)
        return env->NewStringUTF("");
    try {
        FuelModels& fm = fuelModels();
        if (!fm.isFuelModelDefined(fuelModel))
            return env->NewStringUTF("");
        const std::string s = fm.getFuelCode(fuelModel) + " " + fm.getFuelName(fuelModel);
        return env->NewStringUTF(s.c_str());
    } catch (...) {
        return env->NewStringUTF("");
    }
}

}
