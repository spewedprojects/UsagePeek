package com.gratus.usagepeek

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object PermissionUtils {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED ||
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
