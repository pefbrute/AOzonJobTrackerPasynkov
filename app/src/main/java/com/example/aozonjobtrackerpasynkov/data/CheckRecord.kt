package com.example.aozonjobtrackerpasynkov.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Запись о проверке слотов.
 * Каждый цикл работы бота создаёт одну запись.
 */
@Entity(tableName = "check_records")
data class CheckRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,           // System.currentTimeMillis()
    val isSuccess: Boolean,        // Цикл завершился без ошибок (не в recovery)
    val slotsFound: Boolean,       // Были ли найдены слоты
    val slotDays: String?,         // Найденные даты (например "7 февраля, Сб; 8 февраля, Вс")
    val durationMs: Long,          // Время выполнения цикла в мс
    val errorMessage: String?      // Ошибка (если была), null если всё ок
)
