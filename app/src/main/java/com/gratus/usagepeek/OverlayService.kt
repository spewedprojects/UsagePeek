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
import android.util.Log
import androidx.core.view.isVisible
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.type.Date
import com.gratus.usagepeek.data.AppEntity
import com.gratus.usagepeek.data.UsageDao
import com.gratus.usagepeek.data.UsageDb
import com.gratus.usagepeek.data.ResetWorker
import kotlinx.coroutines.*                        // NEW
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private var currentPackage = ""
    private var sessionStart   = 0L
    private var sessionCommitted = false

    // ---------- Place 1 (corrected) : paste immediately AFTER `private var sessionCommitted = false` ----------

    // ... existing variables ...
    private lateinit var powerManager: android.os.PowerManager
    private var isScreenOn = true

    // Receiver to handle Screen ON/OFF events
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    // Force commit current session immediately when screen goes dark
                    serviceScope.launch {
                        // The loop is paused, so we manually trigger a cleanup/commit here if needed
                        // or just let the next loop iteration handle the pause logic
                    }
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    // Reset lastStartTs or wake up logic if needed
                }
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    companion object {
        // if monotonic gap > this, we treat previous session as interrupted/rebooted
        private const val MONO_GAP_THRESHOLD_MS = 120_000L
        private val DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
    }

    private fun saveSessionState(pkg: String, startWall: Long, startMono: Long) {
        prefs.edit()
            .putString("session_pkg", pkg)
            .putLong("session_start_wall", startWall)
            .putLong("session_start_mono", startMono)
            .apply()
    }

    private fun clearSessionState() {
        prefs.edit()
            .remove("session_pkg")
            .remove("session_start_wall")
            .remove("session_start_mono")
            .apply()
    }

    /**
     * Commit a session safely: splits across midnight if needed, inserts SessionEntity
     * and upserts daily totals. Runs DB ops on IO dispatcher.
     */
    private fun commitSession(packageName: String, startWall: Long, endWall: Long) {
        if (packageName.isBlank() || endWall <= startWall) {
            clearSessionState()
            return
        }

        val startDay = DATE_FMT.format(java.util.Date(startWall))
        val endDay = DATE_FMT.format(java.util.Date(endWall))

        // use IO dispatcher for DB ops
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // ensure app row exists
                dao.ensureAppRow(com.gratus.usagepeek.data.AppEntity(packageName, packageName))

                if (startDay == endDay) {
                    val duration = ((endWall - startWall) / 1000).toInt().coerceAtLeast(0)
                    if (duration > 0) {

                        dao.upsertDaily(packageName, startDay, duration)
                    }
                } else {
                    // compute midnight millis for the start of endDay using java.util.Calendar to avoid import conflicts
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = endWall }
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    val midnight = cal.timeInMillis // midnight at start of endDay

                    val firstDur = ((midnight - startWall) / 1000).toInt().coerceAtLeast(0)
                    val secondDur = ((endWall - midnight) / 1000).toInt().coerceAtLeast(0)

                    if (firstDur > 0) {

                        dao.upsertDaily(packageName, startDay, firstDur)
                    }
                    if (secondDur > 0) {

                        dao.upsertDaily(packageName, endDay, secondDur)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "commitSession DB error", e)
            } finally {
                clearSessionState()
            }
        }
    }
// ---------- end corrected Place 1 ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        powerManager = getSystemService(android.os.PowerManager::class.java)
        isScreenOn = powerManager.isInteractive

        // Register Screen receiver
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

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
        // ---------- Place 2: restore saved session (paste right after `dao = UsageDb.get(this).dao()`) ----------
        run {
            val savedPkg = prefs.getString("session_pkg", "") ?: ""
            val savedStartWall = prefs.getLong("session_start_wall", 0L)
            val savedStartMono = prefs.getLong("session_start_mono", 0L)

            if (savedPkg.isNotBlank() && savedStartWall > 0L) {
                val nowWall = System.currentTimeMillis()
                val nowMono = SystemClock.elapsedRealtime()
                val monoDiff = nowMono - savedStartMono

                if (monoDiff > MONO_GAP_THRESHOLD_MS || nowMono < savedStartMono) {
                    // Long gap detected (possible reboot / forced-stop). Commit a minimal safe credit
                    val approxEnd = savedStartWall + 1000L
                    val duration = ((approxEnd - savedStartWall) / 1000).toInt()
                    if (duration > 0) {
                        val date = DATE_FMT.format(java.util.Date(savedStartWall))
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                dao.ensureAppRow(com.gratus.usagepeek.data.AppEntity(savedPkg, savedPkg))

                                dao.upsertDaily(savedPkg, date, duration)
                            } catch (e: Exception) {
                                android.util.Log.e("OverlayService", "recover commit error", e)
                            } finally {
                                clearSessionState()
                            }
                        }
                    } else {
                        clearSessionState()
                    }
                } else {
                    // treat as continuation: reconstruct wall-clock start from monotonic offset
                    val restoredStartWall = nowWall - monoDiff
                    sessionStart = restoredStartWall
                    currentPackage = savedPkg
                    sessionCommitted = false
                    saveSessionState(currentPackage, sessionStart, SystemClock.elapsedRealtime())
                }
            }
        }
// ---------- end Place 2 ----------

        // ---------- OverlayService: seed usageTotals from today's DB totals (paste after Place 2 block in onCreate) ----------
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val today = DATE_FMT.format(java.util.Date(System.currentTimeMillis()))
                val totals = dao.totalsForDate(today)   // new DAO call
                for (row in totals) {
                    // dao stores seconds as Int; keep same units (seconds)
                    usageTotals[row.packageName] = row.seconds.toLong()
                }
                // optional: checkpoint to ensure DB is stable (not strictly required)
                dao.checkpointDb(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "failed to seed usageTotals", e)
            }
        }
// ---------- end seed snippet ----------


        // wait for seed to complete before starting loop (optional)
        runBlocking {
            val today = DATE_FMT.format(java.util.Date(System.currentTimeMillis()))
            val totals = dao.totalsForDate(today)
            for (row in totals) usageTotals[row.packageName] = row.seconds.toLong()
        }
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
            try {
                while (isActive) {
                    // 1. BATTERY SAVER: specific check for screen state
                    if (!isScreenOn) {
                        // If screen is off, we are definitely NOT using any app.
                        // Commit any open session and wait.
                        if (currentPackage.isNotBlank()) {
                            val nowWall = System.currentTimeMillis()
                            commitSession(currentPackage, sessionStart, nowWall)
                            currentPackage = ""
                            sessionStart = 0L
                            sessionCommitted = false
                            clearSessionState()

                            withContext(Dispatchers.Main) { bubbleView.isVisible = false }
                        }

                        // Sleep for a long time or until screen turns on.
                        // checking every 2 seconds is low cost if just checking a boolean
                        delay(2000)
                        continue
                    }

                    // 2. RETRIEVE PACKAGE (Logic Fixed)
                    // We pass 'currentPackage' as the default. If queryEvents finds nothing
                    // (e.g. user watching a movie), it returns 'currentPackage' instead of empty.
                    val nowPkg = getForegroundPackageOptimized(usageStats, currentPackage)
                    val now    = System.currentTimeMillis()

                    // ... [The rest of your original logic stays mostly the same] ...

                    if (currentPackage.isBlank()) {
                        currentPackage = nowPkg
                        sessionStart   = now
                    }

                    if (nowPkg == currentPackage && nowPkg.isNotBlank()) {
                        // LOGIC: App matches, increment time
                        usageTotals.merge(nowPkg, 1L) { a, b -> a + b }

                        val durationSoFar = ((now - sessionStart) / 1000).toInt()

                        // Commit initial "credit" after 5 seconds
                        if (!sessionCommitted && durationSoFar >= 5) {
                            // ... [Your existing DB insert logic] ...
                            sessionCommitted = true
                        }
                    } else if (nowPkg != currentPackage) {
                        // LOGIC: App changed
                        val nowWall = System.currentTimeMillis()
                        if (currentPackage.isNotBlank() && sessionStart > 0L) {
                            commitSession(currentPackage, sessionStart, nowWall)
                        } else {
                            clearSessionState()
                        }

                        currentPackage = ""
                        sessionStart = 0L
                        sessionCommitted = false

                        if (nowPkg.isNotBlank()) {
                            currentPackage = nowPkg
                            sessionStart = nowWall
                            saveSessionState(currentPackage, sessionStart, SystemClock.elapsedRealtime())
                        }
                    }

                    // UPDATE UI
                    withContext(Dispatchers.Main) {
                        val show = nowPkg in overlayEnabledPkgs
                        bubbleView.isVisible = show
                        if (show) {
                            val tv = bubbleView.findViewById<TextView>(R.id.bubbleText)
                            val secs = usageTotals[nowPkg] ?: 0L
                            tv.text = formatHMS(secs)
                        }
                    }

                    delay(1000)
                }
            } finally {
                // 🔁 Always flush the last session if cancelled (even without onDestroy)
                val now = System.currentTimeMillis()
                val durationSec = ((now - sessionStart) / 1000).toInt()
                if (durationSec > 0 && currentPackage.isNotBlank()) {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(System.currentTimeMillis())

                    dao.ensureAppRow(AppEntity(currentPackage, currentPackage))
                    dao.upsertDaily(currentPackage, today, durationSec)
                    withContext(Dispatchers.IO) {
                        dao.checkpointDb(androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
                    }
                }
            }
        }
    }

    private fun getForegroundPackageOptimized(
        usm: UsageStatsManager,
        lastKnown: String
    ): String {
        val end = System.currentTimeMillis()
        val begin = end - 60_000L // Look back 60s

        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()

        var latestTimeStamp = 0L
        var latestPackage = ""

        // 1. Search for the absolute latest MOVE_TO_FOREGROUND event
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp > latestTimeStamp) {
                    latestTimeStamp = event.timeStamp
                    latestPackage = event.packageName
                }
            }
        }

        // 2. DECISION LOGIC:
        return if (latestPackage.isNotBlank()) {
            // We found a new event, that's definitely the app.
            latestPackage
        } else {
            // We found NO events in the last 60 seconds.
            // This means the user is either:
            // A) Still looking at the 'lastKnown' app (e.g. watching video)
            // B) On the home screen (which sometimes doesn't send events in some launchers)

            // If screen is ON (checked in the loop), we assume they are still
            // in the last known package.
            lastKnown
        }
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
        _isRunning.value = false
        val now    = System.currentTimeMillis()
        val durationSec = ((now - sessionStart) / 1000).toInt()
        if (durationSec > 0 && currentPackage.isNotBlank()) {
            runBlocking {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(System.currentTimeMillis())

                dao.upsertDaily(currentPackage, today, durationSec)
            }
        }

        // IMPORTANT: clear persisted session state and mark overlay as inactive
        clearSessionState() // remove session_pkg, session_start_wall, session_start_mono
        try {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("overlay_active", false).apply()
        } catch (_: Exception) { /* ignore */ }

        super.onDestroy()
        unregisterReceiver(screenReceiver)
        windowManager.removeView(bubbleView)
        serviceScope.cancel()
    }

}
