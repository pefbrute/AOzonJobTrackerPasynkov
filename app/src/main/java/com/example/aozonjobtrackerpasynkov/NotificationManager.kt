package com.example.aozonjobtrackerpasynkov

import android.content.Context
import android.util.Log
import com.example.aozonjobtrackerpasynkov.network.TelegramClient
import java.security.MessageDigest

class NotificationManager(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("OzonPrefs", Context.MODE_PRIVATE)

    fun notifyCheckResult(slots: String?) {
        val botToken = sharedPrefs.getString("tg_bot_token", "") ?: ""
        val chatId = sharedPrefs.getString("tg_chat_id", "") ?: ""
        
        if (botToken.isBlank() || chatId.isBlank()) {
            Log.w("NotificationManager", "Telegram settings not configured")
            return
        }

        val client = TelegramClient(botToken, chatId)
        val message: String
        val signature: String

        if (slots != null) {
            message = "[OK] Слот появился! Склад: ${OzonJobAccessibilityService.WAREHOUSE_NAME} Работа: ${OzonJobAccessibilityService.JOB_NAME} Даты: $slots"
            signature = hashString("SLOTS:$slots")
        } else {
            message = "[INFO] Проверка завершена: слотов для '${OzonJobAccessibilityService.JOB_NAME}' пока нет."
            signature = hashString("NO_SLOTS")
        }

        val lastSignature = sharedPrefs.getString("last_tg_signature", "")
        val lastSentTime = sharedPrefs.getLong("last_tg_sent_time", 0L)
        val currentTime = System.currentTimeMillis()

        // Deduplication logic:
        // 1. If slots found and signature changed -> Send
        // 2. If NO slots and signature changed -> Send (once per "no slots" state change)
        // 3. Heartbeat: If same signature but > 4 hours passed -> Send anyway (optional but good for 'alive' signal)
        
        val fourHoursMs = 4 * 60 * 60 * 1000L
        val shouldSend = signature != lastSignature || (currentTime - lastSentTime > fourHoursMs)

        if (shouldSend) {
            client.sendMessage(message) { success, error ->
                if (success) {
                    Log.d("NotificationManager", "Telegram message sent successfully")
                    sharedPrefs.edit()
                        .putString("last_tg_signature", signature)
                        .putLong("last_tg_sent_time", currentTime)
                        .apply()
                } else {
                    Log.e("NotificationManager", "Failed to send Telegram message: $error")
                }
            }
        } else {
            Log.d("NotificationManager", "Telegram message skipped (deduplicated)")
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
