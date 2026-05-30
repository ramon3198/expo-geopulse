#pragma once

#include "kalman.h"

namespace geopulse {

struct FusionConfig {
  bool enableKalman = true;
  double accuracyFilter = 0.0;    // drop fixes worse than this (m); 0 = keep all
  double maxSpeedMps = 100.0;     // reject fixes implying a speed above this (m/s)
  double processNoise = 3.0;      // Kalman process noise (m/s)
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

  FusionOutput process(double latitude, double longitude, double accuracyMeters,
                       long long timestampMs);

 private:
  FusionConfig cfg_;
  GpsKalmanFilter kalman_;
  bool hasLast_;
  double lastLat_;
  double lastLng_;
  long long lastTimeMs_;
};

/** Great-circle distance between two coordinates, in meters. */
double haversineMeters(double lat1, double lon1, double lat2, double lon2);

}  // namespace geopulse
