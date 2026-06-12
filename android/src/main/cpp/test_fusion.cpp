// Host unit test for the Kalman / fusion core. NOT compiled into the Android
// library (excluded from CMakeLists). Build & run on the host with g++:
//
//   g++ -std=c++17 -O2 kalman.cpp kalman_cv.cpp fusion.cpp test_fusion.cpp -o test_fusion
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

  // ---- Test 5: reset() clears state so a post-stationary fix isn't an outlier ----
  {
    FusionConfig cfg;
    cfg.enableKalman = true;
    cfg.maxSpeedMps = 100.0;
    SensorFusion fusion(cfg);
    long long t = 0;
    fusion.process(37.0, -122.0, 8.0, t);  // converge at a point
    t += 1000;
    fusion.process(37.0, -122.0, 8.0, t);

    // Movement resumes ~500 m away. As simulateLocation would inject it, the new
    // fix arrives soon after the last one processed (the 20-min stop produced no
    // fixes). Without a reset the speed gate sees 500 m / 1 s and rejects it.
    double farLat = 37.0 + 500.0 * kDegPerMeterLat;  // ~500 m north
    t += 1000;
    FusionOutput beforeReset = fusion.process(farLat, -122.0, 8.0, t);
    check(!beforeReset.accepted, "reset: a 500m/1s jump is rejected without a reset");

    // The controller calls reset() on stationary -> moving; the first fix after is
    // a fresh seed (speed gate skipped), so it is accepted, not treated as a jump.
    fusion.reset();
    t += 1000;
    FusionOutput afterReset = fusion.process(farLat, -122.0, 8.0, t);
    check(afterReset.accepted, "reset: the post-stationary fix is accepted after reset");
    check(std::abs(afterReset.latitude - farLat) < 1e-6,
          "reset: the seed fix passes through (no smoothing toward stale state)");
  }

  // ---- Test 6: activity-adaptive motion profile ----
  {
    FusionConfig cfg;
    cfg.enableKalman = false;
    cfg.maxSpeedMps = 100.0;
    SensorFusion fusion(cfg);
    long long t = 0;
    fusion.process(37.0, -122.0, 5.0, t);

    // Pedestrian profile (maxSpeed 40): a 60 m/s jump with tight accuracy is an
    // outlier. (60m apart, +/-5m accuracy: combined sigma 7.1m, gate 35m < 60m.)
    fusion.setMotionProfile(1.2, 40.0);
    t += 1000;
    double far = 37.0 + 60.0 * kDegPerMeterLat;
    FusionOutput walk = fusion.process(far, -122.0, 5.0, t);
    check(!walk.accepted, "profile: 60 m/s rejected under the pedestrian gate");

    // Vehicle profile: the same displacement is legitimate highway motion.
    fusion.setMotionProfile(8.0, 100.0);
    t += 1000;
    FusionOutput drive = fusion.process(far, -122.0, 5.0, t);
    check(drive.accepted, "profile: 60 m/s accepted under the vehicle gate");

    // Adaptive process noise: with the SAME noise sequence, "still" tuning
    // (q=0.5) must beat the default (q=3.0) on a stationary target.
    auto stationaryRmse = [](double q) {
      std::mt19937 rng(777);
      std::normal_distribution<double> noise(0.0, 10.0);
      FusionConfig kcfg;
      kcfg.enableKalman = true;
      SensorFusion f(kcfg);
      f.setMotionProfile(q, 40.0);
      Accumulator acc(37.0, -122.0);
      long long ts = 1'000'000;
      for (int i = 0; i < 200; i++) {
        double dLat = noise(rng) * kDegPerMeterLat;
        double dLng = noise(rng) * degPerMeterLng(37.0);
        FusionOutput o = f.process(37.0 + dLat, -122.0 + dLng, 10.0, ts);
        if (o.accepted) acc.add(o.latitude, o.longitude);
        ts += 1000;
      }
      return acc.rmse();
    };
    double rmseStill = stationaryRmse(0.5);
    double rmseDefault = stationaryRmse(3.0);
    std::printf("  still-profile RMSE: q=0.5 -> %.2fm vs q=3.0 -> %.2fm\n",
                rmseStill, rmseDefault);
    check(rmseStill < rmseDefault * 0.75,
          "profile: q=0.5 beats default q=3.0 by >25% when stationary");
  }

  // ---- Test 7: sigma gate — noise-plausible jumps are not outliers ----
  {
    FusionConfig cfg;
    cfg.enableKalman = false;
    cfg.maxSpeedMps = 30.0;  // tight gate to expose the old behavior
    SensorFusion fusion(cfg);
    long long t = 0;
    fusion.process(51.5, -0.12, 25.0, t);
    // 40m apparent jump in 1s = 40 m/s > maxSpeed, but both fixes report +/-25m:
    // combined sigma 35.4m, 5-sigma envelope 177m >> 40m -> plausible noise, keep.
    t += 1000;
    double jumped = 51.5 + 40.0 * kDegPerMeterLat;
    FusionOutput noisy = fusion.process(jumped, -0.12, 25.0, t);
    check(noisy.accepted, "sigma gate: 40m jump within combined accuracy accepted");

    // The same jump with tight accuracy (+/-3m: 5-sigma 21m < 40m) IS an outlier.
    SensorFusion tight(cfg);
    tight.process(51.5, -0.12, 3.0, 0);
    FusionOutput outlier = tight.process(jumped, -0.12, 3.0, 1000);
    check(!outlier.accepted, "sigma gate: same jump with tight accuracy rejected");
  }

  // ---- Test 8: constant-velocity model — less lag than scalar on a moving target ----
  {
    // Straight track heading north at 15 m/s (city driving), noisy fixes at 1 Hz.
    // The scalar filter has no velocity state, so it permanently trails the
    // target; the CV model predicts through the motion. With the chip's doppler
    // velocity fused as well, the lag should shrink further.
    const double startLat = 37.0, startLng = -122.0;
    const double speedMps = 15.0;
    const double noiseSd = 8.0, reportedAcc = 8.0;

    auto movingRmse = [&](bool useCv, bool feedVelocity) {
      std::mt19937 rng(4242);
      std::normal_distribution<double> noise(0.0, noiseSd);
      FusionConfig cfg;
      cfg.enableKalman = true;
      cfg.useCvModel = useCv;
      cfg.processNoise = 3.0;
      SensorFusion f(cfg);
      double sumSq = 0.0;
      int n = 0;
      long long t = 1'000'000;
      for (int i = 0; i < 300; i++) {
        double trueLat = startLat + (speedMps * i) * kDegPerMeterLat;
        double measLat = trueLat + noise(rng) * kDegPerMeterLat;
        double measLng = startLng + noise(rng) * degPerMeterLng(startLat);
        FusionOutput o = f.process(measLat, measLng, reportedAcc, t,
                                   /*hasVelocity=*/feedVelocity, speedMps,
                                   /*bearingDeg=*/0.0, /*speedAcc=*/0.5);
        if (o.accepted && i > 20) {  // skip convergence warm-up
          double d = haversineMeters(trueLat, startLng, o.latitude, o.longitude);
          sumSq += d * d;
          n++;
        }
        t += 1000;
      }
      return std::sqrt(sumSq / n);
    };

    double scalar = movingRmse(false, false);
    double cv = movingRmse(true, false);
    double cvVel = movingRmse(true, true);
    std::printf("  moving@15m/s RMSE: scalar=%.2fm  cv=%.2fm  cv+velocity=%.2fm\n",
                scalar, cv, cvVel);
    check(cv < scalar, "cv: less moving error than the scalar filter");
    check(cvVel < cv, "cv: fusing doppler velocity improves it further");
    check(cvVel < scalar * 0.75, "cv+velocity: >25% better than scalar while moving");

    // Stationary behavior. Without a velocity measurement the CV velocity state
    // chases position noise (a known CV trade-off, mitigated by the still
    // profile's low q). In the REAL pipeline the chip's doppler reports
    // speed ~ 0 while parked even with no bearing — fused as a zero-velocity
    // pin, which kills the wander and matches/beats the scalar filter.
    auto stillRmse = [&](bool useCv, bool feedZeroDoppler) {
      std::mt19937 rng(99);
      std::normal_distribution<double> noise(0.0, 10.0);
      FusionConfig cfg;
      cfg.enableKalman = true;
      cfg.useCvModel = useCv;
      cfg.processNoise = 1.0;
      SensorFusion f(cfg);
      Accumulator acc(startLat, startLng);
      long long t = 1'000'000;
      for (int i = 0; i < 300; i++) {
        double dLat = noise(rng) * kDegPerMeterLat;
        double dLng = noise(rng) * degPerMeterLng(startLat);
        FusionOutput o = f.process(startLat + dLat, startLng + dLng, 10.0, t,
                                   /*hasVelocity=*/feedZeroDoppler, 0.0, 0.0, 0.5);
        if (o.accepted) acc.add(o.latitude, o.longitude);
        t += 1000;
      }
      return acc.rmse();
    };
    double stillScalar = stillRmse(false, false);
    double stillCvPinned = stillRmse(true, true);
    double stillCvBlind = stillRmse(true, false);
    std::printf("  stationary RMSE: scalar=%.2fm  cv+zero-doppler=%.2fm  cv-blind=%.2fm\n",
                stillScalar, stillCvPinned, stillCvBlind);
    check(stillCvPinned < stillScalar * 1.1,
          "cv: zero-doppler pin matches/beats scalar when parked");
    check(stillCvBlind < stillScalar * 2.5,
          "cv: bounded degradation without any velocity data");
  }

  // ---- Test 9: configurable accuracy floor preserves sub-meter (RTK) precision ----
  {
    // An RTK-grade source reporting +/-0.2m: with the default 1.0m floor the
    // filter degrades it 5x; with the floor lowered the real precision survives.
    auto rtkAccuracy = [&](double minAccuracy) {
      FusionConfig cfg;
      cfg.enableKalman = true;
      cfg.processNoise = 0.5;
      SensorFusion f(cfg);
      f.setMinAccuracy(minAccuracy);
      double lastAcc = 99.0;
      long long t = 1'000'000;
      for (int i = 0; i < 50; i++) {
        FusionOutput o = f.process(37.0, -122.0, 0.2, t);
        if (o.accepted) lastAcc = o.accuracy;
        t += 1000;
      }
      return lastAcc;
    };
    double floored = rtkAccuracy(1.0);   // default floor
    double subMeter = rtkAccuracy(0.1);  // RTK-friendly floor
    std::printf("  RTK accuracy out: floor=1.0 -> %.2fm  floor=0.1 -> %.2fm\n",
                floored, subMeter);
    check(subMeter < 0.2, "minAccuracy: sub-meter precision preserved with a low floor");
    check(subMeter < floored, "minAccuracy: low floor reports tighter than the default");

    // The CV model honors the same floor.
    FusionConfig cv;
    cv.enableKalman = true;
    cv.useCvModel = true;
    SensorFusion fcv(cv);
    fcv.setMinAccuracy(0.1);
    double cvAcc = 99.0;
    long long t = 1'000'000;
    for (int i = 0; i < 50; i++) {
      FusionOutput o = fcv.process(37.0, -122.0, 0.2, t, true, 0.0, 0.0, 0.1);
      if (o.accepted) cvAcc = o.accuracy;
      t += 1000;
    }
    check(cvAcc < 0.2, "minAccuracy: the CV model preserves sub-meter too");
  }

  std::printf("== %s ==\n", failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED");
  return failures == 0 ? 0 : 1;
}
