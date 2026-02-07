package com.example.aozonjobtrackerpasynkov.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Repository для работы со статистикой.
 * Предоставляет удобный API для записи и чтения данных.
 */
class StatsRepository private constructor(context: Context) {
    
    private val dao = StatsDatabase.getInstance(context).statsDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Записать результат проверки
     */
    fun recordCheck(
        isSuccess: Boolean,
        slotsFound: Boolean,
        slotDays: String?,
        durationMs: Long,
        errorMessage: String? = null
    ) {
        scope.launch {
            dao.insert(
                CheckRecord(
                    timestamp = System.currentTimeMillis(),
                    isSuccess = isSuccess,
                    slotsFound = slotsFound,
                    slotDays = slotDays,
                    durationMs = durationMs,
                    errorMessage = errorMessage
                )
            )
        }
    }
    
    /**
     * Получить последние N проверок
     */
    fun getRecentChecks(limit: Int = 50): Flow<List<CheckRecord>> {
        return dao.getRecentChecks(limit)
    }
    
    /**
     * Получить сводную статистику
     */
    fun getSummaryStats(): Flow<SummaryStats> {
        return combine(
            dao.getTotalChecksCount(),
            dao.getSuccessfulChecksCount(),
            dao.getSlotsFoundCount(),
            dao.getAverageDurationMs()
        ) { total, successful, slotsFound, avgDuration ->
            SummaryStats(
                totalChecks = total,
                successfulChecks = successful,
                slotsFoundCount = slotsFound,
                successRate = if (total > 0) (successful.toFloat() / total * 100) else 0f,
                averageDurationMs = avgDuration?.toLong() ?: 0L
            )
        }
    }
    
    /**
     * Получить данные для heat map
     */
    fun getHeatMapData(): Flow<List<HeatMapCell>> {
        return dao.getHeatMapData()
    }
    
    /**
     * Распределение слотов по часам
     */
    fun getSlotsFoundByHour(): Flow<List<HourCount>> {
        return dao.getSlotsFoundByHour()
    }
    
    /**
     * Распределение слотов по дням недели
     */
    fun getSlotsFoundByDayOfWeek(): Flow<List<DayOfWeekCount>> {
        return dao.getSlotsFoundByDayOfWeek()
    }
    
    /**
     * Очистить всю статистику
     */
    fun clearAll() {
        scope.launch {
            dao.clearAll()
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: StatsRepository? = null
        
        fun getInstance(context: Context): StatsRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = StatsRepository(context)
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Сводная статистика
 */
data class SummaryStats(
    val totalChecks: Int,
    val successfulChecks: Int,
    val slotsFoundCount: Int,
    val successRate: Float,          // 0-100%
    val averageDurationMs: Long
)
