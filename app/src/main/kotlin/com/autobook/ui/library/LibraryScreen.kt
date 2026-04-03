package com.autobook.ui.library

import android.content.Context
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.autobook.data.db.BookEntity
import com.autobook.ui.theme.*
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (String) -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {}
) {
    val books by viewModel.books.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<BookEntity?>(null) }
    var showEditDialog by remember { mutableStateOf<BookEntity?>(null) }
    var showLongClickMenu by remember { mutableStateOf<BookEntity?>(null) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var largeIcons by remember { mutableStateOf(prefs.getBoolean("library_large_icons", true)) }

    Scaffold(
        containerColor = Navy,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onImportClick,
                containerColor = Amber,
                contentColor = Color(0xFF261A00),
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import Book", modifier = Modifier.size(28.dp))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AIAnyBook",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        largeIcons = !largeIcons
                        prefs.edit().putBoolean("library_large_icons", largeIcons).apply()
                    }) {
                        Icon(
                            if (largeIcons) Icons.Default.GridView else Icons.Default.ViewModule,
                            contentDescription = "Toggle view",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Amber)
                }
            } else if (books.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = TextMuted
                        )
                        Text(
                            "Your library is empty",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextSecondary
                        )
                        Text(
                            "Import a book to get started",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                        Button(
                            onClick = onImportClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Color(0xFF261A00)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Import Book", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Resume Playback hero card (only if a book was actually played)
                val lastBook = books.firstOrNull { it.lastReadAt != null }
                if (lastBook != null) {
                    ResumeCard(
                        book = lastBook,
                        onClick = { onBookClick(lastBook.id) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                // Section header
                Text(
                    text = "MY LIBRARY",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
                )

                // Book grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (largeIcons) 2 else 3),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (largeIcons) 12.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (largeIcons) 12.dp else 8.dp)
                ) {
                    items(books) { book ->
                        if (largeIcons) {
                            BookCard(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onLongClick = { showLongClickMenu = book }
                            )
                        } else {
                            BookCardSmall(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onLongClick = { showLongClickMenu = book }
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-click menu
    showLongClickMenu?.let { book ->
        AlertDialog(
            onDismissRequest = { showLongClickMenu = null },
            containerColor = NavySurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showLongClickMenu = null
                            showEditDialog = book
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Edit Book", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            showLongClickMenu = null
                            viewModel.reSearchCover(book)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Re-search Cover", modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = {
                            showLongClickMenu = null
                            showDeleteDialog = book
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Delete", modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showLongClickMenu = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Edit book dialog
    showEditDialog?.let { book ->
        var editedTitle by remember { mutableStateOf(book.title) }
        var editedAuthor by remember { mutableStateOf(book.author ?: "") }
        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            containerColor = NavySurface,
            titleContentColor = TextPrimary,
            title = { Text("Edit Book") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editedTitle,
                        onValueChange = { editedTitle = it },
                        label = { Text("Title", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Amber,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editedAuthor,
                        onValueChange = { editedAuthor = it },
                        label = { Text("Author", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Amber,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editedTitle.isNotBlank()) {
                            viewModel.editBook(
                                book,
                                editedTitle.trim(),
                                editedAuthor.trim().ifBlank { null }
                            )
                            showEditDialog = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Amber)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditDialog = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { book ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = NavySurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete \"${book.title}\"?") },
            text = { Text("This will permanently remove the book and all chapters.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeCard(
    book: BookEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cover
            val coverFile = book.coverPath?.let { File(it) }
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Amber.copy(alpha = 0.6f), SecondaryGold.copy(alpha = 0.4f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverFile != null && coverFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color(0xFF261A00)
                    )
                }
            }

            // Info
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Resume Playback",
                        style = MaterialTheme.typography.labelMedium,
                        color = Amber,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    book.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 1
                        )
                    }
                }

                // Progress
                val progress = if (book.totalChapters > 0) {
                    book.currentChapterIndex.toFloat() / book.totalChapters
                } else 0f

                Column {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Amber,
                        trackColor = NavyMuted
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ch. ${book.currentChapterIndex + 1} of ${book.totalChapters}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface)
    ) {
        Column {
            // Cover art
            val coverFile = book.coverPath?.let { File(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Amber.copy(alpha = 0.3f),
                                SecondaryGold.copy(alpha = 0.2f),
                                Navy.copy(alpha = 0.8f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverFile != null && coverFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Amber.copy(alpha = 0.7f)
                    )
                }
            }

            // Info
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                book.author?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Progress bar
                val progress = if (book.totalChapters > 0) {
                    book.currentChapterIndex.toFloat() / book.totalChapters
                } else 0f

                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Amber,
                    trackColor = NavyMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCardSmall(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface)
    ) {
        Column {
            // Cover art — smaller
            val coverFile = book.coverPath?.let { File(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Amber.copy(alpha = 0.3f),
                                SecondaryGold.copy(alpha = 0.2f),
                                Navy.copy(alpha = 0.8f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverFile != null && coverFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Amber.copy(alpha = 0.7f)
                    )
                }
            }

            // Info — compact
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                val progress = if (book.totalChapters > 0) {
                    book.currentChapterIndex.toFloat() / book.totalChapters
                } else 0f

                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = Amber,
                    trackColor = NavyMuted
                )
            }
        }
    }
}
