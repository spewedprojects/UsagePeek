package com.gratus.usagepeek.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.sqlite.db.SimpleSQLiteQuery

@Dao
interface UsageDao {

    /* ---------- inserts ---------- */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureAppRow(app: AppEntity)

    @Insert
    suspend fun insertSession(s: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDaily(d: DailyTotalEntity)

    /* ---------- atomic daily upsert ---------- */

    @Query("""
        UPDATE daily_totals SET
            seconds    = seconds + :delta,
            openCount  = openCount + 1,
            minSession = MIN(minSession, :delta),
            maxSession = MAX(maxSession, :delta),
            avgSession = (seconds + :delta) * 1.0 / (openCount + 1)
        WHERE packageName = :pkg AND date = :date
    """)
    fun bumpDaily(pkg: String, date: String, delta: Int): Int    // returns rows updated

    @Transaction
    suspend fun upsertDaily(pkg: String, date: String, delta: Int) {
        val changed = bumpDaily(pkg, date, delta)
        if (changed == 0) {
            insertDaily(
                DailyTotalEntity(
                    pkg, date,
                    seconds = delta,
                    openCount = 1,
                    minSession = delta,
                    maxSession = delta,
                    avgSession = delta.toFloat()
                )
            )
        }
    }

    /* ---------- queries for UI ---------- */

    @Query("SELECT * FROM daily_totals WHERE packageName = :pkg ORDER BY date DESC LIMIT 30")
    fun last30Days(pkg: String): Flow<List<DailyTotalEntity>>

    @androidx.room.RawQuery
    fun checkpointDb(query: androidx.sqlite.db.SimpleSQLiteQuery): Int
}