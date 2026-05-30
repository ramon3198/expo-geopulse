#pragma once

namespace geopulse {

/** Smoothed position plus the filter's current 1-sigma accuracy estimate (meters). */
struct FilterResult {
  double latitude;
  double longitude;
  double accuracy;
};

/**
 * Classic scalar Kalman filter for GPS coordinates.
 *
 * Tracks position with a single variance (in meters^2) that grows with elapsed
 * time (process noise, driven by an assumed max speed) and shrinks when a new
 * fix arrives (measurement update weighted by the fix's reported accuracy).
 *
 * This is the well-established "GPS Kalman" used by many production trackers:
 * cheap (no matrix math), robust, and very effective at removing jitter and
 * tightening accuracy when the device is slow or stationary.
 */
class GpsKalmanFilter {
 public:
  explicit GpsKalmanFilter(double processNoiseMetersPerSecond = 3.0);

  void reset();
  bool isInitialized() const { return variance_ >= 0.0; }

  /**
   * Feed a raw fix. `timestampMs` should be monotonic. `accuracyMeters` is the
   * fix's reported horizontal accuracy. Returns the smoothed position.
   */
  FilterResult process(double latitude, double longitude, double accuracyMeters,
                       long long timestampMs);

 private:
  double q_;            // process noise (m/s)
  double minAccuracy_;  // floor on reported accuracy (m)
  long long lastTimeMs_;
  double lat_;
  double lng_;
  double variance_;     // P, in meters^2; < 0 means uninitialized
};

}  // namespace geopulse
