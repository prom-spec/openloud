package com.autobook.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.autobook.service.PlaybackState
import com.autobook.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    bookId: String,
    onBack: () -> Unit
) {
    val book by viewModel.book.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val skipSeconds by viewModel.skipSeconds.collectAsState()

    var showChapters by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val currentBook = book

    // Calculate time estimates
    val totalWords = remember(chapters) {
        chapters.sumOf { it.textContent.split(Regex("\\s+")).size }
    }
    val wordsPerMinute = 150f // average TTS speaking rate at 1x
    val effectiveWPM = wordsPerMinute * playbackSpeed
    val totalMinutes = if (effectiveWPM > 0) totalWords / effectiveWPM else 0f

    // Words read so far (chapters before current + position in current chapter)
    val wordsRead = remember(chapters, currentChapter, currentPosition) {
        val currentBook = book ?: return@remember 0
        val chapterIndex = currentBook.currentChapterIndex
        var words = 0
        // Words in completed chapters
        for (i in 0 until chapterIndex.coerceAtMost(chapters.size)) {
            words += chapters[i].textContent.split(Regex("\\s+")).size
        }
        // Words in current chapter up to current sentence
        currentChapter?.let { ch ->
            val sentences = ch.textContent.split(Regex("[.!?]+\\s+"))
            for (i in 0 until currentPosition.coerceAtMost(sentences.size)) {
                words += sentences[i].split(Regex("\\s+")).size
            }
        }
        words
    }
    val elapsedMinutes = if (effectiveWPM > 0) wordsRead / effectiveWPM else 0f
    val remainingMinutes = (totalMinutes - elapsedMinutes).coerceAtLeast(0f)

    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Library", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showChapters = !showChapters }) {
                        Icon(Icons.Default.List, contentDescription = "Chapters", tint = TextSecondary)
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        if (showChapters) {
            // Chapter list
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp)
            ) {
                Text(
                    "Chapters",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(chapters) { index, chapter ->
                        val isCurrent = chapter.id == currentChapter?.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapters = false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) Amber.copy(alpha = 0.15f) else Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isCurrent) Amber else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        chapter.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isCurrent) TextPrimary else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Show chapter duration estimate
                                    val chapterWords = chapter.textContent.split(Regex("\\s+")).size
                                    val chapterMins = if (effectiveWPM > 0) chapterWords / effectiveWPM else 0f
                                    Text(
                                        formatDuration(chapterMins),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Amber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Player view
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Cover art — large
                Box(
                    modifier = Modifier
                        .padding(horizontal = 32.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Amber.copy(alpha = 0.3f),
                                    SecondaryGold.copy(alpha = 0.15f),
                                    Navy
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val coverPath = currentBook?.coverPath
                    val coverFile = coverPath?.let { File(it) }
                    if (coverFile != null && coverFile.exists()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = currentBook?.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = Amber.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Title and author
                currentBook?.let { b ->
                    Text(
                        text = b.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    b.author?.let { author ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Chapter info
                currentChapter?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Progress bar with time
                val progress = if (totalWords > 0) {
                    wordsRead.toFloat() / totalWords.coerceAtLeast(1)
                } else 0f

                // Track slider drag separately so we don't hammer seekToProgress
                var isDragging by remember { mutableStateOf(false) }
                var dragProgress by remember { mutableFloatStateOf(0f) }
                val displayProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

                Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                    Slider(
                        value = displayProgress,
                        onValueChange = { newProgress ->
                            isDragging = true
                            dragProgress = newProgress
                        },
                        onValueChangeFinished = {
                            if (totalWords > 0) {
                                viewModel.seekToProgress(dragProgress)
                            }
                            isDragging = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Amber,
                            activeTrackColor = Amber,
                            inactiveTrackColor = NavyMuted
                        )
                    )

                    Spacer(Modifier.height(0.dp))

                    // Time labels
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatDuration(elapsedMinutes),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            formatDuration(totalMinutes),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    // Remaining time centered
                    Text(
                        "-${formatDuration(remainingMinutes)} remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Playback controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous chapter
                    IconButton(onClick = { viewModel.previousChapter() }) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = TextSecondary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Rewind
                    IconButton(onClick = { viewModel.skipBackward() }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Replay,
                                contentDescription = "Rewind ${skipSeconds}s",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "$skipSeconds",
                                fontSize = 9.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Play/Pause button — large amber circle
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Amber)
                            .clickable { viewModel.playPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (playbackState == PlaybackState.PLAYING)
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState == PlaybackState.PLAYING) "Pause" else "Play",
                            tint = Color(0xFF261A00),
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Forward
                    IconButton(onClick = { viewModel.skipForward() }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Forward,
                                contentDescription = "Forward ${skipSeconds}s",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                "$skipSeconds",
                                fontSize = 9.sp,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Next chapter
                    IconButton(onClick = { viewModel.nextChapter() }) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = TextSecondary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Speed selector — wired to viewModel
                Row(
                    modifier = Modifier
                        .padding(horizontal = 48.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(NavySurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
                    speeds.forEach { speed ->
                        val isSelected = playbackSpeed == speed
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) Amber else Color.Transparent)
                                .clickable {
                                    viewModel.setSpeed(speed)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${speed}x",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) Color(0xFF261A00) else TextMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Format minutes into h:mm or m:ss */
private fun formatDuration(minutes: Float): String {
    val totalSeconds = (minutes * 60).toInt()
    val hours = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format("%d:%02d", mins, secs)
    }
}
