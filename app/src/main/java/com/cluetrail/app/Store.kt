package com.cluetrail.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One location in a hunt. Index 0 is the starting location; the rest are clue locations. */
class Stop(var clue: String = "", var lat: Double? = null, var lng: Double? = null) {
    fun hasLocation(): Boolean = lat != null && lng != null
}

/** A hunt the host is still building. */
class Draft(
    var title: String = "",
    var mode: String = "easy",
    var radius: Int = 20,
    var finalMessage: String = "",
    val stops: MutableList<Stop> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in stops) {
            val o = JSONObject().put("clue", s.clue)
            val la = s.lat
            val ln = s.lng
            if (la != null && ln != null) {
                o.put("lat", la)
                o.put("lng", ln)
            }
            arr.put(o)
        }
        return JSONObject()
            .put("title", title)
            .put("mode", mode)
            .put("radius_ft", radius)
            .put("final_message", finalMessage)
            .put("stops", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Draft {
            val d = Draft(
                title = o.optString("title", ""),
                mode = o.optString("mode", "easy"),
                radius = o.optInt("radius_ft", 20),
                finalMessage = o.optString("final_message", "")
            )
            val arr = o.optJSONArray("stops") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val stop = Stop(clue = s.optString("clue", ""))
                if (s.has("lat") && !s.isNull("lat") && s.has("lng") && !s.isNull("lng")) {
                    stop.lat = s.getDouble("lat")
                    stop.lng = s.getDouble("lng")
                }
                d.stops.add(stop)
            }
            return d
        }
    }
}

/** A hunt the player is in the middle of. [step] is the stop they are trying to reach (0 = start). */
class Game(
    val code: String,
    val title: String,
    val mode: String,
    val total: Int,
    val step: Int,
    val clue: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("code", code)
        .put("title", title)
        .put("mode", mode)
        .put("total", total)
        .put("step", step)
        .put("clue", clue)

    companion object {
        fun fromJson(o: JSONObject): Game = Game(
            code = o.optString("code", ""),
            title = o.optString("title", ""),
            mode = o.optString("mode", "easy"),
            total = o.optInt("total", 0),
            step = o.optInt("step", 0),
            clue = o.optString("clue", "")
        )
    }
}

class MyHunt(val code: String, val title: String)

/** Saves everything on the phone so nothing is lost if the app is closed. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("cluetrail", Context.MODE_PRIVATE)

    fun saveDraft(c: Context, d: Draft?) {
        val e = prefs(c).edit()
        if (d == null) e.remove("draft") else e.putString("draft", d.toJson().toString())
        e.apply()
    }

    fun loadDraft(c: Context): Draft? {
        val s = prefs(c).getString("draft", null) ?: return null
        return try {
            Draft.fromJson(JSONObject(s))
        } catch (e: Exception) {
            null
        }
    }

    fun saveGame(c: Context, g: Game?) {
        val e = prefs(c).edit()
        if (g == null) e.remove("game") else e.putString("game", g.toJson().toString())
        e.apply()
    }

    fun loadGame(c: Context): Game? {
        val s = prefs(c).getString("game", null) ?: return null
        return try {
            Game.fromJson(JSONObject(s))
        } catch (e: Exception) {
            null
        }
    }

    fun myHunts(c: Context): List<MyHunt> {
        val s = prefs(c).getString("my_hunts", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val list = ArrayList<MyHunt>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(MyHunt(o.optString("code", ""), o.optString("title", "")))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addMyHunt(c: Context, code: String, title: String) {
        val arr = JSONArray()
        arr.put(JSONObject().put("code", code).put("title", title))
        for (h in myHunts(c)) {
            if (arr.length() >= 20) break
            arr.put(JSONObject().put("code", h.code).put("title", h.title))
        }
        prefs(c).edit().putString("my_hunts", arr.toString()).apply()
    }
}
