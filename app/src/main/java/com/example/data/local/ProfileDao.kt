package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM v2ray_profiles ORDER BY isFavorite DESC, isPhoneBridge DESC, createdAt DESC")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM v2ray_profiles")
    suspend fun getAllOnce(): List<ProfileEntity>

    @Query("SELECT * FROM v2ray_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfiles(profiles: List<ProfileEntity>)

    @Query("UPDATE v2ray_profiles SET latencyMs = :latencyMs, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Long?, testedAt: Long)

    @Query("UPDATE v2ray_profiles SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE v2ray_profiles SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("DELETE FROM v2ray_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
