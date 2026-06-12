#include "kalman_cv.h"

#include <cmath>

namespace geopulse {

namespace {
constexpr double kMetersPerDegLat = 111320.0;
constexpr double kPi = 3.14159265358979323846;
}  // namespace

GpsKalmanFilterCV::GpsKalmanFilterCV(double accelNoiseMps2)
    : qa_(accelNoiseMps2 > 0 ? accelNoiseMps2 : 3.0),
      minAccuracy_(1.0),
      initialized_(false),
      lastTimeMs_(0),
      anchorLat_(0.0),
      anchorLng_(0.0),
      metersPerDegLng_(kMetersPerDegLat),
      east_{},
      north_{} {}

void GpsKalmanFilterCV::reset() { initialized_ = false; }

void GpsKalmanFilterCV::Axis::seed(double pos, double posVar) {
  x = pos;
  v = 0.0;
  p00 = posVar;
  p01 = 0.0;
  // Unknown initial velocity: generous variance so the first few fixes (or a
  // velocity measurement) pin it down quickly.
  p11 = 100.0;
}

void GpsKalmanFilterCV::Axis::predict(double dt, double qa) {
  // x' = x + v*dt; white-acceleration process noise (discretized).
  x += v * dt;
  const double q = qa * qa;
  const double dt2 = dt * dt;
  p00 += 2.0 * dt * p01 + dt2 * p11 + q * dt2 * dt2 / 4.0;
  p01 += dt * p11 + q * dt2 * dt / 2.0;
  p11 += q * dt2;
}

void GpsKalmanFilterCV::Axis::updatePosition(double meas, double measVar) {
  // H = [1 0]
  const double s = p00 + measVar;
  const double k0 = p00 / s;
  const double k1 = p01 / s;
  const double innovation = meas - x;
  x += k0 * innovation;
  v += k1 * innovation;
  const double p00n = (1.0 - k0) * p00;
  const double p01n = (1.0 - k0) * p01;
  p11 -= k1 * p01;
  p00 = p00n;
  p01 = p01n;
}

void GpsKalmanFilterCV::Axis::updateVelocity(double meas, double measVar) {
  // H = [0 1]
  const double s = p11 + measVar;
  const double k0 = p01 / s;
  const double k1 = p11 / s;
  const double innovation = meas - v;
  x += k0 * innovation;
  v += k1 * innovation;
  const double p00n = p00 - k0 * p01;
  const double p01n = (1.0 - k1) * p01;
  p11 = (1.0 - k1) * p11;
  p00 = p00n;
  p01 = p01n;
}

FilterResult GpsKalmanFilterCV::process(double latitude, double longitude,
                                        double accuracyMeters,
                                        long long timestampMs, bool hasVelocity,
                                        double speedMps, double bearingDeg,
                                        double speedAccuracyMps) {
  const double accuracy =
      accuracyMeters < minAccuracy_ ? minAccuracy_ : accuracyMeters;
  const double posVar = accuracy * accuracy;

  if (!initialized_) {
    // Seed: anchor the local east/north meter frame at the first fix.
    anchorLat_ = latitude;
    anchorLng_ = longitude;
    metersPerDegLng_ = kMetersPerDegLat * std::cos(latitude * kPi / 180.0);
    if (metersPerDegLng_ < 1.0) metersPerDegLng_ = 1.0;  // poles: degenerate
    east_.seed(0.0, posVar);
    north_.seed(0.0, posVar);
    initialized_ = true;
    lastTimeMs_ = timestampMs;
    return FilterResult{latitude, longitude, accuracy};
  }

  const long long dtMs = timestampMs - lastTimeMs_;
  if (dtMs > 0) {
    const double dt = static_cast<double>(dtMs) / 1000.0;
    east_.predict(dt, qa_);
    north_.predict(dt, qa_);
    lastTimeMs_ = timestampMs;
  }

  const double e = (longitude - anchorLng_) * metersPerDegLng_;
  const double n = (latitude - anchorLat_) * kMetersPerDegLat;
  east_.updatePosition(e, posVar);
  north_.updatePosition(n, posVar);

  if (hasVelocity && speedMps >= 0.0) {
    // The GPS chip's doppler velocity is far less noisy than differentiated
    // positions — fuse it so the velocity state tracks reality, not jitter.
    const double b = bearingDeg * kPi / 180.0;
    double sigmaV = speedAccuracyMps;
    if (sigmaV <= 0.0) sigmaV = 1.0 + 0.1 * speedMps;  // conservative estimate
    const double velVar = sigmaV * sigmaV;
    east_.updateVelocity(speedMps * std::sin(b), velVar);
    north_.updateVelocity(speedMps * std::cos(b), velVar);
  }

  const double outLat = anchorLat_ + north_.x / kMetersPerDegLat;
  const double outLng = anchorLng_ + east_.x / metersPerDegLng_;
  const double outAcc = std::sqrt((east_.p00 + north_.p00) / 2.0);
  return FilterResult{outLat, outLng, outAcc};
}

}  // namespace geopulse
