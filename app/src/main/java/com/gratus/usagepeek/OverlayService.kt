package com.gratus.usagepeek

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import android.app.usage.UsageEvents              // NEW
import androidx.core.view.isVisible
import kotlinx.coroutines.*                        // NEW
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap     // NEW


class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usageTotals   = ConcurrentHashMap<String, Long>()   // seconds today per package
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var overlayEnabledPkgs: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // ---------- 1) notification FIRST ----------
        createNotificationChannelIfNeeded()
        val notif = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_stat_usagepeek)
            .setContentTitle("UsagePeek running")
            .setOngoing(true)
            .build()
        startForeground(1, notif)                 // ← must happen ≤5 s
        // ---------- 2) set up window & view -------
        windowManager = getSystemService()!!
        bubbleView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_bubble, null)

        // start the 1-second coroutine *once*
        startTimerLoop()

        AppPrefs.enabledPackages(this).onEach { overlayEnabledPkgs = it }
            .launchIn(serviceScope)      // needs kotlinx-coroutines-core already present

        // ---------- 3) drag handling --------------
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
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun startTimerLoop() {
        if (serviceScope.coroutineContext[Job]?.children?.any { it.isActive } == true) return
        val usageStats = getSystemService(UsageStatsManager::class.java)

        serviceScope.launch {
            var lastPackage = ""
            var lastTick    = SystemClock.elapsedRealtime()

            while (isActive) {
                val nowPkg = foregroundPackage(usageStats, lastPackage)
                val now    = SystemClock.elapsedRealtime()

                val delta  = (now - lastTick) / 1000     // seconds since last loop
                if (delta > 0 && lastPackage.isNotBlank()) {
                    usageTotals.merge(lastPackage, delta) { a, b -> a + b }
                }

                lastTick    = now
                lastPackage = nowPkg

                // update bubble only when showing on a permitted app (we'll filter later)
                withContext(Dispatchers.Main) {
                    val tv = bubbleView.findViewById<TextView>(R.id.bubbleText)
                    val secs = usageTotals[nowPkg] ?: 0L
                    tv.text  = formatHMS(secs)
                    val show = nowPkg in overlayEnabledPkgs
                    bubbleView.isVisible = show              // import androidx.core.view.isVisible
                    if (show) {
                        val secs = usageTotals[nowPkg] ?: 0L
                        bubbleView.findViewById<TextView>(R.id.bubbleText).text = formatHMS(secs)
                    }

                }

                delay(1000)       // 1-second cadence keeps CPU <1 %
            }
        }
    }

    // Replace your current foregroundPackage() with this version
    private fun foregroundPackage(
        usm: UsageStatsManager,
        lastKnown: String        // <-- new param
    ): String {
        val end   = System.currentTimeMillis()
        val begin = end - 60_000              // look back 60 s instead of 10 s

        val events = usm.queryEvents(begin, end)
        var latestT = 0L
        var latestP = ""

        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                e.timeStamp     > latestT) {
                latestT = e.timeStamp
                latestP = e.packageName
            }
        }
        return if (latestP.isNotBlank()) latestP else lastKnown   // ← keep last
    }

    private fun formatHMS(totalSecs: Long): String {
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return "%02d:%02d:%02d".format(h, m, s)
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
        serviceScope.cancel()
    }
}
