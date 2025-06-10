package com.gratus.usagepeek.data

import androidx.room.Entity

@Entity(tableName = "daily_totals", primaryKeys = ["packageName", "date"])
data class DailyTotalEntity(
    val packageName: String,
    val date: String,          // yyyy-MM-dd
    var seconds: Int,
    var openCount: Int,
    var minSession: Int,
    var maxSession: Int,
    var avgSession: Float
)