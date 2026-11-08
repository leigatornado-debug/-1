package com.example.virtualgps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.min

class JoystickOverlay(
    context: Context,
    private val onMove: (Float, Float) -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val joystick = JoystickView(context)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        220,
        220,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 240
    }

    fun show() {
        if (!attached) {
            windowManager.addView(joystick, params)
            attached = true
        }
    }

    fun dismiss() {
        if (attached) {
            windowManager.removeView(joystick)
            attached = false
            onMove(0f, 0f)
        }
    }

    private inner class JoystickView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var knobX = 0f
        private var knobY = 0f
        private var centerX = 0f
        private var centerY = 0f
        private var radius = 1f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            centerX = width / 2f
            centerY = height / 2f
            radius = min(width, height) * 0.42f

            paint.color = Color.argb(150, 70, 70, 70)
            canvas.drawCircle(centerX, centerY, radius, paint)

            if (knobX == 0f && knobY == 0f) {
                knobX = centerX
                knobY = centerY
            }
            paint.color = Color.argb(220, 30, 130, 255)
            canvas.drawCircle(knobX, knobY, radius * 0.32f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - centerX
                    val dy = event.y - centerY
                    val maxDistance = radius * 0.65f
                    val length = hypot(dx, dy).coerceAtLeast(1f)
                    val scale = min(1f, maxDistance / length)
                    knobX = centerX + dx * scale
                    knobY = centerY + dy * scale

                    val usable = maxDistance.coerceAtLeast(1f)
                    onMove((knobX - centerX) / usable, (knobY - centerY) / usable)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knobX = centerX
                    knobY = centerY
                    onMove(0f, 0f)
                    invalidate()
                    return true
                }
            }
            return true
        }
    }
}
