#include "kalman.h"

#include <cmath>

namespace geopulse {

GpsKalmanFilter::GpsKalmanFilter(double processNoiseMetersPerSecond)
    : q_(processNoiseMetersPerSecond > 0 ? processNoiseMetersPerSecond : 3.0),
      minAccuracy_(1.0),
      lastTimeMs_(0),
      lat_(0.0),
      lng_(0.0),
      variance_(-1.0) {}

void GpsKalmanFilter::reset() { variance_ = -1.0; }

FilterResult GpsKalmanFilter::process(double latitude, double longitude,
                                      double accuracyMeters, long long timestampMs) {
  double accuracy = accuracyMeters < minAccuracy_ ? minAccuracy_ : accuracyMeters;

  if (variance_ < 0.0) {
    // First fix: seed state directly.
    lastTimeMs_ = timestampMs;
    lat_ = latitude;
    lng_ = longitude;
    variance_ = accuracy * accuracy;
  } else {
    // Predict: grow uncertainty with elapsed time.
    long long dtMs = timestampMs - lastTimeMs_;
    if (dtMs > 0) {
      variance_ += static_cast<double>(dtMs) * q_ * q_ / 1000.0;
      lastTimeMs_ = timestampMs;
    }
    // Update: blend prediction with measurement (scalar Kalman gain).
    double gain = variance_ / (variance_ + accuracy * accuracy);
    lat_ += gain * (latitude - lat_);
    lng_ += gain * (longitude - lng_);
    variance_ = (1.0 - gain) * variance_;
  }

  return FilterResult{lat_, lng_, std::sqrt(variance_)};
}

}  // namespace geopulse
