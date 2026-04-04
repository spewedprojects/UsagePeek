package com.gratus.usagepeek

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.UserHandle
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gratus.usagepeek.data.UsageDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

// Data class to hold our parsed app info clearly
data class AppListItem(
    val activityInfo: ActivityInfo,
    val userHandle: UserHandle,
    val uid: Int,
    val label: String,
    val isTwin: Boolean
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val pm = context.packageManager

    // 1. Service State
    val isServiceRunning = OverlayService.isRunning

    // 2. Enabled Packages State
    val enabledPkgs = AppPrefs.enabledPackages(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // 3. App List & Search State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _allApps = MutableStateFlow<List<AppListItem>>(emptyList())

    // This automatically updates whenever the search query or the app list changes
    val filteredApps = combine(_allApps, _searchQuery) { apps, query ->
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch(Dispatchers.Default) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val infos = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            }

            val appList = infos.map { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                val uid = activityInfo.applicationInfo.uid
                val userHandle = android.os.UserHandle.getUserHandleForUid(uid)
                val label = pm.getApplicationLabel(activityInfo.applicationInfo).toString()
                val isTwin = uid > 200000 // Heuristic for twin/work apps

                AppListItem(activityInfo, userHandle, uid, label, isTwin)
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }

            _allApps.value = appList
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleApp(pkg: String, isEnabled: Boolean) {
        viewModelScope.launch {
            AppPrefs.setEnabled(context, pkg, isEnabled)
        }
    }

    fun startService() {
        context.startForegroundService(Intent(context, OverlayService::class.java))
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("overlay_active", true).apply()
    }

    fun stopService() {
        context.stopService(Intent(context, OverlayService::class.java))
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("overlay_active", false).apply()
    }

    fun exportDatabase(activityContext: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            UsageDb.get(context).dao().checkpointDb(
                androidx.sqlite.db.SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")
            )

            val src = context.getDatabasePath("usagepeek.db")
            if (!src.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Database not found", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(System.currentTimeMillis())
            val fileName = "usagepeek_$stamp.db"

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/UsagePeekExports")
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Saved to Documents/UsagePeekExports", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    withContext(Dispatchers.Main) {
                        ActivityCompat.requestPermissions(
                            (activityContext as Activity),
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            101
                        )
                    }
                    return@launch
                }
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "UsagePeekExports").apply { mkdirs() }
                val dest = File(destDir, fileName)
                src.inputStream().use { it.copyTo(dest.outputStream()) }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to ${dest.relativeTo(Environment.getExternalStorageDirectory())}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}