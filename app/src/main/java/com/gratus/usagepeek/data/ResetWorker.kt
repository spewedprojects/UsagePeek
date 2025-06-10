package com.gratus.usagepeek.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters


class ResetWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val dao = UsageDb.get(applicationContext).dao()
        // just clear today’s in-memory map; DB keeps history.
        // nothing to do here yet
        return Result.success()
    }
}