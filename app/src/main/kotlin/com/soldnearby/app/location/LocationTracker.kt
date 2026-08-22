package com.soldnearby.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Thin coroutine wrapper around Play Services' FusedLocationProviderClient. */
class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Whatever fix Play Services already has cached, or null if there isn't a recent one.
     *
     * Returns as close to instantly as makes no difference — it never waits on the location
     * hardware — which is what lets the locate-me button move the camera on the same frame as
     * the tap instead of after a round trip to the GPS (see MapScreen.centerOnCurrentLocation).
     *
     * Bounded by [maxAgeMs] because the cache has no upper age limit of its own: without this,
     * "last known" can be a fix from hours ago and a different city, and snapping the map there
     * would be worse than not moving at all. Age is measured with elapsedRealtime rather than
     * wall-clock time so a clock adjustment can't make a stale fix look fresh.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(maxAgeMs: Long): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { location ->
                    val fresh = location?.takeIf { it.ageMillis() <= maxAgeMs }
                    cont.resume(fresh?.let { it.latitude to it.longitude })
                }
                // A failure here is not worth propagating: every caller's fallback for "no
                // cached fix" is already the same as its fallback for "couldn't read one".
                .addOnFailureListener { cont.resume(null) }
        }

    /**
     * Asks the location hardware for a genuinely new fix. Can take seconds (or never resolve at
     * all, indoors), so callers should treat it as a refinement of something they already showed
     * rather than as the thing they're waiting on — and should impose their own timeout.
     *
     * [highAccuracy] turns the GPS on for this one request. Worth the power for an explicit tap
     * on the locate-me button; not for the background dot-following stream, which uses the
     * balanced (network/fused) priority instead.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(highAccuracy: Boolean = false): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val cancelSource = CancellationTokenSource()
            cont.invokeOnCancellation { cancelSource.cancel() }
            val priority =
                if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            client.getCurrentLocation(priority, cancelSource.token)
                .addOnSuccessListener { location ->
                    cont.resume(location?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener { cont.resume(null) }
        }

    /**
     * A continuous stream of fixes, for as long as it's collected.
     *
     * This replaced a loop that called [currentLocation] on a fixed delay. Each of those is a
     * *separate* one-shot request that spins the location engine up and back down, so polling
     * with them was both more expensive than a subscription and slower to produce anything —
     * and it left the app's idea of "where the user is" as stale as one poll interval plus
     * however long that request took, which is precisely what the locate-me button then had to
     * wait on. A real subscription keeps the last fix continuously current instead.
     *
     * removeLocationUpdates in awaitClose is what stops it: collect this inside a
     * repeatOnLifecycle(STARTED) so the subscription is actually torn down when the app is
     * backgrounded, rather than merely ignored.
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long): Flow<Pair<Double, Double>> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            // Play Services is free to deliver faster than intervalMs when something else on the
            // device has already paid for a fix; there's no reason to throw those away.
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.latitude to it.longitude) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.ageMillis(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
}
