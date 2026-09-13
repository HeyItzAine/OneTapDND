package com.example.onetapdnd

internal const val MAX_LOCATION_AGE_NANOS = 2L * 60L * 1_000_000_000L

internal fun acceptsLocationSample(sampleNanos: Long, previousNanos: Long, nowNanos: Long): Boolean =
    sampleNanos > 0L && sampleNanos <= nowNanos && nowNanos - sampleNanos <= MAX_LOCATION_AGE_NANOS &&
        sampleNanos >= (previousNanos.takeIf { it in 0L..nowNanos } ?: 0L)

internal fun locationAccuracyMargin(accuracyMeters: Float): Float = accuracyMeters.coerceAtLeast(25f)

internal fun insideAfterLocation(
    wasInside: Boolean,
    distanceMeters: Float,
    radiusMeters: Float,
    accuracyMeters: Float
): Boolean {
    if (!distanceMeters.isFinite() || distanceMeters < 0f ||
        !accuracyMeters.isFinite() || accuracyMeters < 0f
    ) return wasInside
    val margin = locationAccuracyMargin(accuracyMeters)
    return if (wasInside) distanceMeters <= radiusMeters + margin
        else distanceMeters <= (radiusMeters - margin).coerceAtLeast(0f)
}

internal fun distanceToPlaceBoundary(distanceMeters: Float, radiusMeters: Float, accuracyMeters: Float): Float =
    (distanceMeters - radiusMeters - locationAccuracyMargin(accuracyMeters)).coerceAtLeast(0f)
