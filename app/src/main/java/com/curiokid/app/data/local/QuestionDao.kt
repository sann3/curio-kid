package com.curiokid.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuestionEntity): Long

    @Query("SELECT * FROM questions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE timestamp >= :sinceMillis ORDER BY timestamp DESC")
    suspend fun forRange(sinceMillis: Long): List<QuestionEntity>

    @Query("DELETE FROM questions")
    suspend fun clear()

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun delete(id: Long)
}
