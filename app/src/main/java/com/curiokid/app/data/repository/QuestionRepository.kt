package com.curiokid.app.data.repository

import com.curiokid.app.data.local.QuestionDao
import com.curiokid.app.data.local.QuestionEntity
import kotlinx.coroutines.flow.Flow

class QuestionRepository(private val dao: QuestionDao) {

    fun observeAll(): Flow<List<QuestionEntity>> = dao.observeAll()

    suspend fun forSince(sinceMillis: Long): List<QuestionEntity> =
        dao.forRange(sinceMillis)

    suspend fun save(entity: QuestionEntity): Long = dao.insert(entity)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()
}
