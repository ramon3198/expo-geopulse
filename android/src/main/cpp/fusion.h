#pragma once

#include "kalman.h"
#include "kalman_cv.h"

namespace geopulse {

struct FusionConfig {
  bool enableKalman = true;
  bool useCvModel = false;        // constant-velocity Kalman + GPS velocity fusion
  double accuracyFilter = 0.0;    // drop fixes worse than this (m); 0 = keep all
  double maxSpeedMps = 100.0;     // reject fixes implying a speed above this (m/s)
  double processNoise = 3.0;      // Kalman process noise (m/s; accel m/s^2 for CV)
};

struct FusionOutput {
  bool accepted;   // false if rejected by accuracy gate or outlier check
  bool filtered;   // true if the Kalman filter was applied
  double latitude;
  double longitude;
  double accuracy;
};

/**
 * High-level fusion pipeline: accuracy gating, speed-based outlier rejection,
 * and Kalman smoothing. All math is in C++/NDK so it is fast and reusable.
 */
class SensorFusion {
 public:
  explicit SensorFusion(const FusionConfig& config);

  void setConfig(const FusionConfig& config);
  void reset();

  /**
   * Activity-adaptive tuning (from Android activity recognition): adjust the
   * Kalman process noise and the outlier gate's max speed without rebuilding
   * the filter. Non-positive values leave the respective knob unchanged.
   */
  void setMotionProfile(double processNoiseMetersPerSecond, double maxSpeedMps);

  /** Accuracy floor for both Kalman models (see GpsKalmanFilter::setMinAccuracy). */
  void setMinAccuracy(double minAccuracyMeters);

  FusionOutput process(double latitude, double longitude, double accuracyMeters,
                       long long timestampMs);

  /**
   * Full pipeline with the fix's measured velocity (GPS doppler speed +
   * bearing), which the CV model fuses after the position update. The
   * 4-argument overload delegates here with hasVelocity = false.
   */
  FusionOutput process(double latitude, double longitude, double accuracyMeters,
                       long long timestampMs, bool hasVelocity, double speedMps,
                       double bearingDeg, double speedAccuracyMps);

 private:
  FusionConfig cfg_;
  GpsKalmanFilter kalman_;
  GpsKalmanFilterCV cvKalman_;
  bool hasLast_;
  double lastLat_;
  double lastLng_;
  double lastAccuracy_;
  long long lastTimeMs_;
};

/** Great-circle distance between two coordinates, in meters. */
double haversineMeters(double lat1, double lon1, double lat2, double lon2);

}  // namespace geopulse
