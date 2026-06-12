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
      cvKalman_(config.processNoise),
      hasLast_(false),
      lastLat_(0.0),
      lastLng_(0.0),
      lastAccuracy_(0.0),
      lastTimeMs_(0) {}

void SensorFusion::setConfig(const FusionConfig& config) {
  cfg_ = config;
}

void SensorFusion::setMotionProfile(double processNoiseMetersPerSecond,
                                    double maxSpeedMps) {
  if (processNoiseMetersPerSecond > 0) {
    cfg_.processNoise = processNoiseMetersPerSecond;
    kalman_.setProcessNoise(processNoiseMetersPerSecond);
    cvKalman_.setProcessNoise(processNoiseMetersPerSecond);
  }
  if (maxSpeedMps > 0) cfg_.maxSpeedMps = maxSpeedMps;
}

void SensorFusion::setMinAccuracy(double minAccuracyMeters) {
  kalman_.setMinAccuracy(minAccuracyMeters);
  cvKalman_.setMinAccuracy(minAccuracyMeters);
}

void SensorFusion::reset() {
  kalman_.reset();
  cvKalman_.reset();
  hasLast_ = false;
}

FusionOutput SensorFusion::process(double latitude, double longitude,
                                   double accuracyMeters, long long timestampMs) {
  return process(latitude, longitude, accuracyMeters, timestampMs,
                 /*hasVelocity=*/false, 0.0, 0.0, -1.0);
}

FusionOutput SensorFusion::process(double latitude, double longitude,
                                   double accuracyMeters, long long timestampMs,
                                   bool hasVelocity, double speedMps,
                                   double bearingDeg, double speedAccuracyMps) {
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

  // 3) Outlier rejection (dt > 0 guaranteed by the check above). A jump is only
  //    rejected when it is BOTH (a) too large to be explained by the two fixes'
  //    reported accuracies (beyond kSigmaGate of their combined 1-sigma — with
  //    poor accuracy an apparently-fast jump is often just noise the Kalman will
  //    smooth anyway) AND (b) faster than the motion profile's max speed.
  if (hasLast_) {
    long long dtMs = timestampMs - lastTimeMs_;
    double meters = haversineMeters(lastLat_, lastLng_, latitude, longitude);
    double combined = std::sqrt(lastAccuracy_ * lastAccuracy_ +
                                accuracyMeters * accuracyMeters);
    static constexpr double kSigmaGate = 5.0;
    if (meters > kSigmaGate * combined) {
      double speed = meters / (static_cast<double>(dtMs) / 1000.0);
      if (speed > cfg_.maxSpeedMps) {
        return out;  // physically implausible jump -> reject
      }
    }
  }

  // 3) Kalman smoothing (optional): the CV model when configured (it predicts
  //    through motion and can fuse the chip's doppler velocity), else the
  //    proven scalar filter.
  if (cfg_.enableKalman) {
    FilterResult r =
        cfg_.useCvModel
            ? cvKalman_.process(latitude, longitude, accuracyMeters, timestampMs,
                                hasVelocity, speedMps, bearingDeg,
                                speedAccuracyMps)
            : kalman_.process(latitude, longitude, accuracyMeters, timestampMs);
    out.latitude = r.latitude;
    out.longitude = r.longitude;
    out.accuracy = r.accuracy;
    out.filtered = true;
  }

  out.accepted = true;
  hasLast_ = true;
  lastLat_ = latitude;
  lastLng_ = longitude;
  lastAccuracy_ = accuracyMeters;
  lastTimeMs_ = timestampMs;
  return out;
}

}  // namespace geopulse
