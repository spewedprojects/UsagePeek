package com.gratus.usagepeek.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "sessions",
    foreignKeys = [ForeignKey(
        entity = AppEntity::class,
        parentColumns = ["packageName"],
        childColumns = ["packageName"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startTs: Long,
    val endTs: Long,
    val durationSec: Int
)