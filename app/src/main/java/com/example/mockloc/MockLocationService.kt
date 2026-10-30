package com.example.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.mockloc.START"
        const val ACTION_STOP = "com.example.mockloc.STOP"
        const val ACTION_MOVE = "com.example.mockloc.MOVE"
        const val ACTION_TOGGLE_PAUSE = "com.example.mockloc.TOGGLE_PAUSE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_DEST_LAT = "dlat"
        const val EXTRA_DEST_LON = "dlon"
        const val EXTRA_ROUTE_LAT = "rlat"
        const val EXTRA_ROUTE_LON = "rlon"
        private const val CHANNEL_ID = "mockloc"
        private const val NOTIF_ID = 1

        // Live state shared with the UI (same process)
        @Volatile var running = false
        @Volatile var curLat = 0.0
        @Volatile var curLon = 0.0
        @Volatile var moving = false
        @Volatile var paused = false
        @Volatile var remaining = 0.0
        @Volatile var speedNow = 0f
        @Volatile var targetSpeedMs = 1.4f // can be changed any time, even while moving
        @Volatile var loop = false         // go back and forth along the route
        @Volatile var joyActive = false    // manual joystick steering
        @Volatile var joyX = 0f            // -1..1, right is positive
        @Volatile var joyY = 0f            // -1..1, up (north) is negative
        @Volatile var stopRequested = false // set by the Quick Settings tile
        @Volatile var stopAtMs = 0L         // auto-stop time (SystemClock.elapsedRealtime), 0 = off

        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        /** True if this app is currently selected as the mock location app. */
        fun isMockAllowed(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                val p = LocationManager.GPS_PROVIDER
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                addProvider(lm, p)
                lm.removeTestProvider(p)
                true
            } catch (e: SecurityException) {
                false
            }
        }

        @Suppress("DEPRECATION")
        private fun addProvider(lm: LocationManager, name: String) {
            lm.addTestProvider(
                name,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            lm.setTestProviderEnabled(name, true)
        }
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var fusedOn = false

    // Route being followed
    private var wpLat = DoubleArray(0)
    private var wpLon = DoubleArray(0)
    private var suffix = DoubleArray(0) // suffix[i] = metres from waypoint i to the end
    private var wpIdx = 0

    private var curSpeed = 0f
    private var curBearing = 0f

    private val tick = object : Runnable {
        override fun run() {
            val timedOut = stopAtMs > 0L && SystemClock.elapsedRealtime() >= stopAtMs
            if (stopRequested || timedOut) {
                stopRequested = false
                stopAtMs = 0L
                if (timedOut) {
                    Toast.makeText(applicationContext, "Mock Loc stopped (timer)", Toast.LENGTH_LONG).show()
                }
                shutdown()
                return
            }
            step()
            pushLocation()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                stopRequested = false
                curLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                curLon = intent.getDoubleExtra(EXTRA_LON, 0.0)
                moving = false
                paused = false
                remaining = 0.0
                curSpeed = 0f
                speedNow = 0f
                val notification = buildNotification()
                if (!running) {
                    goForeground(notification)
                    try {
                        PROVIDERS.forEach {
                            try { lm.removeTestProvider(it) } catch (_: Exception) {}
                            addProvider(lm, it)
                        }
                    } catch (e: SecurityException) {
                        Toast.makeText(
                            this,
                            "Select Mock Loc as the mock location app in Developer options",
                            Toast.LENGTH_LONG
                        ).show()
                        shutdown()
                        return START_NOT_STICKY
                    }
                    enableFused()
                    acquireWakeLock()
                    running = true
                    handler.post(tick)
                } else {
                    updateNotification()
                    pushLocation()
                }
            }

            ACTION_MOVE -> {
                if (running) {
                    val lats = intent.getDoubleArrayExtra(EXTRA_ROUTE_LAT)
                    val lons = intent.getDoubleArrayExtra(EXTRA_ROUTE_LON)
                    if (lats != null && lons != null && lats.isNotEmpty() && lats.size == lons.size) {
                        setRoute(lats, lons)
                    } else {
                        setRoute(
                            doubleArrayOf(intent.getDoubleExtra(EXTRA_DEST_LAT, curLat)),
                            doubleArrayOf(intent.getDoubleExtra(EXTRA_DEST_LON, curLon))
                        )
                    }
                    paused = false
                    moving = true
                    remaining = remainingDistance()
                    updateNotification()
                }
            }

            ACTION_TOGGLE_PAUSE -> {
                if (running && moving) {
                    paused = !paused
                    updateNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Movement along a route ----------
    private fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(la1, lo1, la2, lo2, r)
        return r[0]
    }

    private fun setRoute(lats: DoubleArray, lons: DoubleArray) {
        wpLat = lats
        wpLon = lons
        wpIdx = 0
        val n = lats.size
        suffix = DoubleArray(n)
        for (i in n - 2 downTo 0) {
            suffix[i] = suffix[i + 1] + dist(lats[i], lons[i], lats[i + 1], lons[i + 1])
        }
    }

    private fun remainingDistance(): Double =
        if (wpIdx >= wpLat.size) 0.0
        else dist(curLat, curLon, wpLat[wpIdx], wpLon[wpIdx]) + suffix[wpIdx]

    private fun advance(bearingRad: Double, meters: Double) {
        val d = meters / 6371000.0
        val la1 = Math.toRadians(curLat)
        val lo1 = Math.toRadians(curLon)
        val la2 = asin(sin(la1) * cos(d) + cos(la1) * sin(d) * cos(bearingRad))
        val lo2 = lo1 + atan2(
            sin(bearingRad) * sin(d) * cos(la1),
            cos(d) - sin(la1) * sin(la2)
        )
        curLat = Math.toDegrees(la2)
        curLon = ((Math.toDegrees(lo2) + 540.0) % 360.0) - 180.0
    }

    /** Manual steering: stick direction = compass bearing (up = north), stick size = speed. */
    private fun joystickStep() {
        val x = joyX
        val y = joyY
        val m = minOf(1f, hypot(x.toDouble(), y.toDouble()).toFloat())
        val active = m >= 0.12f
        val target = if (active) targetSpeedMs * m else 0f
        val delta = (target - curSpeed).coerceIn(-2.0f, 1.5f)
        curSpeed = (curSpeed + delta).coerceAtLeast(0f)
        if (curSpeed < 0.05f) curSpeed = 0f
        if (active) {
            var b = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
            if (b < 0f) b += 360f
            curBearing = b
        }
        speedNow = curSpeed
        remaining = 0.0
        if (curSpeed > 0f) advance(Math.toRadians(curBearing.toDouble()), curSpeed.toDouble())
    }

    /** Advance one second along the route, with gentle acceleration and braking. */
    private fun step() {
        if (joyActive) {
            moving = false
            paused = false
            joystickStep()
            return
        }
        if (!moving) return
        if (paused) {
            curSpeed = 0f
            speedNow = 0f
            return
        }

        val left = remainingDistance()
        var target = targetSpeedMs
        if (left <= curSpeed * curSpeed / 3.0) target = minOf(target, 1.0f)
        val delta = (target - curSpeed).coerceIn(-1.5f, 1.2f)
        curSpeed = (curSpeed + delta).coerceAtLeast(0.8f)
        speedNow = curSpeed

        var budget = curSpeed.toDouble()
        val res = FloatArray(2)
        while (budget > 0.0 && wpIdx < wpLat.size) {
            Location.distanceBetween(curLat, curLon, wpLat[wpIdx], wpLon[wpIdx], res)
            val d = res[0].toDouble()
            if (d > 0.5) {
                var b = res[1]
                if (b < 0f) b += 360f
                curBearing = b
            }
            if (d <= budget) {
                curLat = wpLat[wpIdx]
                curLon = wpLon[wpIdx]
                budget -= d
                wpIdx++
            } else {
                advance(Math.toRadians(res[1].toDouble()), budget)
                budget = 0.0
            }
        }

        remaining = remainingDistance()
        if (wpIdx >= wpLat.size && loop && wpLat.size > 1) {
            // Turn around and walk the route back
            setRoute(wpLat.reversedArray(), wpLon.reversedArray())
            remaining = remainingDistance()
        } else if (wpIdx >= wpLat.size) {
            moving = false
            remaining = 0.0
            curSpeed = 0f
            speedNow = 0f
            updateNotification()
        }
    }

    // ---------- Providers ----------
    /** Many apps (WhatsApp, Maps...) read Google's Fused provider, so mock it as well. */
    private fun enableFused() {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .setMockMode(true)
                .addOnSuccessListener { fusedOn = true }
                .addOnFailureListener { fusedOn = false }
        } catch (_: Throwable) {
            fusedOn = false
        }
    }

    private fun disableFused() {
        try {
            if (fusedOn) {
                LocationServices.getFusedLocationProviderClient(this).setMockMode(false)
            }
        } catch (_: Throwable) {
        }
        fusedOn = false
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockloc:service").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun makeLocation(provider: String) = Location(provider).apply {
        latitude = curLat
        longitude = curLon
        altitude = 0.0
        accuracy = 3f
        speed = curSpeed
        bearing = curBearing
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun pushLocation() {
        PROVIDERS.forEach { name ->
            try {
                lm.setTestProviderLocation(name, makeLocation(name))
            } catch (_: Exception) {
            }
        }
        if (fusedOn) {
            try {
                LocationServices.getFusedLocationProviderClient(this)
                    .setMockLocation(makeLocation("fused"))
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- Notification / lifecycle ----------
    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(if (paused) "Mock location paused" else "Mock location active")
            .setContentText("%.6f, %.6f".format(curLat, curLon))
            .setContentIntent(open)
        if (moving) {
            val toggle = PendingIntent.getService(
                this, 2,
                Intent(this, MockLocationService::class.java).setAction(ACTION_TOGGLE_PAUSE),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, if (paused) "Resume" else "Pause", toggle)
        }
        return builder
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun cleanup() {
        handler.removeCallbacks(tick)
        PROVIDERS.forEach {
            try { lm.removeTestProvider(it) } catch (_: Exception) {}
        }
        disableFused()
        releaseWakeLock()
        moving = false
        paused = false
        joyActive = false
        joyX = 0f
        joyY = 0f
        stopAtMs = 0L
        remaining = 0.0
        speedNow = 0f
        running = false
    }

    private fun shutdown() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
