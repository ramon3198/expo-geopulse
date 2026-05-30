// Host unit test for the Kalman / fusion core. NOT compiled into the Android
// library (excluded from CMakeLists). Build & run on the host with g++:
//
//   g++ -std=c++17 -O2 kalman.cpp fusion.cpp test_fusion.cpp -o test_fusion
//   ./test_fusion
//
// Exit code 0 = all checks passed.

#include <cmath>
#include <cstdio>
#include <random>

#include "fusion.h"

using namespace geopulse;

namespace {

constexpr double kDegPerMeterLat = 1.0 / 111320.0;

double degPerMeterLng(double lat) {
  return 1.0 / (111320.0 * std::cos(lat * 3.14159265358979323846 / 180.0));
}

int failures = 0;

void check(bool cond, const char* name) {
  std::printf("  [%s] %s\n", cond ? "PASS" : "FAIL", name);
  if (!cond) failures++;
}

// RMSE (meters) of a set of (lat,lng) points against a single true point.
struct Accumulator {
  double sumSq = 0.0;
  int n = 0;
  double trueLat, trueLng;
  explicit Accumulator(double lat, double lng) : trueLat(lat), trueLng(lng) {}
  void add(double lat, double lng) {
    double d = haversineMeters(trueLat, trueLng, lat, lng);
    sumSq += d * d;
    n++;
  }
  double rmse() const { return n ? std::sqrt(sumSq / n) : 0.0; }
};

}  // namespace

int main() {
  std::printf("== GeoPulse fusion core tests ==\n");

  // ---- Test 1: stationary point, heavy noise -> Kalman must reduce RMSE ----
  {
    const double trueLat = 37.0, trueLng = -122.0;
    const double noiseSdMeters = 12.0;
    const double reportedAccuracy = 12.0;

    std::mt19937 rng(12345);
    std::normal_distribution<double> noise(0.0, noiseSdMeters);

    FusionConfig cfg;
    cfg.enableKalman = true;
    cfg.processNoise = 1.0;  // slow -> strong smoothing for a stationary device
    SensorFusion fusion(cfg);

    Accumulator raw(trueLat, trueLng);
    Accumulator filtered(trueLat, trueLng);

    long long t = 1'000'000;
    for (int i = 0; i < 300; i++) {
      double dLat = noise(rng) * kDegPerMeterLat;
      double dLng = noise(rng) * degPerMeterLng(trueLat);
      double measLat = trueLat + dLat;
      double measLng = trueLng + dLng;
      raw.add(measLat, measLng);
      FusionOutput o = fusion.process(measLat, measLng, reportedAccuracy, t);
      if (o.accepted) filtered.add(o.latitude, o.longitude);
      t += 1000;
    }
    std::printf("  stationary RMSE: raw=%.2fm  filtered=%.2fm  (%.0f%% better)\n",
                raw.rmse(), filtered.rmse(),
                100.0 * (1.0 - filtered.rmse() / raw.rmse()));
    check(filtered.rmse() < raw.rmse() * 0.6,
          "stationary: filtered RMSE < 60% of raw RMSE");
  }

  // ---- Test 2: slow straight-line movement -> filtered still beats raw ----
  {
    const double startLat = 40.0, startLng = -3.0;
    const double speedMps = 1.4;  // walking
    const double noiseSdMeters = 10.0;
    const double reportedAccuracy = 10.0;

    std::mt19937 rng(999);
    std::normal_distribution<double> noise(0.0, noiseSdMeters);

    FusionConfig cfg;
    cfg.enableKalman = true;
    cfg.processNoise = 2.0;
    SensorFusion fusion(cfg);

    double rawSumSq = 0.0, filtSumSq = 0.0;
    int rawN = 0, filtN = 0;

    long long t = 5'000'000;
    for (int i = 0; i < 300; i++) {
      double trueLat = startLat + (speedMps * i) * kDegPerMeterLat;  // heading north
      double measLat = trueLat + noise(rng) * kDegPerMeterLat;
      double measLng = startLng + noise(rng) * degPerMeterLng(startLat);
      double dRaw = haversineMeters(trueLat, startLng, measLat, measLng);
      rawSumSq += dRaw * dRaw;
      rawN++;
      FusionOutput o = fusion.process(measLat, measLng, reportedAccuracy, t);
      if (o.accepted) {
        double dF = haversineMeters(trueLat, startLng, o.latitude, o.longitude);
        filtSumSq += dF * dF;
        filtN++;
      }
      t += 1000;
    }
    double rawRmse = std::sqrt(rawSumSq / rawN);
    double filtRmse = std::sqrt(filtSumSq / filtN);
    std::printf("  moving RMSE:     raw=%.2fm  filtered=%.2fm  (%.0f%% better)\n",
                rawRmse, filtRmse, 100.0 * (1.0 - filtRmse / rawRmse));
    check(filtRmse < rawRmse, "moving: filtered RMSE < raw RMSE");
  }

  // ---- Test 3: outlier rejection (teleport) ----
  {
    FusionConfig cfg;
    cfg.enableKalman = false;
    cfg.maxSpeedMps = 100.0;
    SensorFusion fusion(cfg);
    long long t = 0;
    FusionOutput a = fusion.process(48.8566, 2.3522, 8.0, t);          // Paris
    t += 1000;
    FusionOutput b = fusion.process(48.8570, 2.3525, 8.0, t);          // ~50m -> ok
    t += 1000;
    FusionOutput c = fusion.process(40.7128, -74.0060, 8.0, t);        // NYC, 1s later -> impossible
    check(a.accepted, "outlier: first fix accepted");
    check(b.accepted, "outlier: plausible fix accepted");
    check(!c.accepted, "outlier: teleport fix rejected");
  }

  // ---- Test 4: accuracy gate ----
  {
    FusionConfig cfg;
    cfg.enableKalman = false;
    cfg.accuracyFilter = 20.0;
    SensorFusion fusion(cfg);
    FusionOutput good = fusion.process(51.5074, -0.1278, 10.0, 0);
    FusionOutput bad = fusion.process(51.5074, -0.1278, 50.0, 1000);
    check(good.accepted, "accuracy gate: 10m fix accepted (<20m)");
    check(!bad.accepted, "accuracy gate: 50m fix rejected (>20m)");
  }

  std::printf("== %s ==\n", failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED");
  return failures == 0 ? 0 : 1;
}
