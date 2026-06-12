#pragma once

#include "kalman.h"  // FilterResult

namespace geopulse {

/**
 * Constant-velocity GPS Kalman filter.
 *
 * Upgrades the scalar (position-only) filter with a velocity state, so the
 * filter *predicts* where a moving device will be instead of always lagging
 * behind it — less smear on straights, less corner-cutting in turns. It can
 * also ingest the GPS chip's measured velocity (speed + bearing), which the
 * scalar model has no way to use.
 *
 * Internally the 2D constant-velocity model decomposes into two independent
 * 2-state filters (position, velocity) — one per local axis — because the
 * transition, process-noise and measurement matrices are all block-diagonal.
 * That removes every 4x4 operation: each update is a couple of scalar ops,
 * numerically stable by construction.
 *
 * Positions are filtered in a local east/north meter frame anchored at the
 * first fix (re-anchored on reset), which keeps the math in well-scaled units.
 */
class GpsKalmanFilterCV {
 public:
  explicit GpsKalmanFilterCV(double accelNoiseMps2 = 3.0);

  void reset();
  bool isInitialized() const { return initialized_; }

  /** Update the process (acceleration) noise at runtime; ignores values <= 0. */
  void setProcessNoise(double accelNoiseMps2) {
    if (accelNoiseMps2 > 0) qa_ = accelNoiseMps2;
  }

  /** Floor on reported accuracy (see GpsKalmanFilter::setMinAccuracy). */
  void setMinAccuracy(double minAccuracyMeters) {
    if (minAccuracyMeters > 0) minAccuracy_ = minAccuracyMeters;
  }

  /**
   * Feed a raw fix. When [hasVelocity], [speedMps] / [bearingDeg] (compass
   * degrees, 0 = north) / [speedAccuracyMps] (pass <= 0 for an estimate) are
   * fused as a velocity measurement after the position update.
   */
  FilterResult process(double latitude, double longitude, double accuracyMeters,
                       long long timestampMs, bool hasVelocity = false,
                       double speedMps = 0.0, double bearingDeg = 0.0,
                       double speedAccuracyMps = -1.0);

 private:
  // One axis of the decomposed model: state [position x (m), velocity v (m/s)]
  // with 2x2 covariance P.
  struct Axis {
    double x, v;
    double p00, p01, p11;  // symmetric covariance
    void seed(double pos, double posVar);
    void predict(double dt, double qa);
    void updatePosition(double meas, double measVar);
    void updateVelocity(double meas, double measVar);
  };

  double qa_;           // white-acceleration noise (m/s^2)
  double minAccuracy_;  // floor on reported accuracy (m)
  bool initialized_;
  long long lastTimeMs_;
  double anchorLat_, anchorLng_, metersPerDegLng_;
  Axis east_, north_;
};

}  // namespace geopulse
