package com.autobook.ui.import_

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autobook.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onImportComplete: () -> Unit,
    onBack: () -> Unit
) {
    val importState by viewModel.importState.collectAsState()

    // Back button always goes back to library
    BackHandler { onBack() }

    // Auto-launch file picker on entry
    var pickerLaunched by remember { mutableStateOf(false) }
    // Track whether we got a result back from the picker
    var pickerResultReceived by remember { mutableStateOf(false) }

    // Use */* so all files (including .bin) are visible in the picker
    val mimeTypes = arrayOf("*/*")

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        pickerResultReceived = true
        if (uri != null) {
            viewModel.importBook(uri)
        } else {
            // User cancelled the file picker — go back to library
            onBack()
        }
    }
    LaunchedEffect(Unit) {
        if (!pickerLaunched) {
            pickerLaunched = true
            launcher.launch(mimeTypes)
        }
    }

    // If picker was launched but user cancelled (returned to ImportScreen idle), go back
    LaunchedEffect(pickerResultReceived, importState) {
        if (pickerLaunched && pickerResultReceived && importState is ImportState.Idle) {
            onBack()
        }
    }

    // Detect when activity resumes after picker closes without calling the callback
    // (some Android versions/devices don't call the result on back press)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pickerLaunched && !pickerResultReceived) {
                // Picker closed without giving us a result — user pressed back
                onBack()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(importState) {
        if (importState is ImportState.Success) {
            onImportComplete()
        }
    }

    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = {
                    Text("Import Book", color = TextPrimary, fontWeight = FontWeight.Bold)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when (val state = importState) {
                is ImportState.Idle -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        // Icon
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(NavySurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Amber
                            )
                        }

                        Text(
                            "Choose a file to import",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Supports EPUB, PDF, MOBI, DOCX, ODT, FB2, TXT",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = {
                                pickerResultReceived = false
                                launcher.launch(mimeTypes)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Color(0xFF261A00)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.height(52.dp).fillMaxWidth(0.7f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Browse Files", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                is ImportState.Processing -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(32.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = NavySurface)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Amber,
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(Modifier.height(24.dp))

                            @Suppress("DEPRECATION")
                            LinearProgressIndicator(
                                progress = state.progress / 100f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Amber,
                                trackColor = NavyMuted
                            )

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${state.progress}%",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Amber,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                is ImportState.Success -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E7D32).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFF4CAF50)
                            )
                        }
                        Text(
                            "Import Complete!",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                    }
                }

                is ImportState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6B6B).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFFFF6B6B)
                            )
                        }
                        Text(
                            "Import Failed",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { viewModel.resetState() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Amber,
                                contentColor = Color(0xFF261A00)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    }
}
