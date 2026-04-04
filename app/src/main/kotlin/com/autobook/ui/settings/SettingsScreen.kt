package com.autobook.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.autobook.BuildConfig
import com.autobook.domain.tts.EdgeTTSEngine
import com.autobook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onVoiceChanged: (() -> Unit)? = null
) {
    val voices by viewModel.voices.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val skipSeconds by viewModel.skipSeconds.collectAsState()
    val ttsEngine by viewModel.ttsEngine.collectAsState()
    val edgeVoices by viewModel.edgeVoices.collectAsState()
    val selectedEdgeVoice by viewModel.selectedEdgeVoice.collectAsState()
    var defaultSpeed by remember { mutableStateOf(1.0f) }

    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // TTS Engine Selection
            item {
                SectionHeader("TTS ENGINE")
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NavySurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilterChip(
                                selected = ttsEngine == "system",
                                onClick = { viewModel.setTTSEngine("system") },
                                label = { Text("📱 Device", fontSize = 14.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Color(0xFF261A00),
                                    containerColor = Color.Transparent,
                                    labelColor = TextMuted
                                )
                            )
                            FilterChip(
                                selected = ttsEngine == "edge",
                                onClick = { viewModel.setTTSEngine("edge") },
                                label = { Text("✨ AI Neural", fontSize = 14.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Color(0xFF261A00),
                                    containerColor = Color.Transparent,
                                    labelColor = TextMuted
                                )
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (ttsEngine == "edge") "Microsoft neural voices • Free • Requires internet"
                            else "Built-in Android TTS • Works offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Voice Selection Section
            item {
                SectionHeader("VOICE")
            }

            if (ttsEngine == "edge") {
                // Edge TTS voices
                items(edgeVoices.size) { index ->
                    val voice = edgeVoices[index]
                    val isSelected = voice.id == selectedEdgeVoice
                    Card(
                        modifier = Modifier.clickable {
                            viewModel.selectEdgeVoice(voice.id)
                            onVoiceChanged?.invoke()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Amber.copy(alpha = 0.15f) else NavySurface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) Amber else TextMuted,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(32.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    voice.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) TextPrimary else TextSecondary
                                )
                                Text(
                                    voice.gender + " • Neural",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            IconButton(
                                onClick = { viewModel.testEdgeVoice(voice.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.PlayCircle,
                                    contentDescription = "Preview voice",
                                    tint = if (isSelected) Amber else TextMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Amber
                                )
                            }
                        }
                    }
                }
            } else {
                // System TTS voices
                if (voices.isNotEmpty()) {
                    val voiceList = voices
                    items(voiceList.size) { index ->
                        val voice = voiceList[index]
                        val isSelected = voice.name == selectedVoice
                        Card(
                            modifier = Modifier.clickable {
                                viewModel.selectVoice(voice.name)
                                onVoiceChanged?.invoke()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Amber.copy(alpha = 0.15f) else NavySurface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isSelected) Amber else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        voice.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) TextPrimary else TextSecondary
                                    )
                                    Text(
                                        buildString {
                                            append(voiceQualityLabel(voice.quality))
                                            if (voice.isNetwork) append(" • ☁ Network")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.testVoice(voice.name) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.PlayCircle,
                                        contentDescription = "Preview voice",
                                        tint = if (isSelected) Amber else TextMuted,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Amber
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Loading voices…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }

            // Playback Section
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("PLAYBACK")
            }



            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NavySurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Skip Duration: ${skipSeconds}s",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf(5, 10, 15, 30, 60).forEach { secs ->
                                val isSelected = skipSeconds == secs
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.setSkipSeconds(secs) },
                                    label = { Text("${secs}s", fontSize = 13.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Amber,
                                        selectedLabelColor = Color(0xFF261A00),
                                        containerColor = Color.Transparent,
                                        labelColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // About section
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("ABOUT")
            }

            item {
                val context = LocalContext.current
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NavySurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow(icon = Icons.Default.Info, title = "Version", subtitle = BuildConfig.VERSION_NAME)
                        Divider(color = NavyMuted.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://f-droid.org/packages/${BuildConfig.APPLICATION_ID}/")
                                    )
                                    context.startActivity(intent)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Update, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Updates", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                                Text("Via F-Droid", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                            }
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                        Divider(color = NavyMuted.copy(alpha = 0.3f))
                        SettingsRow(icon = Icons.Default.Code, title = "Open Source", subtitle = "Apache License 2.0")
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextMuted,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

private fun voiceQualityLabel(quality: Int): String = when {
    quality >= 500 -> "Very High"
    quality >= 400 -> "High"
    quality >= 300 -> "Normal"
    quality >= 200 -> "Low"
    else -> "Very Low"
}
