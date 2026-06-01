#include "fusion.h"

#include <cmath>

namespace geopulse {

static constexpr double kEarthRadiusMeters = 6371000.0;
static constexpr double kPi = 3.14159265358979323846;

double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
  double dLat = (lat2 - lat1) * kPi / 180.0;
  double dLon = (lon2 - lon1) * kPi / 180.0;
  double a = std::sin(dLat / 2.0) * std::sin(dLat / 2.0) +
             std::cos(lat1 * kPi / 180.0) * std::cos(lat2 * kPi / 180.0) *
                 std::sin(dLon / 2.0) * std::sin(dLon / 2.0);
  double c = 2.0 * std::atan2(std::sqrt(a), std::sqrt(1.0 - a));
  return kEarthRadiusMeters * c;
}

SensorFusion::SensorFusion(const FusionConfig& config)
    : cfg_(config),
      kalman_(config.processNoise),
      hasLast_(false),
      lastLat_(0.0),
      lastLng_(0.0),
      lastTimeMs_(0) {}

void SensorFusion::setConfig(const FusionConfig& config) {
  cfg_ = config;
}

void SensorFusion::reset() {
  kalman_.reset();
  hasLast_ = false;
}

FusionOutput SensorFusion::process(double latitude, double longitude,
                                   double accuracyMeters, long long timestampMs) {
  FusionOutput out{false, false, latitude, longitude, accuracyMeters};

  // 0) Reject non-finite inputs. A single NaN/Inf would otherwise poison the
  //    Kalman state forever (every later `x += gain*(v - NaN)` stays NaN).
  if (!std::isfinite(latitude) || !std::isfinite(longitude) ||
      !std::isfinite(accuracyMeters)) {
    return out;  // rejected
  }

  // 1) Accuracy gate.
  if (cfg_.accuracyFilter > 0.0 && accuracyMeters > cfg_.accuracyFilter) {
    return out;  // rejected
  }

  // 2) Reject duplicate / out-of-order fixes (timestamp not advancing). Without
  //    this, a dt<=0 fix collapses the Kalman variance (false over-confidence)
  //    and bypasses the speed gate while corrupting the reference point.
  if (hasLast_ && timestampMs <= lastTimeMs_) {
    return out;  // rejected
  }

  // 3) Speed-based outlier rejection (dt > 0 guaranteed by the check above).
  if (hasLast_) {
    long long dtMs = timestampMs - lastTimeMs_;
    double meters = haversineMeters(lastLat_, lastLng_, latitude, longitude);
    double speed = meters / (static_cast<double>(dtMs) / 1000.0);
    if (speed > cfg_.maxSpeedMps) {
      return out;  // physically implausible jump -> reject
    }
  }

  // 3) Kalman smoothing (optional).
  if (cfg_.enableKalman) {
    FilterResult r = kalman_.process(latitude, longitude, accuracyMeters, timestampMs);
    out.latitude = r.latitude;
    out.longitude = r.longitude;
    out.accuracy = r.accuracy;
    out.filtered = true;
  }

  out.accepted = true;
  hasLast_ = true;
  lastLat_ = latitude;
  lastLng_ = longitude;
  lastTimeMs_ = timestampMs;
  return out;
}

}  // namespace geopulse
