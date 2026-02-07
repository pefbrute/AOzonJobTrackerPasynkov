package com.example.aozonjobtrackerpasynkov.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aozonjobtrackerpasynkov.data.CheckRecord
import com.example.aozonjobtrackerpasynkov.data.HeatMapCell
import com.example.aozonjobtrackerpasynkov.data.StatsRepository
import com.example.aozonjobtrackerpasynkov.data.SummaryStats
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StatsScreen(repository: StatsRepository) {
    val summaryStats by repository.getSummaryStats().collectAsState(initial = null)
    val recentChecks by repository.getRecentChecks(50).collectAsState(initial = emptyList())
    val heatMapData by repository.getHeatMapData().collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Statistics", style = MaterialTheme.typography.headlineMedium)
        }

        // Summary Cards
        item {
            SummarySection(summaryStats)
        }

        // Heat Map Section
        item {
            Text("Slot Appearance Heat Map", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            SlotHeatMap(heatMapData)
        }

        // History Section
        item {
            Text("Recent History", style = MaterialTheme.typography.titleMedium)
        }

        items(recentChecks) { record ->
            CheckRecordItem(record)
            HorizontalDivider()
        }
    }
}

@Composable
fun SummarySection(stats: SummaryStats?) {
    if (stats == null) return
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("Checks", "${stats.totalChecks}", Modifier.weight(1f))
        StatCard("Success", "${"%.1f".format(stats.successRate)}%", Modifier.weight(1f))
        StatCard("Slots", "${stats.slotsFoundCount}", Modifier.weight(1f))
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SlotHeatMap(data: List<HeatMapCell>) {
    val days = listOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
    val maxCount = data.maxByOrNull { it.count }?.count ?: 1
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Hours header
            Row(modifier = Modifier.padding(start = 24.dp)) {
                listOf(0, 6, 12, 18, 23).forEach { hour ->
                    Text(
                        text = "$hour",
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                        color = Color.Gray
                    )
                }
            }
            
            // Grid
            days.forEachIndexed { dayIdx, dayName ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dayName, fontSize = 10.sp, modifier = Modifier.width(24.dp))
                    Row(modifier = Modifier.weight(1f)) {
                        for (hour in 0..23) {
                            val cell = data.find { it.dayOfWeek == dayIdx && it.hour == hour }
                            val alpha = if (cell != null) (cell.count.toFloat() / maxCount).coerceIn(0.2f, 1.0f) else 0f
                            val color = if (cell != null) MaterialTheme.colorScheme.primary.copy(alpha = alpha) else Color.LightGray.copy(alpha = 0.2f)
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .padding(1.dp)
                                    .background(color)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CheckRecordItem(record: CheckRecord) {
    val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    val timeStr = timeFormat.format(Date(record.timestamp))
    
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(timeStr, style = MaterialTheme.typography.bodySmall)
            Text(
                text = if (record.isSuccess) "Success" else "Error",
                color = if (record.isSuccess) Color(0xFF4CAF50) else Color.Red,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        if (record.slotsFound) {
            Text("SLOTS FOUND!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            Text(record.slotDays ?: "", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("No slots found", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        }
        
        if (record.errorMessage != null) {
            Text("Error: ${record.errorMessage}", color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }
        
        Text("Duration: ${record.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}
