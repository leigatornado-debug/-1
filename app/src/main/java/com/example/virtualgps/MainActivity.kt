package com.example.virtualgps

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var service: MockLocationService? = null
    private var bound = false
    private var overlay: JoystickOverlay? = null

    private lateinit var status: TextView
    private lateinit var paceInput: EditText
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var routeInput: EditText

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MockLocationService.LocalBinder).service()
            bound = true
            status.text = "状态：服务已连接；位置由系统作为测试位置处理"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        paceInput = findViewById(R.id.paceInput)
        latitudeInput = findViewById(R.id.latitudeInput)
        longitudeInput = findViewById(R.id.longitudeInput)
        routeInput = findViewById(R.id.routeInput)

        findViewById<Button>(R.id.startButton).setOnClickListener { startTestService() }
        findViewById<Button>(R.id.overlayButton).setOnClickListener { toggleOverlay() }
        findViewById<Button>(R.id.sampleRouteButton).setOnClickListener {
            routeInput.setText("39.9090,116.3970;39.9095,116.3980;39.9100,116.3990")
        }
        findViewById<Button>(R.id.routeButton).setOnClickListener {
            try {
                val points = parseRoute(routeInput.text.toString())
                val pace = Pace.parse(paceInput.text.toString())
                val current = service
                if (current == null) toast("请先启动测试定位")
                else {
                    current.playRoute(points, pace)
                    status.text = "状态：路线播放中，配速 ${paceInput.text} min/km"
                }
            } catch (e: Exception) {
                toast(e.message ?: "路线或配速格式不正确")
            }
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopTestService() }
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
    }

    private fun startTestService() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast("请先授予精确位置权限")
            requestPermissionsIfNeeded()
            return
        }

        try {
            val pace = Pace.parse(paceInput.text.toString())
            val lat = latitudeInput.text.toString().toDouble()
            val lon = longitudeInput.text.toString().toDouble()
            require(lat in -90.0..90.0 && lon in -180.0..180.0) {
                "纬度或经度超出有效范围"
            }

            val intent = Intent(this, MockLocationService::class.java).apply {
                putExtra("lat", lat)
                putExtra("lon", lon)
                putExtra("speed", pace.metersPerSecond())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
            status.text = "状态：正在启动。请确认开发者选项已选择本 App 为模拟位置应用。"
        } catch (e: Exception) {
            toast(e.message ?: "输入内容不正确")
        }
    }

    private fun toggleOverlay() {
        if (service == null) {
            toast("请先启动测试定位")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (overlay == null) {
            overlay = JoystickOverlay(this) { x, y -> service?.setJoystick(x, y) }
            overlay?.show()
        } else {
            overlay?.dismiss()
            overlay = null
        }
    }

    private fun parseRoute(text: String): List<RoutePoint> = text.trim()
        .split(";")
        .map { item ->
            val pair = item.trim().split(",")
            require(pair.size == 2) { "路线点格式应为 纬度,经度" }
            val lat = pair[0].trim().toDouble()
            val lon = pair[1].trim().toDouble()
            require(lat in -90.0..90.0 && lon in -180.0..180.0) {
                "路线点坐标超出有效范围"
            }
            RoutePoint(lat, lon)
        }
        .also { require(it.size >= 2) { "路线至少需要两个点" } }

    private fun stopTestService() {
        overlay?.dismiss()
        overlay = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        stopService(Intent(this, MockLocationService::class.java))
        status.text = "状态：已停止"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        overlay?.dismiss()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
