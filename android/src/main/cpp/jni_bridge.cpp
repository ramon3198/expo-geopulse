#include <jni.h>

#include "fusion.h"

using geopulse::FusionConfig;
using geopulse::FusionOutput;
using geopulse::SensorFusion;

extern "C" {

JNIEXPORT jlong JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeCreate(
    JNIEnv*, jobject, jboolean enableKalman, jboolean useCvModel,
    jdouble accuracyFilter, jdouble maxSpeed, jdouble processNoise) {
  FusionConfig cfg;
  cfg.enableKalman = enableKalman == JNI_TRUE;
  cfg.useCvModel = useCvModel == JNI_TRUE;
  cfg.accuracyFilter = accuracyFilter;
  cfg.maxSpeedMps = maxSpeed;
  cfg.processNoise = processNoise;
  return reinterpret_cast<jlong>(new SensorFusion(cfg));
}

JNIEXPORT void JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeReset(JNIEnv*, jobject, jlong handle) {
  auto* fusion = reinterpret_cast<SensorFusion*>(handle);
  if (fusion != nullptr) fusion->reset();
}

JNIEXPORT void JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeSetMotionProfile(
    JNIEnv*, jobject, jlong handle, jdouble processNoise, jdouble maxSpeed) {
  auto* fusion = reinterpret_cast<SensorFusion*>(handle);
  if (fusion != nullptr) fusion->setMotionProfile(processNoise, maxSpeed);
}

JNIEXPORT void JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeSetMinAccuracy(
    JNIEnv*, jobject, jlong handle, jdouble minAccuracy) {
  auto* fusion = reinterpret_cast<SensorFusion*>(handle);
  if (fusion != nullptr) fusion->setMinAccuracy(minAccuracy);
}

JNIEXPORT void JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
  auto* fusion = reinterpret_cast<SensorFusion*>(handle);
  delete fusion;
}

/**
 * Returns a double[5]: { accepted, filtered, latitude, longitude, accuracy }.
 * [hasVelocity] marks speed/bearing/speedAccuracy as valid doppler data for the
 * CV model (ignored by the scalar filter).
 */
JNIEXPORT jdoubleArray JNICALL
Java_expo_modules_geopulse_fusion_KalmanBridge_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jdouble latitude, jdouble longitude,
    jdouble accuracy, jlong timestampMs, jboolean hasVelocity, jdouble speedMps,
    jdouble bearingDeg, jdouble speedAccuracyMps) {
  auto* fusion = reinterpret_cast<SensorFusion*>(handle);
  jdouble result[5];
  if (fusion == nullptr) {
    result[0] = 0.0;  // not accepted
    result[1] = 0.0;
    result[2] = latitude;
    result[3] = longitude;
    result[4] = accuracy;
  } else {
    FusionOutput out =
        fusion->process(latitude, longitude, accuracy, timestampMs,
                        hasVelocity == JNI_TRUE, speedMps, bearingDeg,
                        speedAccuracyMps);
    result[0] = out.accepted ? 1.0 : 0.0;
    result[1] = out.filtered ? 1.0 : 0.0;
    result[2] = out.latitude;
    result[3] = out.longitude;
    result[4] = out.accuracy;
  }
  jdoubleArray array = env->NewDoubleArray(5);
  if (array == nullptr) return nullptr;  // OOM: a pending exception is set
  env->SetDoubleArrayRegion(array, 0, 5, result);
  return array;
}

}  // extern "C"
