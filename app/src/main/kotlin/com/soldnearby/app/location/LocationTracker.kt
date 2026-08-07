package com.soldnearby.app.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thin coroutine wrapper around Play Services' FusedLocationProviderClient. */
class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    // Lint can't see that callers only invoke this after checking the location permission
    // themselves (MapScreen gates the call on hasLocationPermission).
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        val cancelSource = CancellationTokenSource()
        cont.invokeOnCancellation { cancelSource.cancel() }
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancelSource.token)
            .addOnSuccessListener { location ->
                cont.resume(location?.let { it.latitude to it.longitude })
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
