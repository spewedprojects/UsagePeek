package com.gratus.usagepeek

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService()!!

        bubbleView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.x = 0
        params.y = 200

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    // ------------------ foreground notification ------------------
                    createNotificationChannelIfNeeded()
                    val notif = NotificationCompat.Builder(this, "overlay")
                        .setSmallIcon(R.drawable.ic_stat_usagepeek)
                        .setContentTitle("UsagePeek running")
                        .setOngoing(true)
                        .build()

                    startForeground(1, notif)
                    // -------------------------------------------------------------
                    true
                }

                else -> false

            }
        }
        windowManager.addView(bubbleView, params)
    }

    private fun createNotificationChannelIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "overlay",
                "UsagePeek Overlay",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY


    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(bubbleView)
    }
}
