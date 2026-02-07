package com.example.aozonjobtrackerpasynkov

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.aozonjobtrackerpasynkov.ui.theme.AOzonJobTrackerPasynkovTheme
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.collectLatest
import com.example.aozonjobtrackerpasynkov.data.StatsRepository
import com.example.aozonjobtrackerpasynkov.ui.StatsScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AOzonJobTrackerPasynkovTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                val context = LocalContext.current
                val statsRepository = remember { StatsRepository.getInstance(context) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Monitor") },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("Stats") },
                                icon = { Icon(Icons.Default.List, contentDescription = null) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            0 -> MainScreen()
                            1 -> StatsScreen(statsRepository)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isMonitoring by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    
    // Check accessibility status every 2 seconds
    LaunchedEffect(Unit) {
        while(true) {
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            isAccessibilityEnabled = enabledServices.contains(context.packageName)
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        OzonJobAccessibilityService.serviceState.collectLatest { msg ->
            logs.add(0, "[Access] $msg")
            if (logs.size > 100) logs.removeLast()
        }
    }

    val sharedPrefs = context.getSharedPreferences("OzonPrefs", Context.MODE_PRIVATE)
    var actionDelay by remember { mutableFloatStateOf(sharedPrefs.getFloat("action_delay", 3.0f)) }
    var refreshDelay by remember { mutableFloatStateOf(sharedPrefs.getFloat("refresh_delay", 1.5f)) }
    var fastRefresh by remember { mutableStateOf(sharedPrefs.getBoolean("fast_refresh", false)) }

    Column(modifier = modifier.padding(16.dp)) {
        Text("Ozon Job Watcher", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isAccessibilityEnabled) "Accessibility: ACTIVE" else "Accessibility: DISABLED",
            color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = fastRefresh,
                onCheckedChange = { 
                    fastRefresh = it
                    sharedPrefs.edit().putBoolean("fast_refresh", it).apply()
                }
            )
            Text("Fast Refresh Mode (Back & Forth)")
        }

        if (fastRefresh) {
            Text("Fast Refresh Delay: ${"%.1f".format(refreshDelay)}s")
            Slider(
                value = refreshDelay,
                onValueChange = { 
                    refreshDelay = it
                    sharedPrefs.edit().putFloat("refresh_delay", it).apply()
                },
                valueRange = 0.5f..5f,
                steps = 8 // 0.5s increments
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        var tgBotToken by remember { mutableStateOf(sharedPrefs.getString("tg_bot_token", "") ?: "") }
        var tgChatId by remember { mutableStateOf(sharedPrefs.getString("tg_chat_id", "") ?: "") }

        Text("Telegram Settings", style = MaterialTheme.typography.titleMedium)
        TextField(
            value = tgBotToken,
            onValueChange = { 
                tgBotToken = it
                sharedPrefs.edit().putString("tg_bot_token", it).apply()
            },
            label = { Text("Bot Token") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextField(
            value = tgChatId,
            onValueChange = { 
                tgChatId = it
                sharedPrefs.edit().putString("tg_chat_id", it).apply()
            },
            label = { Text("Chat ID") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(onClick = {
            if (tgBotToken.isBlank() || tgChatId.isBlank()) {
                logs.add(0, "[TG] Error: Token or ID is blank")
            } else {
                com.example.aozonjobtrackerpasynkov.network.TelegramClient(tgBotToken, tgChatId)
                    .sendMessage("Test message from Ozon Job Watcher") { success, error ->
                        if (success) {
                            logs.add(0, "[TG] Test message sent!")
                        } else {
                            logs.add(0, "[TG] Error: $error")
                        }
                    }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Test Telegram Notification")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                try {
                    val intent = Intent(context, JobMonitoringService::class.java).apply {
                        action = JobMonitoringService.ACTION_START
                    }
                    context.startForegroundService(intent)
                    isMonitoring = true
                    logs.add(0, "Started Monitoring")
                } catch (e: Exception) {
                    logs.add(0, "Error starting service: ${e.message}")
                    e.printStackTrace()
                }
            }) {
                Text("Start Monitoring")
            }
            
            Button(onClick = {
                val intent = Intent(context, JobMonitoringService::class.java).apply {
                    action = JobMonitoringService.ACTION_STOP
                }
                context.startService(intent)
                isMonitoring = false
                logs.add(0, "Stopped Monitoring")
            }) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("Open Accessibility Settings")
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
             val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
             context.startActivity(intent)
        }) {
            Text("Open Battery Settings")
        }
        
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            context.startActivity(intent)
        }) {
            Text("Open Overlay Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            items(logs) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}