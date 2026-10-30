package com.example.mockloc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var selectedText: TextView
    private lateinit var searchBox: EditText
    private lateinit var speedGroup: RadioGroup
    private lateinit var speedBar: SeekBar
    private lateinit var speedLabel: TextView
    private lateinit var btnPause: Button
    private lateinit var chipLoop: Chip
    private lateinit var chipJoy: Chip
    private lateinit var chipStop: Chip
    private lateinit var chipTimer: Chip
    private lateinit var btnMore: Button
    private lateinit var extraTop: View
    private lateinit var extraBottom: View
    private lateinit var joystick: JoystickView
    private val prefs by lazy { getSharedPreferences("mockloc", MODE_PRIVATE) }

    private var marker: Marker? = null      // the pin you tapped (destination)
    private var liveMarker: Marker? = null  // where the mock location currently is
    private var routeLine: Polyline? = null
    private val stops = mutableListOf<GeoPoint>()
    private val stopMarkers = mutableListOf<Marker>()
    private var sawMoving = false

    // Smooth marker animation between the 1-second location updates
    private var hasDisp = false
    private var dLat = 0.0
    private var dLon = 0.0
    private var aLat = 0.0
    private var aLon = 0.0
    private var tLat = 0.0
    private var tLon = 0.0
    private var aStart = 0L
    private var wasMoving = false
    private var suppressArrivalUntil = 0L
    private var follow = false
    private var selected: GeoPoint? = null
    private var pending: GeoPoint? = null

    private val speedIds = listOf(R.id.rbWalk, R.id.rbJog, R.id.rbBike, R.id.rbCar)
    private val presetsKmh = listOf(5, 10, 18, 40)

    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshLive()
            ui.postDelayed(this, 100)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasFineLocation()) {
                pending?.let { startMock(it.latitude, it.longitude) }
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
            }
            pending = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val osmBase = File(cacheDir, "osmdroid")
        val osmTiles = File(osmBase, "tiles")
        osmTiles.mkdirs()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = osmBase
            osmdroidTileCache = osmTiles
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
        }

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        status = findViewById(R.id.status)
        selectedText = findViewById(R.id.selected)
        searchBox = findViewById(R.id.search)
        speedGroup = findViewById(R.id.speedGroup)
        speedBar = findViewById(R.id.speedBar)
        speedLabel = findViewById(R.id.speedLabel)
        btnPause = findViewById(R.id.btnPause)
        chipLoop = findViewById(R.id.chipLoop)
        chipJoy = findViewById(R.id.chipJoy)
        chipStop = findViewById(R.id.chipStop)
        chipTimer = findViewById(R.id.chipTimer)
        btnMore = findViewById(R.id.btnMore)
        extraTop = findViewById(R.id.extraTop)
        extraBottom = findViewById(R.id.extraBottom)
        joystick = findViewById(R.id.joystick)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Dark map when the phone is in dark mode
        val night = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        // Touching the map stops auto-follow so you can look around freely
        map.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) follow = false
            false
        }

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                select(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        // Restore last pin
        val sLat = prefs.getString("lat", null)?.toDoubleOrNull()
        val sLon = prefs.getString("lon", null)?.toDoubleOrNull()
        if (sLat != null && sLon != null) {
            val p = GeoPoint(sLat, sLon)
            map.controller.setZoom(15.0)
            map.controller.setCenter(p)
            select(p)
        } else {
            map.controller.setZoom(4.5)
            map.controller.setCenter(GeoPoint(20.5937, 78.9629))
        }

        // Restore speed (before listeners are attached)
        val kmh = prefs.getInt("speedKmh", 5).coerceIn(1, 120)
        speedBar.progress = kmh - 1
        applySpeed(kmh)
        val presetIdx = presetsKmh.indexOf(kmh)
        if (presetIdx >= 0) speedGroup.check(speedIds[presetIdx]) else speedGroup.clearCheck()

        speedGroup.setOnCheckedChangeListener { _, checkedId ->
            val idx = speedIds.indexOf(checkedId)
            if (idx >= 0) {
                speedBar.progress = presetsKmh[idx] - 1
                applySpeed(presetsKmh[idx])
            }
        }
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    applySpeed(progress + 1)
                    val idx = presetsKmh.indexOf(progress + 1)
                    if (idx >= 0) speedGroup.check(speedIds[idx]) else speedGroup.clearCheck()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Loop toggle (go back and forth along the route)
        val loopOn = prefs.getBoolean("loop", false)
        MockLocationService.loop = loopOn
        chipLoop.isChecked = loopOn
        chipLoop.setOnCheckedChangeListener { _, checked ->
            MockLocationService.loop = checked
            prefs.edit().putBoolean("loop", checked).apply()
        }

        // Joystick (manual steering, up = north, max speed = the speed slider)
        joystick.onChange = { x, y ->
            MockLocationService.joyX = x
            MockLocationService.joyY = y
        }
        chipJoy.setOnCheckedChangeListener { _, checked ->
            if (checked && !MockLocationService.running) {
                Toast.makeText(
                    this,
                    "Tap Start here first, then turn the joystick on",
                    Toast.LENGTH_LONG
                ).show()
                chipJoy.isChecked = false
            } else {
                MockLocationService.joyX = 0f
                MockLocationService.joyY = 0f
                MockLocationService.joyActive = checked
                joystick.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) follow = true
            }
        }

        // Multi-stop routes: tap to add the current pin as a stop, long-press to clear
        chipStop.setOnClickListener { addStop() }
        chipStop.setOnLongClickListener {
            clearStops()
            Toast.makeText(this, "Stops cleared", Toast.LENGTH_SHORT).show()
            true
        }

        // Auto-stop timer
        chipTimer.setOnClickListener {
            val labels = arrayOf("Off", "15 minutes", "30 minutes", "1 hour", "2 hours")
            val mins = intArrayOf(0, 15, 30, 60, 120)
            AlertDialog.Builder(this)
                .setTitle("Auto-stop after")
                .setItems(labels) { _, i -> setTimer(mins[i]) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Collapsible controls (more room for the map)
        btnMore.setOnClickListener { applyPanel(extraTop.visibility != View.VISIBLE) }
        applyPanel(prefs.getBoolean("panelOpen", true))

        showLastCrash()

        findViewById<Button>(R.id.btnSearch).setOnClickListener { doSearch() }
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnMove).setOnClickListener { onMoveClicked() }
        btnPause.setOnClickListener {
            if (!MockLocationService.moving) {
                Toast.makeText(this, "Not moving right now", Toast.LENGTH_SHORT).show()
            } else {
                startService(
                    Intent(this, MockLocationService::class.java)
                        .setAction(MockLocationService.ACTION_TOGGLE_PAUSE)
                )
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(
                Intent(this, MockLocationService::class.java)
                    .setAction(MockLocationService.ACTION_STOP)
            )
            ui.postDelayed({ refreshLive() }, 300)
        }
        findViewById<Button>(R.id.btnDev).setOnClickListener { openDevOptions() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveFavorite() }
        findViewById<Button>(R.id.btnFavs).setOnClickListener { showFavorites() }
        findViewById<Button>(R.id.btnCenter).setOnClickListener {
            val p = if (MockLocationService.running)
                GeoPoint(MockLocationService.curLat, MockLocationService.curLon)
            else selected
            if (p != null) {
                follow = MockLocationService.running
                map.controller.animateTo(p)
            } else {
                Toast.makeText(this, "Nothing to center on yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        map.onPause()
        super.onPause()
    }

    // ---------- Speed ----------
    private fun applySpeed(kmh: Int) {
        val k = kmh.coerceIn(1, 120)
        MockLocationService.targetSpeedMs = k / 3.6f
        speedLabel.text = "$k km/h"
        prefs.edit().putInt("speedKmh", k).apply()
    }

    // ---------- Live status / markers ----------
    private fun setStatus(t: String) {
        if (status.text.toString() != t) status.text = t
    }

    /** Called ~10 times a second: glides the marker between the 1 s location updates. */
    private fun refreshLive() {
        if (MockLocationService.running) {
            val now = SystemClock.uptimeMillis()
            val cLat = MockLocationService.curLat
            val cLon = MockLocationService.curLon
            if (!hasDisp || abs(cLat - dLat) + abs(cLon - dLon) > 0.01) {
                // first frame or a big jump: snap
                dLat = cLat; dLon = cLon
                aLat = cLat; aLon = cLon
                tLat = cLat; tLon = cLon
                aStart = now
                hasDisp = true
            } else if (cLat != tLat || cLon != tLon) {
                aLat = dLat; aLon = dLon
                tLat = cLat; tLon = cLon
                aStart = now
            }
            val f = ((now - aStart) / 1000.0).coerceIn(0.0, 1.0)
            val nLat = aLat + (tLat - aLat) * f
            val nLon = aLon + (tLon - aLon) * f
            val changed = nLat != dLat || nLon != dLon
            dLat = nLat
            dLon = nLon
            val p = GeoPoint(nLat, nLon)

            var created = false
            if (liveMarker == null) {
                liveMarker = Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setIcon(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_live))
                    setOnMarkerClickListener { _, _ -> true }
                }
                map.overlays.add(liveMarker)
                created = true
            }
            liveMarker!!.position = p

            val moving = MockLocationService.moving
            val joy = MockLocationService.joyActive
            if (follow && (moving || joy)) map.controller.setCenter(p)

            if (moving) {
                sawMoving = true
            } else if (sawMoving) {
                clearRoute()
                created = true
            }
            if (wasMoving && !moving && !joy && now > suppressArrivalUntil) {
                Toast.makeText(this, "Arrived", Toast.LENGTH_SHORT).show()
            }
            wasMoving = moving
            if (changed || created) map.invalidate()

            setStatus(
                when {
                    joy -> "Joystick: %.1f km/h".format(MockLocationService.speedNow * 3.6f)
                    moving && MockLocationService.paused ->
                        "Paused: ${fmtDist(MockLocationService.remaining)} left"
                    moving -> {
                        val sec = (MockLocationService.remaining /
                            MockLocationService.targetSpeedMs.coerceAtLeast(0.5f)).toInt()
                        "${if (MockLocationService.loop) "Looping" else "Moving"}: " +
                            "${fmtDist(MockLocationService.remaining)} left, ETA %d:%02d"
                                .format(sec / 60, sec % 60)
                    }
                    else -> "Running at %.5f, %.5f".format(nLat, nLon)
                }
            )
        } else {
            var dirty = false
            liveMarker?.let {
                map.overlays.remove(it)
                liveMarker = null
                dirty = true
            }
            if (routeLine != null) {
                clearRoute()
                dirty = true
            }
            if (dirty) map.invalidate()
            hasDisp = false
            wasMoving = false
            setStatus("Status: stopped")
            if (chipJoy.isChecked) chipJoy.isChecked = false
        }
        val timerText = timerChipText()
        if (chipTimer.text.toString() != timerText) chipTimer.text = timerText
        val pauseText = if (MockLocationService.paused) "Resume" else "Pause"
        if (btnPause.text.toString() != pauseText) btnPause.text = pauseText
    }

    // ---------- Timer / panel ----------
    private fun setTimer(minutes: Int) {
        prefs.edit().putInt("timerMin", minutes).apply()
        if (MockLocationService.running) {
            MockLocationService.stopAtMs =
                if (minutes == 0) 0L else SystemClock.elapsedRealtime() + minutes * 60_000L
        }
    }

    private fun timerChipText(): String {
        val at = MockLocationService.stopAtMs
        if (MockLocationService.running && at > 0L) {
            val m = ((at - SystemClock.elapsedRealtime()) / 60_000L + 1).toInt()
            return "Timer: ${m}m left"
        }
        val pm = prefs.getInt("timerMin", 0)
        return if (pm == 0) "Timer: off" else "Timer: ${pm}m"
    }

    private fun applyPanel(expanded: Boolean) {
        val v = if (expanded) View.VISIBLE else View.GONE
        extraTop.visibility = v
        extraBottom.visibility = v
        btnMore.text = if (expanded) "Less" else "More"
        prefs.edit().putBoolean("panelOpen", expanded).apply()
    }

    private fun routeMeters(pts: List<GeoPoint>): Double {
        var total = 0.0
        val r = FloatArray(1)
        for (i in 0 until pts.size - 1) {
            Location.distanceBetween(
                pts[i].latitude, pts[i].longitude,
                pts[i + 1].latitude, pts[i + 1].longitude, r
            )
            total += r[0]
        }
        return total
    }

    // ---------- Stops ----------
    private fun updateStopChip() {
        chipStop.text = if (stops.isEmpty()) "Add stop" else "Stops: ${stops.size}"
    }

    private fun addStop() {
        val p = selected
        if (p == null) {
            Toast.makeText(this, "Tap the map to choose a spot first", Toast.LENGTH_SHORT).show()
            return
        }
        if (stops.size >= 8) {
            Toast.makeText(this, "Maximum 8 stops", Toast.LENGTH_SHORT).show()
            return
        }
        stops.add(p)
        val m = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setIcon(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_stop))
            setOnMarkerClickListener { _, _ -> true }
            position = p
        }
        map.overlays.add(m)
        stopMarkers.add(m)
        map.invalidate()
        updateStopChip()
        if (stops.size == 1) {
            Toast.makeText(
                this,
                "Stop added. Pick the next spot or the final pin, then Move. Long-press this chip to clear.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearStops() {
        stopMarkers.forEach { map.overlays.remove(it) }
        stopMarkers.clear()
        stops.clear()
        updateStopChip()
        map.invalidate()
    }

    // ---------- Last crash ----------
    private fun showLastCrash() {
        val crash = prefs.getString("lastCrash", null) ?: return
        prefs.edit().remove("lastCrash").apply()
        val tv = TextView(this).apply {
            text = crash
            textSize = 11f
            setPadding(32, 16, 32, 16)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Mock Loc crashed last time")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", crash))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun fmtDist(m: Double): String =
        if (m >= 1000) "%.1f km".format(m / 1000.0) else "%.0f m".format(m)

    private fun clearRoute() {
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        sawMoving = false
    }

    private fun drawRoute(points: List<GeoPoint>) {
        clearRoute()
        val line = Polyline().apply {
            setPoints(points)
            outlinePaint.color = Color.parseColor("#1E88E5")
            outlinePaint.strokeWidth = 12f
        }
        routeLine = line
        // index 0 is the tap handler; keep the route under the markers
        map.overlays.add(1.coerceAtMost(map.overlays.size), line)
        map.invalidate()
    }

    // ---------- Map selection ----------
    private fun select(p: GeoPoint) {
        selected = p
        if (marker == null) {
            marker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> true }
            }
            map.overlays.add(marker)
        }
        marker!!.position = p
        map.invalidate()
        selectedText.text = "Pin: %.6f, %.6f".format(p.latitude, p.longitude)
        prefs.edit()
            .putString("lat", p.latitude.toString())
            .putString("lon", p.longitude.toString())
            .apply()
    }

    /** Accepts "28.6139, 77.2090" (comma, semicolon or space separated). */
    private fun parseCoords(text: String): GeoPoint? {
        val m = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$")
            .find(text) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }

    private fun goTo(p: GeoPoint) {
        map.controller.setZoom(15.0)
        map.controller.animateTo(p)
        select(p)
    }

    private fun doSearch() {
        val q = searchBox.text.toString().trim()
        if (q.isEmpty()) return
        parseCoords(q)?.let {
            goTo(it)
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchBox.windowToken, 0)

        Thread {
            try {
                val url = URL(
                    "https://nominatim.openstreetmap.org/search?format=json&limit=5&q=" +
                        URLEncoder.encode(q, "UTF-8")
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "MockLoc/1.0 (personal use)")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                runOnUiThread {
                    when (arr.length()) {
                        0 -> Toast.makeText(this, "Place not found", Toast.LENGTH_SHORT).show()
                        1 -> {
                            val o = arr.getJSONObject(0)
                            goTo(GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble()))
                        }
                        else -> {
                            val names = Array(arr.length()) {
                                arr.getJSONObject(it).optString("display_name", "Result").take(90)
                            }
                            AlertDialog.Builder(this)
                                .setTitle("Choose a result")
                                .setItems(names) { _, i ->
                                    val o = arr.getJSONObject(i)
                                    goTo(
                                        GeoPoint(
                                            o.getString("lat").toDouble(),
                                            o.getString("lon").toDouble()
                                        )
                                    )
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Search failed. Check your internet.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---------- Favorites ----------
    private fun loadFavs(): JSONArray =
        try { JSONArray(prefs.getString("favs", "[]")) } catch (e: Exception) { JSONArray() }

    private fun saveFavorite() {
        val p = selected
        if (p == null) {
            Toast.makeText(this, "Choose a location on the map first", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "Name (e.g. Home, Office)"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Save this pin")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Pin" }
                val arr = loadFavs()
                arr.put(
                    JSONObject().put("name", name)
                        .put("lat", p.latitude).put("lon", p.longitude)
                )
                prefs.edit().putString("favs", arr.toString()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFavorites() {
        val arr = loadFavs()
        if (arr.length() == 0) {
            Toast.makeText(this, "No favorites yet. Use Save pin.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = Array(arr.length()) { arr.getJSONObject(it).getString("name") }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Favorites (tap = go, long-press = delete)")
            .setItems(names) { _, i ->
                val o = arr.getJSONObject(i)
                goTo(GeoPoint(o.getDouble("lat"), o.getDouble("lon")))
            }
            .setNegativeButton("Close", null)
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle("Delete \"${names[pos]}\"?")
                .setPositiveButton("Delete") { _, _ ->
                    val keep = JSONArray()
                    for (k in 0 until arr.length()) if (k != pos) keep.put(arr.getJSONObject(k))
                    prefs.edit().putString("favs", keep.toString()).apply()
                    dlg.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    // ---------- Start / Move ----------
    private fun onStartClicked() {
        val p = selected
        if (p == null) {
            Toast.makeText(this, "Tap the map to choose a location first", Toast.LENGTH_LONG).show()
            return
        }

        val needed = mutableListOf<String>()
        if (!hasFineLocation()) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isNotEmpty()) {
            pending = p
            permLauncher.launch(needed.toTypedArray())
        } else {
            startMock(p.latitude, p.longitude)
        }
    }

    private fun onMoveClicked() {
        val dest = selected
        if (dest == null) {
            Toast.makeText(this, "Tap the map to choose a destination first", Toast.LENGTH_LONG).show()
            return
        }
        if (!MockLocationService.running) {
            Toast.makeText(
                this,
                "Tap Start here first to set your starting point, then pick a new pin and tap Move",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (chipJoy.isChecked) chipJoy.isChecked = false
        val from = GeoPoint(MockLocationService.curLat, MockLocationService.curLon)
        val waypoints = listOf(from) + stops.toList() + dest
        val kmh = prefs.getInt("speedKmh", 5)
        Toast.makeText(this, "Finding route...", Toast.LENGTH_SHORT).show()

        Thread {
            val route = fetchRoute(waypoints, kmh)
            runOnUiThread {
                val pts: List<GeoPoint>
                if (route == null) {
                    Toast.makeText(
                        this,
                        "Route not available, moving in a straight line",
                        Toast.LENGTH_LONG
                    ).show()
                    pts = waypoints
                } else {
                    pts = route
                }
                val full = listOf(from) + pts
                drawRoute(full)
                val meters = routeMeters(full)
                val mins = (meters / MockLocationService.targetSpeedMs.coerceAtLeast(0.5f) / 60.0).toInt()
                Toast.makeText(
                    this,
                    "Route ${fmtDist(meters)}, about ${if (mins < 1) "<1" else mins.toString()} min",
                    Toast.LENGTH_SHORT
                ).show()
                val lats = DoubleArray(full.size) { full[it].latitude }
                val lons = DoubleArray(full.size) { full[it].longitude }
                startService(
                    Intent(this, MockLocationService::class.java)
                        .setAction(MockLocationService.ACTION_MOVE)
                        .putExtra(MockLocationService.EXTRA_ROUTE_LAT, lats)
                        .putExtra(MockLocationService.EXTRA_ROUTE_LON, lons)
                )
                clearStops()
                follow = true
                map.controller.animateTo(from)
                ui.postDelayed({ refreshLive() }, 400)
            }
        }.start()
    }

    /** Road/footpath route through all points, from OpenStreetMap's free routing servers. Null on failure. */
    private fun fetchRoute(points: List<GeoPoint>, kmh: Int): List<GeoPoint>? {
        return try {
            val profile = when {
                kmh < 8 -> "routed-foot"
                kmh < 25 -> "routed-bike"
                else -> "routed-car"
            }
            val coords = points.joinToString(";") {
                String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
            }
            val u = "https://routing.openstreetmap.de/$profile/route/v1/driving/$coords" +
                "?overview=full&geometries=geojson"
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MockLoc/1.0 (personal use)")
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.optString("code") != "Ok") return null
            val line = o.getJSONArray("routes").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")

            // Keep the intent small: thin out very long routes
            val n = line.length()
            val stride = if (n > 3000) n / 3000 + 1 else 1
            val out = ArrayList<GeoPoint>()
            var i = 0
            while (i < n) {
                val c = line.getJSONArray(i)
                out.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                i += stride
            }
            out.add(points.last()) // finish exactly on the pin
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun startMock(lat: Double, lon: Double) {
        if (!MockLocationService.running && !MockLocationService.isMockAllowed(this)) {
            AlertDialog.Builder(this)
                .setTitle("Set mock location app")
                .setMessage(
                    "Go to Developer options > Select mock location app, and choose Mock Loc. " +
                        "Then come back and tap Start again."
                )
                .setPositiveButton("Open Developer options") { _, _ -> openDevOptions() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (!MockLocationService.running) {
            val tm = prefs.getInt("timerMin", 0)
            MockLocationService.stopAtMs =
                if (tm == 0) 0L else SystemClock.elapsedRealtime() + tm * 60_000L
        }
        val i = Intent(this, MockLocationService::class.java)
            .setAction(MockLocationService.ACTION_START)
            .putExtra(MockLocationService.EXTRA_LAT, lat)
            .putExtra(MockLocationService.EXTRA_LON, lon)
        ContextCompat.startForegroundService(this, i)
        follow = false
        clearRoute()
        suppressArrivalUntil = SystemClock.uptimeMillis() + 2000
        status.text = "Starting..."
        ui.postDelayed({ refreshLive() }, 400)
        maybeAskBattery()
    }

    private fun maybeAskBattery() {
        if (prefs.getBoolean("batteryAsked", false)) return
        prefs.edit().putBoolean("batteryAsked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep it running")
            .setMessage(
                "For stable mocking, open App info > Battery and choose Unrestricted, " +
                    "so Android doesn't stop Mock Loc in the background."
            )
            .setPositiveButton("Open App info") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Enable Developer options first: Settings > About phone > tap Build number 7 times",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
