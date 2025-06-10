package com.gratus.usagepeek.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val firstSeen: Long = System.currentTimeMillis()
)
