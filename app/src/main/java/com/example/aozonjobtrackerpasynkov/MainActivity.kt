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
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AOzonJobTrackerPasynkovTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
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
    
    LaunchedEffect(Unit) {
        OzonJobAccessibilityService.serviceState.collectLatest { msg ->
            logs.add(0, "[Access] $msg")
            if (logs.size > 100) logs.removeLast()
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text("Ozon Job Watcher", style = MaterialTheme.typography.headlineMedium)
        
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