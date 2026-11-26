package com.cluetrail.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val FEET_PER_METER = 3.28084f

class MainActivity : AppCompatActivity() {

    private var screen = "home"
    private var draft: Draft? = null
    private var game: Game? = null

    private lateinit var locator: Locator
    private var mapView: MapView? = null
    private var editorScroll: ScrollView? = null

    private var pendingFail: ((String) -> Unit)? = null
    private var pendingFix: ((Location) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fail = pendingFail
            val fix = pendingFix
            pendingFail = null
            pendingFix = null
            if (fail != null && fix != null) {
                if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                    withFix(fail, fix)
                } else {
                    fail(
                        "Clue Trail needs your precise location to check where you are. " +
                            "Allow \"Precise\" location for Clue Trail in your phone's settings and try again."
                    )
                }
            }
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        locator = Locator(this)
        draft = Store.loadDraft(this)
        game = Store.loadGame(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    "home" -> finish()
                    "map" -> {
                        closeMap()
                        showEditor(0)
                    }
                    else -> showHome()
                }
            }
        })

        when (savedInstanceState?.getString("screen")) {
            "editor", "map" -> if (draft != null) showEditor(0) else showHome()
            "play" -> if (game != null) showPlay(null) else showHome()
            else -> showHome()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen)
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    private fun showHome() {
        closeMap()
        screen = "home"
        page {
            addText("Clue Trail", 32, bold = true)
            addText("Real-world scavenger hunts. One person hides the trail, another follows it.", 16, dim = true, top = 4)

            val g = game
            if (g != null) {
                addCard(top = 24) {
                    addText("Hunt in progress", 13, dim = true)
                    addText(g.title, 20, bold = true, top = 2)
                    addText(progressLabel(g), 14, dim = true, top = 2)
                    addButton("Resume hunt") { showPlay(null) }
                }
            }

            addCard(top = 24) {
                addText("Play a hunt", 20, bold = true)
                addText("Enter the code the host sent you.", 14, dim = true, top = 2)
                var typed = ""
                val codeField = addField("Hunt code", "", 1) { typed = it }
                codeField.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
                codeField.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addButton("Start hunt") { joinHunt(typed.trim().uppercase(Locale.US)) }
            }

            addCard(top = 16) {
                addText("Make a hunt", 20, bold = true)
                addText("Write the clues, mark the spots, and send a code to your player.", 14, dim = true, top = 2)
                addButton(if (draft != null) "Continue or start a hunt" else "Create a hunt") { startCreating() }
            }

            val mine = Store.myHunts(this@MainActivity)
            if (mine.isNotEmpty()) {
                addText("Hunts you've made", 16, bold = true, top = 28)
                addText("Tap one to share its code again.", 13, dim = true, top = 2)
                for (h in mine) {
                    addButton("${h.title}  ·  ${h.code}", outlined = true, top = 8) { shareHunt(h.code, h.title) }
                }
            }
        }
    }

    private fun progressLabel(g: Game): String =
        if (g.step == 0) "At the start · ${g.total} clues" else "Clue ${g.step} of ${g.total}"

    private fun joinHunt(code: String) {
        if (code.length != 6) {
            alert("Check the code", "Hunt codes are 6 characters long.")
            return
        }
        val current = game
        if (current != null && current.code == code) {
            showPlay(null)
            return
        }
        val busy = busy("Finding your hunt…")
        Api.join(code) { res, err ->
            if (isDestroyed) return@join
            busy.dismiss()
            if (res == null) {
                alert("Couldn't start", err ?: "Something went wrong.")
            } else {
                val g = Game(
                    code = code,
                    title = res.optString("title", "Scavenger hunt"),
                    mode = res.optString("mode", "easy"),
                    total = res.optInt("total_clues", 0),
                    step = 0,
                    clue = res.optString("start_text", "")
                )
                game = g
                Store.saveGame(this, g)
                showPlay(null)
            }
        }
    }

    // ------------------------------------------------------------------
    // Host: building a hunt
    // ------------------------------------------------------------------

    private fun startCreating() {
        if (draft != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("You have a hunt in progress")
                .setMessage("Keep working on it, or throw it away and start a new one?")
                .setPositiveButton("Keep working") { _, _ -> showEditor(0) }
                .setNegativeButton("Start new") { _, _ -> askClueCount() }
                .show()
        } else {
            askClueCount()
        }
    }

    private fun askClueCount() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText("5")
        input.setSelection(input.text.length)
        val holder = FrameLayout(this)
        holder.setPadding(dp(24), dp(8), dp(24), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH, WRAP))
        MaterialAlertDialogBuilder(this)
            .setTitle("How many clues?")
            .setMessage("Pick any number from 1 to 50. You can add or remove clues later.")
            .setView(holder)
            .setPositiveButton("Next") { _, _ ->
                val n = (input.text.toString().toIntOrNull() ?: 5).coerceIn(1, 50)
                val d = Draft()
                d.stops.add(Stop()) // starting location
                for (i in 1..n) d.stops.add(Stop())
                draft = d
                Store.saveDraft(this, d)
                showEditor(0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveDraft() {
        Store.saveDraft(this, draft)
    }

    private fun rerenderEditor() {
        showEditor(editorScroll?.scrollY ?: 0)
    }

    private fun showEditor(scrollTo: Int) {
        val d = draft
        if (d == null) {
            showHome()
            return
        }
        screen = "editor"
        val sv = page {
            addText("Build your hunt", 28, bold = true)
            addText("Everything saves as you go.", 14, dim = true, top = 2)

            addField("Hunt name", d.title, 1, top = 20) {
                d.title = it
                saveDraft()
            }

            addText("Difficulty", 16, bold = true, top = 24)
            addRadio(
                listOf(
                    "Easy: if they're not there, it says how far away they are (never the direction)" to "easy",
                    "Hard: it only says \"You're not there\"" to "hard"
                ),
                d.mode
            ) {
                d.mode = it
                saveDraft()
            }

            addText("How close counts as arriving", 16, bold = true, top = 20)
            addRadioRow(
                listOf("20 ft" to 20, "30 ft" to 30, "50 ft" to 50, "100 ft" to 100),
                d.radius
            ) {
                d.radius = it
                saveDraft()
            }
            addText(
                "Phone GPS is usually good to about 10 to 30 feet outdoors and worse near tall buildings or indoors. " +
                    "If a spot is in a tight area, pick a bigger number.",
                13, dim = true, top = 2
            )

            for (i in d.stops.indices) addStopCard(d, i)

            addButton("+ Add another clue", outlined = true, top = 16) {
                if (d.stops.size >= 51) {
                    alert("That's the limit", "A hunt can have up to 50 clues.")
                } else {
                    d.stops.add(Stop())
                    saveDraft()
                    rerenderEditor()
                }
            }

            addField("Winning message (optional)", d.finalMessage, 2, top = 24) {
                d.finalMessage = it
                saveDraft()
            }

            addButton("Publish hunt", top = 24) { publish() }
            addButton("Delete this draft", outlined = true, top = 8) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Delete this draft?")
                    .setMessage("This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        draft = null
                        saveDraft()
                        showHome()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        editorScroll = sv
        if (scrollTo > 0) sv.post { sv.scrollTo(0, scrollTo) }
    }

    private fun LinearLayout.addStopCard(d: Draft, i: Int) {
        val stop = d.stops[i]
        addCard(top = 16) {
            if (i == 0) {
                addText("Starting location", 18, bold = true)
                addText("Tell the player where to begin. They check in here to get Clue 1.", 13, dim = true, top = 2)
                addField("Where to start", stop.clue, 2) {
                    stop.clue = it
                    saveDraft()
                }
            } else {
                addText("Clue $i", 18, bold = true)
                addText("Write the clue, then mark the spot it leads to.", 13, dim = true, top = 2)
                addField("Clue text", stop.clue, 3) {
                    stop.clue = it
                    saveDraft()
                }
            }

            val la = stop.lat
            val ln = stop.lng
            if (la != null && ln != null) {
                addText(String.format(Locale.US, "Spot marked  ✓  (%.5f, %.5f)", la, ln), 14, bold = true, top = 12)
            } else {
                addText("No spot marked yet", 14, dim = true, top = 12)
            }

            addButtonRow(
                "I'm standing here" to { markCurrentLocation(i) },
                "Pick on map" to { showMap(i) }
            )

            if (i >= 1 && d.stops.size > 2) {
                addButton("Remove this clue", outlined = true, top = 4) {
                    d.stops.removeAt(i)
                    saveDraft()
                    rerenderEditor()
                }
            }
        }
    }

    private fun markCurrentLocation(index: Int) {
        val d = draft ?: return
        val busy = busy("Getting your GPS position… hold still for a few seconds.")
        withFix({ msg ->
            busy.dismiss()
            alert("Couldn't get your location", msg)
        }) { loc ->
            busy.dismiss()
            if (index < d.stops.size) {
                d.stops[index].lat = loc.latitude
                d.stops[index].lng = loc.longitude
                saveDraft()
                val ft = (loc.accuracy * FEET_PER_METER).roundToInt()
                Toast.makeText(this, "Spot marked (GPS accurate to about ±$ft ft)", Toast.LENGTH_LONG).show()
                if (screen == "editor") rerenderEditor()
            }
        }
    }

    private fun publish() {
        val d = draft ?: return
        if (d.title.isBlank()) {
            alert("Almost there", "Give your hunt a name.")
            return
        }
        for (i in d.stops.indices) {
            val s = d.stops[i]
            val name = if (i == 0) "The starting location" else "Clue $i"
            if (s.clue.isBlank()) {
                alert("Almost there", "$name needs some text.")
                return
            }
            if (!s.hasLocation()) {
                alert("Almost there", "$name doesn't have a spot marked yet.")
                return
            }
        }
        val busy = busy("Publishing your hunt…")
        val title = d.title.trim()
        Api.createHunt(d.toJson()) { code, err ->
            if (isDestroyed) return@createHunt
            busy.dismiss()
            if (code == null) {
                alert("Couldn't publish", err ?: "Something went wrong.")
            } else {
                Store.addMyHunt(this, code, title)
                draft = null
                saveDraft()
                showPublished(code, title)
            }
        }
    }

    private fun showPublished(code: String, title: String) {
        screen = "published"
        page {
            addText("Your hunt is live", 28, bold = true)
            addText(title, 18, dim = true, top = 4)
            addCard(top = 24) {
                addText("Hunt code", 14, dim = true)
                val big = addText(code, 44, bold = true, top = 4)
                big.letterSpacing = 0.2f
                big.gravity = Gravity.CENTER_HORIZONTAL
                big.setTextIsSelectable(true)
            }
            addText(
                "Send this code to your player. They open Clue Trail, type it in under \"Play a hunt\", and head to your starting location.",
                15, dim = true, top = 16
            )
            addButton("Share the code", top = 20) { shareHunt(code, title) }
            addButton("Done", outlined = true, top = 8) { showHome() }
        }
    }

    private fun shareHunt(code: String, title: String) {
        val text = "I made you a scavenger hunt: \"$title\". Open the Clue Trail app, tap \"Play a hunt\" and enter code $code."
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share hunt code"))
    }

    // ------------------------------------------------------------------
    // Host: picking a spot on the map
    // ------------------------------------------------------------------

    private fun showMap(index: Int) {
        val d = draft ?: return
        if (index >= d.stops.size) return
        val scrollBack = editorScroll?.scrollY ?: 0
        screen = "map"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val mapHolder = FrameLayout(this)
        val map = MapView(this)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setMaxZoomLevel(21.0)
        mapHolder.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        mapHolder.addView(CrosshairView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(mapHolder, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Start on this stop's spot, else the nearest earlier marked spot, else find the phone.
        var startPoint: GeoPoint? = null
        val own = d.stops[index]
        val ownLat = own.lat
        val ownLng = own.lng
        if (ownLat != null && ownLng != null) {
            startPoint = GeoPoint(ownLat, ownLng)
        } else {
            for (j in d.stops.indices.reversed()) {
                val la = d.stops[j].lat
                val ln = d.stops[j].lng
                if (la != null && ln != null) {
                    startPoint = GeoPoint(la, ln)
                    break
                }
            }
        }
        if (startPoint != null) {
            map.controller.setZoom(19.0)
            map.controller.setCenter(startPoint)
        } else {
            map.controller.setZoom(4.0)
            map.controller.setCenter(GeoPoint(39.5, -98.35))
            centerMapOnMe(map, quiet = true)
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setPadding(dp(16), dp(12), dp(16), dp(16))
        bar.setBackgroundColor(backgroundColor())
        val label = if (index == 0) "the starting location" else "Clue $index"
        bar.addText("Drag the map until the crosshair sits on the spot for $label. Zoom in close for accuracy.", 14)
        bar.addButtonRow(
            "Center on me" to { centerMapOnMe(map, quiet = false) },
            "Cancel" to {
                closeMap()
                showEditor(scrollBack)
            }
        )
        bar.addButton("Use this spot", top = 4) {
            val c = map.mapCenter
            if (index < d.stops.size) {
                d.stops[index].lat = c.latitude
                d.stops[index].lng = c.longitude
                saveDraft()
            }
            closeMap()
            showEditor(scrollBack)
        }
        bar.addText("Map © OpenStreetMap contributors", 11, dim = true, top = 4)
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        mapView = map
        map.onResume()
    }

    private fun centerMapOnMe(map: MapView, quiet: Boolean) {
        withFix({ msg ->
            if (!quiet && mapView === map) alert("Couldn't get your location", msg)
        }) { loc ->
            if (mapView === map) {
                map.controller.setZoom(19.0)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    private fun closeMap() {
        val m = mapView ?: return
        mapView = null
        try {
            m.onPause()
            m.onDetach()
        } catch (e: Exception) {
            // Nothing to clean up.
        }
    }

    // ------------------------------------------------------------------
    // Player
    // ------------------------------------------------------------------

    private fun showPlay(banner: String?) {
        val g = game
        if (g == null) {
            showHome()
            return
        }
        closeMap()
        screen = "play"
        page {
            addText(g.title, 24, bold = true)
            addText(
                if (g.mode == "hard") "Hard mode · ${g.total} clues" else "Easy mode · ${g.total} clues",
                14, dim = true, top = 2
            )

            if (banner != null) {
                val b = addText(banner, 18, bold = true, top = 20)
                b.setTextColor(primaryColor())
            }

            addCard(top = 20) {
                if (g.step == 0) {
                    addText("STARTING LOCATION", 13, dim = true)
                    addText("Go here to begin:", 14, dim = true, top = 6)
                } else {
                    addText("CLUE ${g.step} OF ${g.total}", 13, dim = true)
                }
                val clue = addText(g.clue, 22, top = 8)
                clue.setTextIsSelectable(true)
            }

            val result = addText("", 18, bold = true, top = 20)
            result.gravity = Gravity.CENTER_HORIZONTAL
            result.visibility = View.GONE

            val buttonRef = arrayOfNulls<MaterialButton>(1)
            val checkButton = addButton("Am I at the right location?", top = 20) {
                val btn = buttonRef[0]
                if (btn != null) checkLocation(g, btn, result)
            }
            buttonRef[0] = checkButton
            checkButton.setPadding(dp(16), dp(20), dp(16), dp(20))
            checkButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)

            addButton("Leave hunt (your place is saved)", outlined = true, top = 32) { showHome() }
        }
    }

    private fun checkLocation(g: Game, button: MaterialButton, result: TextView) {
        button.isEnabled = false
        button.text = "Checking your location…"
        result.visibility = View.GONE

        fun reset() {
            button.isEnabled = true
            button.text = "Am I at the right location?"
        }

        fun show(message: String) {
            result.text = message
            result.visibility = View.VISIBLE
        }

        withFix({ msg ->
            if (screen == "play") {
                reset()
                show(msg)
            }
        }) { loc ->
            Api.check(g.code, g.step, loc.latitude, loc.longitude) { res, err ->
                if (isDestroyed || screen != "play" || game !== g) return@check
                if (res == null) {
                    reset()
                    show(err ?: "Something went wrong.")
                } else if (!res.optBoolean("arrived", false)) {
                    reset()
                    var message = "You're not there."
                    if (res.has("distance_ft")) {
                        message += "\nYou're ${formatDistance(res.optDouble("distance_ft", 0.0))} away."
                    }
                    val accuracyFt = (loc.accuracy * FEET_PER_METER).roundToInt()
                    if (accuracyFt > 40) {
                        message += "\n\nYour GPS is only good to about ±$accuracyFt ft right now. " +
                            "If you think you're on the spot, wait a moment and try again."
                    }
                    show(message)
                } else if (res.optBoolean("finished", false)) {
                    game = null
                    Store.saveGame(this, null)
                    showWon(g, res.optString("final_message", ""))
                } else {
                    val next = Game(
                        code = g.code,
                        title = g.title,
                        mode = g.mode,
                        total = g.total,
                        step = res.optInt("next_step", g.step + 1),
                        clue = res.optString("next_clue", "")
                    )
                    game = next
                    Store.saveGame(this, next)
                    showPlay(if (g.step == 0) "You're at the start. Here's your first clue!" else "You found it! Here's your next clue.")
                }
            }
        }
    }

    private fun formatDistance(feet: Double): String {
        if (feet < 1000) return "${feet.roundToInt()} feet"
        val miles = feet / 5280.0
        return if (miles < 10) String.format(Locale.US, "%.2f miles", miles)
        else String.format(Locale.US, "%.1f miles", miles)
    }

    private fun showWon(g: Game, finalMessage: String) {
        screen = "won"
        page {
            val trophy = addText("🏆", 72, top = 40)
            trophy.gravity = Gravity.CENTER_HORIZONTAL
            val title = addText("Congratulations, you win!", 30, bold = true, top = 16)
            title.gravity = Gravity.CENTER_HORIZONTAL
            val sub = addText("You finished \"${g.title}\" and found all ${g.total} clues.", 16, dim = true, top = 8)
            sub.gravity = Gravity.CENTER_HORIZONTAL
            if (finalMessage.isNotBlank()) {
                addCard(top = 24) {
                    addText("A MESSAGE FROM YOUR HOST", 13, dim = true)
                    addText(finalMessage, 20, top = 8)
                }
            }
            addButton("Back to home", top = 32) { showHome() }
        }
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    private fun withFix(onFail: (String) -> Unit, onFix: (Location) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingFail = onFail
            pendingFix = onFix
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            onFail("Location is turned off on this phone. Turn it on and try again.")
            return
        }
        locator.bestFix { loc ->
            if (isDestroyed) return@bestFix
            if (loc == null) {
                onFail("Couldn't get a GPS position. Step outside or away from buildings and try again.")
            } else {
                onFix(loc)
            }
        }
    }

    // ------------------------------------------------------------------
    // Small UI helpers
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun primaryColor(): Int = ContextCompat.getColor(this, R.color.brand)

    private fun backgroundColor(): Int = themeColor(android.R.attr.colorBackground, Color.WHITE)

    private fun rowParams(top: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(top)
        return lp
    }

    private fun page(build: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(28), dp(20), dp(40))
        column.build()
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        setContentView(scroll)
        return scroll
    }

    private fun LinearLayout.addText(
        value: String,
        sp: Int = 16,
        bold: Boolean = false,
        dim: Boolean = false,
        top: Int = 0
    ): TextView {
        val tv = TextView(this@MainActivity)
        tv.text = value
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (dim) tv.alpha = 0.7f
        addView(tv, rowParams(top))
        return tv
    }

    private fun LinearLayout.addButton(
        label: String,
        outlined: Boolean = false,
        top: Int = 12,
        onClick: () -> Unit
    ): MaterialButton {
        val b = newButton(label, outlined, onClick)
        addView(b, rowParams(top))
        return b
    }

    private fun newButton(label: String, outlined: Boolean, onClick: () -> Unit): MaterialButton {
        val b = if (outlined) {
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        } else {
            MaterialButton(this)
        }
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    /** Two or more outlined buttons side by side, equal width. */
    private fun LinearLayout.addButtonRow(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this@MainActivity)
        row.orientation = LinearLayout.HORIZONTAL
        for ((n, item) in items.withIndex()) {
            val b = newButton(item.first, true, item.second)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (n > 0) lp.marginStart = dp(8)
            row.addView(b, lp)
        }
        addView(row, rowParams(8))
    }

    private fun LinearLayout.addField(
        hint: String,
        value: String,
        lines: Int = 1,
        top: Int = 12,
        onChange: (String) -> Unit
    ): TextInputEditText {
        val layout = TextInputLayout(this@MainActivity)
        layout.hint = hint
        val edit = TextInputEditText(layout.context)
        edit.setText(value)
        if (lines > 1) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            edit.minLines = lines
            edit.gravity = Gravity.TOP or Gravity.START
        } else {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            edit.maxLines = 1
        }
        edit.doAfterTextChanged { onChange(it?.toString() ?: "") }
        layout.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(layout, rowParams(top))
        return edit
    }

    private fun LinearLayout.addCard(top: Int = 16, build: LinearLayout.() -> Unit) {
        val card = MaterialCardView(this@MainActivity)
        val inner = LinearLayout(this@MainActivity)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(16), dp(16), dp(16), dp(16))
        inner.build()
        card.addView(inner, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(card, rowParams(top))
    }

    /** Stacked radio choices (for long labels). */
    private fun <T> LinearLayout.addRadio(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
        addRadioGroup(options, current, LinearLayout.VERTICAL, onPick)
    }

    /** Side-by-side radio choices (for short labels). */
    private fun <T> LinearLayout.addRadioRow(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
        addRadioGroup(options, current, LinearLayout.HORIZONTAL, onPick)
    }

    private fun <T> LinearLayout.addRadioGroup(
        options: List<Pair<String, T>>,
        current: T,
        direction: Int,
        onPick: (T) -> Unit
    ) {
        val group = RadioGroup(this@MainActivity)
        group.orientation = direction
        for (option in options) {
            val rb = MaterialRadioButton(this@MainActivity)
            rb.id = View.generateViewId()
            rb.text = option.first
            val lp = if (direction == LinearLayout.HORIZONTAL) {
                RadioGroup.LayoutParams(0, WRAP, 1f)
            } else {
                RadioGroup.LayoutParams(MATCH, WRAP)
            }
            group.addView(rb, lp)
            if (option.second == current) group.check(rb.id)
            rb.setOnClickListener { onPick(option.second) }
        }
        addView(group, rowParams(4))
    }

    private fun alert(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun busy(message: String): AlertDialog =
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(false)
            .show()
}

/** A small red crosshair drawn over the centre of the map. Touches pass through to the map. */
class CrosshairView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(214, 40, 40)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val arm = 18f * density
        val gap = 4f * density
        for (p in arrayOf(halo, line)) {
            canvas.drawLine(cx - arm, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + arm, cy, p)
            canvas.drawLine(cx, cy - arm, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + arm, p)
            canvas.drawCircle(cx, cy, 9f * density, p)
        }
    }
}
