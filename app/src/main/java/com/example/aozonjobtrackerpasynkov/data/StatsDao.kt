package com.example.aozonjobtrackerpasynkov.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы со статистикой проверок.
 */
@Dao
interface StatsDao {
    
    @Insert
    suspend fun insert(record: CheckRecord)
    
    /**
     * Последние N проверок (для списка истории)
     */
    @Query("SELECT * FROM check_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentChecks(limit: Int = 50): Flow<List<CheckRecord>>
    
    /**
     * Общее количество проверок
     */
    @Query("SELECT COUNT(*) FROM check_records")
    fun getTotalChecksCount(): Flow<Int>
    
    /**
     * Количество успешных проверок (без ошибок recovery)
     */
    @Query("SELECT COUNT(*) FROM check_records WHERE isSuccess = 1")
    fun getSuccessfulChecksCount(): Flow<Int>
    
    /**
     * Количество проверок с найденными слотами
     */
    @Query("SELECT COUNT(*) FROM check_records WHERE slotsFound = 1")
    fun getSlotsFoundCount(): Flow<Int>
    
    /**
     * Средняя длительность цикла (в мс)
     */
    @Query("SELECT AVG(durationMs) FROM check_records WHERE isSuccess = 1")
    fun getAverageDurationMs(): Flow<Double?>
    
    /**
     * Распределение слотов по часам (0-23)
     * Возвращает пары (hour, count)
     */
    @Query("""
        SELECT 
            CAST(strftime('%H', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
            COUNT(*) as count
        FROM check_records 
        WHERE slotsFound = 1
        GROUP BY hour
    """)
    fun getSlotsFoundByHour(): Flow<List<HourCount>>
    
    /**
     * Распределение слотов по дням недели (0=Воскресенье, 1=Понедельник, ..., 6=Суббота)
     */
    @Query("""
        SELECT 
            CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as dayOfWeek,
            COUNT(*) as count
        FROM check_records 
        WHERE slotsFound = 1
        GROUP BY dayOfWeek
    """)
    fun getSlotsFoundByDayOfWeek(): Flow<List<DayOfWeekCount>>
    
    /**
     * Heat map: распределение слотов по часам и дням недели
     */
    @Query("""
        SELECT 
            CAST(strftime('%w', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as dayOfWeek,
            CAST(strftime('%H', datetime(timestamp/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
            COUNT(*) as count
        FROM check_records 
        WHERE slotsFound = 1
        GROUP BY dayOfWeek, hour
    """)
    fun getHeatMapData(): Flow<List<HeatMapCell>>
    
    /**
     * Очистить всю статистику
     */
    @Query("DELETE FROM check_records")
    suspend fun clearAll()
}

/**
 * Вспомогательный класс для группировки по часам
 */
data class HourCount(
    val hour: Int,
    val count: Int
)

/**
 * Вспомогательный класс для группировки по дням недели
 */
data class DayOfWeekCount(
    val dayOfWeek: Int,
    val count: Int
)

/**
 * Ячейка heat map (час × день недели)
 */
data class HeatMapCell(
    val dayOfWeek: Int,
    val hour: Int,
    val count: Int
)
