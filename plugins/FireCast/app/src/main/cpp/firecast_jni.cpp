// JNI over the Fire Lab's behave surface and crown fire modules. One call in, one array out.
#include <jni.h>
#include <string>
#include <algorithm>
#include <cmath>
#include "fuelModels.h"
#include "surface.h"
#include "crown.h"
#include "spot.h"
#include "CrownFirebrandProcessor.h"

namespace {
// Fuel model 220, "BU1", burnable urban: Moran, Seielstad and Pietruszka 2025
// (Int. J. Wildland Fire, WF24132), fitted to the mean 0.81 km/h spread of nine
// North American conflagrations through neighborhoods. 1 h 5.0 Mg/ha, live herb
// 0.5, live woody 27.0, surface-area-to-volume 13,120 /m for every class, 20 cm
// deep, 12% moisture of extinction, 20,000 J/g. Its wind adjustment comes out at
// 0.335 from the depth, which is the paper's figure. Static, so it does not drift.
const int BU1 = 220;

FuelModels& fuelModels() {
    static FuelModels fm;   // the standard 13 + 40 + the extras, built once
    static bool custom = false;
    if (!custom) {
        custom = true;
        fm.setCustomFuelModel(BU1, "BU1", "Burnable urban, house to house (Moran 2025)",
                20.0, LengthUnits::Centimeters,
                12.0, FractionUnits::Percent,
                20000.0, 20000.0, HeatOfCombustionUnits::KilojoulesPerKilogram,
                5.0, 0.0, 0.0, 0.5, 27.0, LoadingUnits::TonnesPerHectare,
                13120.0, 13120.0, 13120.0, SurfaceAreaToVolumeUnits::SquareMetersOverCubicMeters,
                false);
    }
    return fm;
}

// The surface fire with the canopy over it, through behave's crown module: Scott and
// Reinhardt (2001), the linked models FlamMap and Behave7 use. Surface for the heading
// and the ellipse; the crown run decides surface, torching or crowning and gives the
// final spread rate and flame. A crowning fire takes the crown fire's own length to
// width (Rothermel 1991, from the 20 ft wind); a torching one keeps the surface shape
// at the faster rate. Embers: Albini's active crown firebrands when it crowns, his
// surface fire model with the final flame otherwise.
//   out: 0 spread rate ft/min, 1 heading deg, 2 A, 3 B, 4 C (ft per minute), 5 flame ft,
//        6 fire type (0 surface, 1 torching, 2 conditional crown, 3 crowning),
//        7 crown fraction burned, 8 fireline intensity BTU/ft/s, 9 spot distance m
// Returns false when behave threw.
// Every number the Java side sends is checked here, before behave sees it. behave
// assumes physical inputs: a NaN, an infinity or a wind of 1e308 sent its solver
// into a loop that never ends (found by fuzzing the shipped bridge on the Mac,
// 2026-10-31). Real inputs sit far inside these limits; anything outside them is
// refused and the Java side gets no run.
bool within(double v, double lo, double hi) {
    return std::isfinite(v) && v >= lo && v <= hi;
}

bool surfaceInputsOk(int fuelModel, double m1, double m10, double m100, double mHerb, double mWoody,
        double windMph, int windHeight, double windFrom, double slopeDeg, double aspectDeg,
        double coverPct, double heightFt, double crownRatio) {
    return fuelModel > 0 && fuelModel < 256
            && within(m1, 0.5, 500) && within(m10, 0.5, 500) && within(m100, 0.5, 500)
            && within(mHerb, 0.5, 500) && within(mWoody, 0.5, 500)
            && within(windMph, 0, 200) && (windHeight == 0 || windHeight == 1)
            && within(windFrom, -3600, 3600) && within(slopeDeg, 0, 90) && within(aspectDeg, -3600, 3600)
            && within(coverPct, 0, 100) && within(heightFt, 0, 500) && within(crownRatio, 0, 1);
}

bool crownRun(double out[10], int fuelModel, double m1, double m10, double m100, double mHerb, double mWoody,
        double mFoliar, double windMph, int windHeight, double windFrom, double slopeDeg, double aspectDeg,
        double coverPct, double heightFt, double baseFt, double density) {
    if (!surfaceInputsOk(fuelModel, m1, m10, m100, mHerb, mWoody, windMph, windHeight, windFrom, slopeDeg,
            aspectDeg, coverPct, heightFt, 0) || !within(mFoliar, 30, 500) || !within(baseFt, 0, 500)
            || !within(density, 0, 2))
        return false;
    try {
        const double crownRatio = heightFt > 0 ? std::max(0.0, std::min(1.0, (heightFt - baseFt) / heightFt)) : 0.0;
        const WindHeightInputMode::WindHeightInputModeEnum wh =
                windHeight == 1 ? WindHeightInputMode::TenMeter : WindHeightInputMode::TwentyFoot;
        Surface s(fuelModels());
        s.updateSurfaceInputs(fuelModel, m1, m10, m100, mHerb, mWoody, FractionUnits::Percent,
                windMph, SpeedUnits::MilesPerHour, wh,
                windFrom, WindAndSpreadOrientationMode::RelativeToNorth,
                slopeDeg, SlopeUnits::Degrees, aspectDeg,
                coverPct, FractionUnits::Percent, heightFt, LengthUnits::Feet,
                crownRatio, FractionUnits::Fraction);
        s.doSurfaceRunInDirectionOfMaxSpread();
        const double surfaceRos = s.getSpreadRate(SpeedUnits::FeetPerMinute);
        double a = s.getEllipticalA(LengthUnits::Feet, 1, TimeUnits::Minutes);
        double b = s.getEllipticalB(LengthUnits::Feet, 1, TimeUnits::Minutes);
        double c = s.getEllipticalC(LengthUnits::Feet, 1, TimeUnits::Minutes);

        Crown cr(fuelModels());
        cr.updateCrownInputs(fuelModel, m1, m10, m100, mHerb, mWoody, mFoliar, FractionUnits::Percent,
                windMph, SpeedUnits::MilesPerHour, wh,
                windFrom, WindAndSpreadOrientationMode::RelativeToNorth,
                slopeDeg, SlopeUnits::Degrees, aspectDeg,
                coverPct, FractionUnits::Percent, heightFt, baseFt, LengthUnits::Feet,
                crownRatio, FractionUnits::Fraction,
                density, DensityUnits::KilogramsPerCubicMeter);
        cr.doCrownRunScottAndReinhardt();
        const int type = (int) cr.getFireType();
        double ros = cr.getFinalSpreadRate(SpeedUnits::FeetPerMinute);
        if (!(ros >= 0) || std::isinf(ros))
            ros = surfaceRos;
        if (type == (int) FireType::Crowning) {
            const double lw = std::max(1.0, cr.getCrownFireLengthToWidthRatio());
            const double e = std::sqrt(std::max(0.0, 1.0 - 1.0 / (lw * lw)));
            b = ros / (1.0 + e);
            c = e * b;
            a = b / lw;
        } else if (surfaceRos > 0) {
            const double k = ros / surfaceRos;
            a *= k;
            b *= k;
            c *= k;
        }
        const double flame = cr.getFinalFlameLength(LengthUnits::Feet);
        const double wind20 = windHeight == 1 ? windMph * 0.87 : windMph;
        double spotM = 0;
        if (flame > 0 && wind20 > 0) {
            Spot sp;
            const SpotDownWindCanopyMode::SpotDownWindCanopyModeEnum canopy =
                    coverPct >= 50 ? SpotDownWindCanopyMode::CLOSED : SpotDownWindCanopyMode::OPEN;
            bool crownEmbers = false;
            if (type == (int) FireType::Crowning) {
                // Albini's crown firebrands exactly as Spot computes them for an active
                // crown fire with no ridge given (flat ground), but on the stack: Spot
                // allocates the processor and never frees it. The processor's flame height
                // solve never ends once exp() of its constant overflows, which takes a
                // canopy or a wind past anything real (security review, 2026-10-17); that
                // case keeps the surface ember model below.
                const double treeM = heightFt * 0.3048 * (canopy == SpotDownWindCanopyMode::OPEN ? 0.5 : 1.0);
                const double intensity = std::pow(flame * 0.3048 / 0.0775, 1.0 / 0.46);   // Byram, from flame
                const double windKmh = wind20 * 1.609344;
                const double canopyWind = treeM > 0.1
                        ? windKmh / (3.6 * (1.0 + std::log(1.0 + 2.94 * 6.096 / treeM))) : 0.0;
                const double con = treeM > 0.1 && canopyWind > 0 ? 7.791e-03 * intensity / (treeM * canopyWind) : 0.0;
                if (con > 0 && con < 700) {
                    CrownFirebrandProcessor p(treeM, intensity, windKmh, 6.096, 0.5);
                    spotM = p.getFirebrandDistance();
                    crownEmbers = true;
                }
            }
            if (!crownEmbers) {
                sp.updateSpotInputsForSurfaceFire(SpotFireLocation::MIDSLOPE_WINDWARD,
                        0.0, LengthUnits::Miles, 0.0, LengthUnits::Feet,
                        heightFt, LengthUnits::Feet, canopy,
                        wind20, SpeedUnits::MilesPerHour, flame, LengthUnits::Feet);
                sp.calculateSpottingDistanceFromSurfaceFire();
                spotM = sp.getMaxMountainousTerrainSpottingDistanceFromSurfaceFire(LengthUnits::Meters);
            }
            if (!(spotM > 0) || std::isinf(spotM))
                spotM = 0;
        }
        out[0] = ros;
        out[1] = s.getDirectionOfMaxSpread();
        out[2] = a;
        out[3] = b;
        out[4] = c;
        out[5] = flame;
        out[6] = type;
        out[7] = cr.getCrownFractionBurned();
        out[8] = cr.getFinalFirelineIntesity(FirelineIntensityUnits::BtusPerFootPerSecond);
        out[9] = spotM;
        return true;
    } catch (...) {
        return false;
    }
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
    // The fuel model table has 256 slots and the library indexes it unchecked; every
    // other input is held to physical limits (surfaceInputsOk).
    if (!surfaceInputsOk(fuelModel, m1, m10, m100, mHerb, mWoody, windMph, windHeight, windFrom, slopeDeg,
            aspectDeg, coverPct, heightFt, crownRatio))
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

// Albini's maximum spotting distance from a wind-driven surface fire, in meters,
// on mountainous terrain (the larger figure): flame length ft, 20 ft wind mph,
// downwind cover height ft (0 for open ground). Location: midslope, windward.
JNIEXPORT jdouble JNICALL
Java_com_atakmap_android_firecast_model_Behave_spotDistance(JNIEnv*, jclass,
        jdouble flameLengthFt, jdouble windMph, jdouble coverHeightFt) {
    try {
        if (!within(flameLengthFt, 0, 1000) || !within(windMph, 0, 200) || !within(coverHeightFt, 0, 500)
                || flameLengthFt <= 0 || windMph <= 0)
            return 0;
        Spot s;
        s.updateSpotInputsForSurfaceFire(SpotFireLocation::MIDSLOPE_WINDWARD,
                0.0, LengthUnits::Miles, 0.0, LengthUnits::Feet,
                coverHeightFt, LengthUnits::Feet, SpotDownWindCanopyMode::OPEN,
                windMph, SpeedUnits::MilesPerHour, flameLengthFt, LengthUnits::Feet);
        s.calculateSpottingDistanceFromSurfaceFire();
        const double m = s.getMaxMountainousTerrainSpottingDistanceFromSurfaceFire(LengthUnits::Meters);
        return (m > 0 && m == m) ? m : 0;
    } catch (...) {
        return 0;
    }
}

// The crown run above, for the Java side: surfaceRun's inputs, then foliar moisture
// percent, canopy cover percent, canopy height ft, canopy base height ft, canopy bulk
// density kg/m3. Ten numbers out, or null.
JNIEXPORT jdoubleArray JNICALL
Java_com_atakmap_android_firecast_model_Behave_crownRun(JNIEnv* env, jclass,
        jint fuelModel, jdouble m1, jdouble m10, jdouble m100, jdouble mHerb, jdouble mWoody, jdouble mFoliar,
        jdouble windMph, jint windHeight, jdouble windFrom, jdouble slopeDeg, jdouble aspectDeg,
        jdouble coverPct, jdouble heightFt, jdouble baseFt, jdouble density) {
    // The fuel model table has 256 slots and the library indexes it unchecked.
    if (fuelModel <= 0 || fuelModel >= 256)
        return nullptr;
    double v[10] = { 0 };
    if (!crownRun(v, fuelModel, m1, m10, m100, mHerb, mWoody, mFoliar, windMph, windHeight, windFrom,
            slopeDeg, aspectDeg, coverPct, heightFt, baseFt, density))
        return nullptr;
    jdoubleArray out = env->NewDoubleArray(10);
    if (out == nullptr)
        return nullptr;
    env->SetDoubleArrayRegion(out, 0, 10, v);
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
