package com.example.virtualgps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.max

class MockLocationService : Service() {
    companion object {
        private const val CHANNEL_ID = "mock_location_test"
        private const val NOTIFICATION_ID = 101
        private const val PROVIDER = LocationManager.GPS_PROVIDER
    }

    inner class LocalBinder : Binder() {
        fun service(): MockLocationService = this@MockLocationService
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var latitude = 39.909
    private var longitude = 116.397
    private var speedMps = 1000.0 / 360.0
    private var joystickX = 0f
    private var joystickY = 0f
    private var route: List<RoutePoint> = emptyList()
    private var routeDistance = 0.0
    private var routePlaying = false
    private var lastTickMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val deltaSeconds = if (lastTickMs == 0L) 1.0
            else ((now - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
            lastTickMs = now

            if (routePlaying && route.size >= 2) {
                routeDistance += speedMps * deltaSeconds
                val total = totalRouteDistance()
                if (routeDistance >= total) {
                    routeDistance = total
                    routePlaying = false
                }
                val point = pointAtDistance(route, routeDistance)
                latitude = point.latitude
                longitude = point.longitude
            } else if (joystickX != 0f || joystickY != 0f) {
                val eastMeters = joystickX * speedMps * deltaSeconds
                val northMeters = -joystickY * speedMps * deltaSeconds
                latitude += northMeters / 111_320.0
                val longitudeScale = max(0.01, cos(Math.toRadians(latitude)))
                longitude += eastMeters / (111_320.0 * longitudeScale)
                longitude = ((longitude + 540.0) % 360.0) - 180.0
                latitude = latitude.coerceIn(-90.0, 90.0)
            }

            publishLocation()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initializeTestProvider()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra("lat")) latitude = it.getDoubleExtra("lat", latitude)
            if (it.hasExtra("lon")) longitude = it.getDoubleExtra("lon", longitude)
            if (it.hasExtra("speed")) speedMps = it.getDoubleExtra("speed", speedMps)
        }
        publishLocation()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setJoystick(x: Float, y: Float) {
        joystickX = x.coerceIn(-1f, 1f)
        joystickY = y.coerceIn(-1f, 1f)
        if (joystickX != 0f || joystickY != 0f) routePlaying = false
    }

    fun playRoute(points: List<RoutePoint>, pace: Pace) {
        require(points.size >= 2) { "路线至少需要两个点" }
        route = points
        routeDistance = 0.0
        speedMps = pace.metersPerSecond()
        latitude = points.first().latitude
        longitude = points.first().longitude
        routePlaying = true
        joystickX = 0f
        joystickY = 0f
        publishLocation()
    }

    fun stopMovement() {
        routePlaying = false
        joystickX = 0f
        joystickY = 0f
    }

    private fun initializeTestProvider() {
        try {
            locationManager.addTestProvider(
                PROVIDER,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(PROVIDER, true)
        } catch (_: SecurityException) {
            // Select this app as the mock location app in Developer options.
        } catch (_: IllegalArgumentException) {
            // Provider may already exist or be unsupported by this Android build.
        } catch (_: RuntimeException) {
            // Device/ROM-specific test-provider behavior.
        }
    }

    private fun publishLocation() {
        try {
            val location = Location(PROVIDER).apply {
                latitude = this@MockLocationService.latitude
                longitude = this@MockLocationService.longitude
                altitude = 0.0
                accuracy = 5f
                speed = speedMps.toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(PROVIDER, location)
        } catch (_: SecurityException) {
            // Check the mock-location app selected in Developer options.
        } catch (_: IllegalArgumentException) {
            // Provider setup may not be supported on this device.
        } catch (_: RuntimeException) {
            // Device/ROM-specific behavior.
        }
    }

    private fun totalRouteDistance(): Double =
        route.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    private fun pointAtDistance(points: List<RoutePoint>, meters: Double): RoutePoint {
        var remaining = meters
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val segment = distance(a, b)
            if (segment > 0.0 && remaining <= segment) {
                val t = (remaining / segment).coerceIn(0.0, 1.0)
                return RoutePoint(
                    a.latitude + (b.latitude - a.latitude) * t,
                    a.longitude + (b.longitude - a.longitude) * t
                )
            }
            remaining -= segment
        }
        return points.last()
    }

    private fun distance(a: RoutePoint, b: RoutePoint): Double {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0].toDouble()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "测试定位服务",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("测试定位运行中")
            .setContentText("模拟位置会明确标记为测试数据")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            locationManager.removeTestProvider(PROVIDER)
        } catch (_: RuntimeException) {
        }
        super.onDestroy()
    }
}
