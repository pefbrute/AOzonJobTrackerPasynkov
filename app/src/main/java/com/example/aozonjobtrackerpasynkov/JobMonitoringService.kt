package com.example.aozonjobtrackerpasynkov

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class JobMonitoringService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val handler = Handler(Looper.getMainLooper())
    
    private var isMonitoring = false
    private val checkIntervalMs = 30 * 1000L // Reduced from 10 minutes to 30 seconds for 'infinite' checking

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                performCheck()
                handler.postDelayed(this, checkIntervalMs)
            }
        }
    }

    private var lastResult: String = "No checks yet"
    private var currentStatus: String = "Idle"

    private fun updateCombinedNotification() {
        val text = "Result: $lastResult | Bot: $currentStatus"
        updateNotification(text)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
            Log.d(TAG, "Service started foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}")
        }
        
        // Listen for service state (detailed logs)
        scope.launch {
            OzonJobAccessibilityService.serviceState.collectLatest { msg ->
                currentStatus = msg
                updateCombinedNotification()
            }
        }
        
        // Listen for slot check results
        val notificationManager = NotificationManager(this)
        scope.launch {
            OzonJobAccessibilityService.slotStatus.collectLatest { days ->
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                if (days != null) {
                    lastResult = "SLOTS FOUND! ($time)"
                    sendSlotFoundNotification(days)
                } else {
                    lastResult = "No slots ($time)"
                }
                notificationManager.notifyCheckResult(days)
                updateCombinedNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isMonitoring = true
                updateNotification("Monitoring active")
                handler.removeCallbacks(checkRunnable)
                handler.post(checkRunnable)
            }
            ACTION_STOP -> {
                isMonitoring = false
                updateNotification("Monitoring paused")
                handler.removeCallbacks(checkRunnable)
                OzonJobAccessibilityService.stopCheckCycle()
            }
        }
        return START_STICKY
    }

    private fun performCheck() {
        Log.d(TAG, "Performing periodic check")
        
        // 1. Wake up screen
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OzonJobWatcher:CheckWakeLock"
        )
        wakeLock.acquire(30 * 1000L) // 30 seconds

        // 2. Launch Ozon Job App
        val launchIntent = packageManager.getLaunchIntentForPackage("ru.ozon.hire")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
            
            // 3. Signal Accessibility Service
            // Give app some time to open
            handler.postDelayed({
                OzonJobAccessibilityService.startCheckCycle()
            }, 2000)
        } else {
            Log.e(TAG, "Ozon Job app (ru.ozon.job) not found")
            // Debug: List all ozon packages
            val packages = packageManager.getInstalledPackages(0)
            packages.forEach { 
                if (it.packageName.contains("ozon", ignoreCase = true)) {
                    Log.d(TAG, "Found potential Ozon monitoring candidate: ${it.packageName}")
                }
            }
        }
        
        wakeLock.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Job Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Slot Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(contentText: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Ozon Job Watcher")
        .setContentText(contentText)
        .setSmallIcon(R.mipmap.ic_launcher)
        .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
    
    private fun sendSlotFoundNotification(days: String) {
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("SLOTS FOUND!")
            .setContentText("Dates: $days")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Available dates: $days"))
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        handler.removeCallbacks(checkRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "JobMonitoringService"
        const val CHANNEL_ID = "monitoring_channel"
        const val ALERT_CHANNEL_ID = "alert_channel"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 999
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }
}
