package com.cluetrail.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to the hunt database. Clue locations never leave the server:
 * the app sends the player's position and the server answers yes/no (and the next clue).
 */
object Api {
    private const val BASE = "https://javityzcowzmvcffzwnj.supabase.co/rest/v1/rpc/"

    // Public (anon) key. It can only call the three scav_* functions below.
    private const val KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imphdml0eXpjb3d6bXZjZmZ6d25qIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM4ODI2NTksImV4cCI6MjA5OTQ1ODY1OX0.ttiK3g8PZhCa3hPv-HdhvCYucyMI2SXYQfWe-HVpbKg"

    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    private fun rpc(fn: String, body: JSONObject, cb: (String?, String?) -> Unit) {
        io.execute {
            var out: String? = null
            var err: String? = null
            try {
                val conn = URL(BASE + fn).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", KEY)
                conn.setRequestProperty("Authorization", "Bearer $KEY")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (status in 200..299) {
                    out = text
                } else {
                    err = try {
                        JSONObject(text).optString("message", "Something went wrong ($status).")
                    } catch (e: Exception) {
                        "Something went wrong ($status)."
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                err = "Couldn't reach the server. Check your internet connection and try again.\n\n" +
                    "(Details: ${e.javaClass.simpleName}: ${e.message ?: "no message"})"
            }
            val finalOut = out
            val finalErr = err
            main.post { cb(finalOut, finalErr) }
        }
    }

    /** Publishes a hunt. Calls back with the 6-character join code. */
    fun createHunt(payload: JSONObject, cb: (String?, String?) -> Unit) {
        rpc("scav_create_hunt", JSONObject().put("p", payload)) { out, err ->
            if (out == null) {
                cb(null, err ?: "Something went wrong.")
            } else {
                val code = try {
                    JSONTokener(out).nextValue() as? String
                } catch (e: Exception) {
                    null
                }
                if (code.isNullOrBlank()) cb(null, "The server gave an unexpected answer.") else cb(code, null)
            }
        }
    }

    /** Looks a hunt up by code. Calls back with title, mode, radius_ft, total_clues, start_text. */
    fun join(code: String, cb: (JSONObject?, String?) -> Unit) {
        rpc("scav_join", JSONObject().put("p_code", code)) { out, err ->
            if (out == null) {
                cb(null, err ?: "Something went wrong.")
            } else if (out.trim() == "null" || out.isBlank()) {
                cb(null, "No hunt found with that code. Double-check it and try again.")
            } else {
                try {
                    cb(JSONObject(out), null)
                } catch (e: Exception) {
                    cb(null, "The server gave an unexpected answer.")
                }
            }
        }
    }

    /** Asks the server whether the player is standing at stop number [step]. */
    fun check(code: String, step: Int, lat: Double, lng: Double, cb: (JSONObject?, String?) -> Unit) {
        val body = JSONObject()
            .put("p_code", code)
            .put("p_step", step)
            .put("p_lat", lat)
            .put("p_lng", lng)
        rpc("scav_check", body) { out, err ->
            if (out == null) {
                cb(null, err ?: "Something went wrong.")
            } else {
                try {
                    cb(JSONObject(out), null)
                } catch (e: Exception) {
                    cb(null, "The server gave an unexpected answer.")
                }
            }
        }
    }
}
