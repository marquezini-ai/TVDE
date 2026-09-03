package com.daniel.tvdeinsight.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trip_history ORDER BY recordedAtMillis DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip_history WHERE sourceDeviceId = :sourceDeviceId ORDER BY recordedAtMillis DESC")
    fun observeBySourceDeviceId(sourceDeviceId: String): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip_history ORDER BY recordedAtMillis DESC")
    suspend fun getAll(): List<TripEntity>

    @Query("SELECT * FROM trip_history WHERE sourceDeviceId = :sourceDeviceId ORDER BY recordedAtMillis DESC")
    suspend fun getBySourceDeviceId(sourceDeviceId: String): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: TripEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TripEntity>)

    @Query("UPDATE trip_history SET screenshotFileName = :fileName WHERE sourceDeviceId = :sourceDeviceId AND id = :id")
    suspend fun updateScreenshotFileName(sourceDeviceId: String, id: Long, fileName: String): Int

    @Query(
        """
        SELECT CAST(strftime('%H', recordedAtMillis / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS tripCount,
               AVG(tripValue) AS averageTripValue,
               AVG(valorPorKm) AS averagePerKm,
               AVG(valorPorHora) AS averagePerHour
        FROM trip_history
        GROUP BY hour
        ORDER BY hour
        """
    )
    suspend fun bestHours(): List<HourlyTripSummary>

    @Query(
        """
        SELECT pickupAddress AS address,
               COUNT(*) AS tripCount,
               AVG(tripValue) AS averageTripValue,
               AVG(valorPorKm) AS averagePerKm,
               AVG(valorPorHora) AS averagePerHour
        FROM trip_history
        WHERE pickupAddress IS NOT NULL AND TRIM(pickupAddress) <> ''
        GROUP BY pickupAddress
        ORDER BY averageTripValue DESC
        LIMIT :limit
        """
    )
    suspend fun bestPickupAddresses(limit: Int = 20): List<PickupAddressSummary>
}
