package expo.modules.geopulse.util

/**
 * Maps GNSS signal quality (satellites used in the fix + their average
 * carrier-to-noise density) to an accuracy inflation factor. Chips often keep
 * reporting optimistic accuracy in urban canyons / indoors even as the
 * constellation degrades; scaling the accuracy fed to the fusion filter by
 * this factor makes the filter appropriately skeptical there.
 *
 * Pure and host-testable. Thresholds: a healthy open-sky fix uses 8+
 * satellites at ~30+ dB-Hz; below ~20 dB-Hz signals are barely usable.
 */
object GnssQuality {
  /**
   * Inflation factor (>= 1.0) for the reported accuracy. [avgCn0DbHz] may be
   * NaN when the constellation reports no C/N0 — then only the count is used.
   */
  fun inflationFor(
    usedInFix: Int,
    avgCn0DbHz: Double,
  ): Double {
    val cn0 = if (avgCn0DbHz.isNaN()) 30.0 else avgCn0DbHz // unknown -> neutral
    return when {
      usedInFix >= 8 && cn0 >= 28.0 -> 1.0 // open sky
      usedInFix >= 6 && cn0 >= 24.0 -> 1.2 // light obstruction
      usedInFix >= 4 && cn0 >= 18.0 -> 1.5 // urban canyon
      else -> 2.0 // deep canyon / indoors: trust the fix half as much
    }
  }
}
