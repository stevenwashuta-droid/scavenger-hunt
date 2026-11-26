package com.cluetrail.app

import android.annotation.SuppressLint
import android.app.Activity
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Gets the most accurate GPS position it can within a few seconds.
 * A single quick reading is often 30+ feet off, so this listens to several
 * readings and keeps the best one.
 */
class Locator(activity: Activity) {
    private val client = LocationServices.getFusedLocationProviderClient(activity)

    @SuppressLint("MissingPermission")
    fun bestFix(timeoutMs: Long = 6000L, goodEnoughMeters: Float = 5f, cb: (Location?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var done = false
        var callback: LocationCallback? = null

        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            val c = callback
            if (c != null) client.removeLocationUpdates(c)
            cb(best)
        }

        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    val b = best
                    if (b == null || l.accuracy < b.accuracy) best = l
                }
                val b = best
                if (b != null && b.accuracy <= goodEnoughMeters) finish()
            }
        }
        callback = listener

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                .addOnFailureListener { finish() }
        } catch (e: Exception) {
            finish()
            return
        }
        handler.postDelayed({ finish() }, timeoutMs)
    }
}
