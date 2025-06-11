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
import android.app.usage.UsageEvents
import androidx.core.view.isVisible
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gratus.usagepeek.data.AppEntity
import com.gratus.usagepeek.data.SessionEntity
import com.gratus.usagepeek.data.UsageDao
import com.gratus.usagepeek.data.UsageDb
import com.gratus.usagepeek.data.ResetWorker
import kotlinx.coroutines.*                        // NEW
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap     // NEW
import java.util.concurrent.TimeUnit


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
    // Room database related
    private lateinit var dao: UsageDao
    private var lastStartTs = System.currentTimeMillis()

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

        dao = UsageDb.get(this).dao()
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

        // Schedule it (reset-worker) once per day.
        val request = PeriodicWorkRequestBuilder<ResetWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(
                Duration.between(
                    LocalDateTime.now(),
                    LocalDateTime.now().with(LocalTime.MAX)
                )
            )
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("daily-reset", ExistingPeriodicWorkPolicy.KEEP, request)


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
            var currentPackage = ""
            var lastTick    = SystemClock.elapsedRealtime()

            while (isActive) {
                val nowPkg = foregroundPackage(usageStats, currentPackage)
                val now    = SystemClock.elapsedRealtime()
                val delta  = ((now - lastTick) / 1000).toInt()

                if (delta > 0 && currentPackage.isNotBlank()) {
                    usageTotals.merge(currentPackage, delta.toLong()) { a, b -> a + b }

                    serviceScope.launch {
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(System.currentTimeMillis())
                        dao.ensureAppRow(AppEntity(currentPackage, currentPackage))   // label later
                        dao.insertSession(
                            SessionEntity(
                                packageName = currentPackage,
                                startTs = lastTick,
                                endTs   = lastTick + 1000,
                                durationSec = 1
                            )
                        )
                        dao.upsertDaily(currentPackage, today, 1)
                    }
                }

                lastTick    = now
                currentPackage = nowPkg

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
