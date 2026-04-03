package com.autobook.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.autobook.domain.cover.CoverResult
import com.autobook.ui.theme.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two-phase cover picker:
 * Phase 1: Grid of API search results — tap to select
 * Phase 2: Crop the selected image to 2:3 book-cover aspect ratio
 */
@Composable
fun CoverPickerDialog(
    covers: List<CoverResult>,
    isLoading: Boolean,
    onSelectAndCrop: (CoverResult) -> Unit,
    onConfirmCropped: (Bitmap) -> Unit,
    selectedBitmap: Bitmap?,
    onDismiss: () -> Unit
) {
    if (selectedBitmap != null) {
        // Phase 2: Crop
        CropPhase(
            sourceBitmap = selectedBitmap,
            onConfirm = onConfirmCropped,
            onBack = { /* parent resets selectedBitmap */ onDismiss() }
        )
    } else {
        // Phase 1: Pick from grid
        PickPhase(
            covers = covers,
            isLoading = isLoading,
            onSelect = onSelectAndCrop,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun PickPhase(
    covers: List<CoverResult>,
    isLoading: Boolean,
    onSelect: (CoverResult) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(20.dp),
            color = Navy
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Choose Cover",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Amber)
                            Spacer(Modifier.height(12.dp))
                            Text("Searching covers...", color = TextMuted)
                        }
                    }
                } else if (covers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No covers found", color = TextMuted)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(covers) { cover ->
                            CoverOption(
                                cover = cover,
                                onClick = { onSelect(cover) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverOption(
    cover: CoverResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NavySurface)
    ) {
        Column {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(cover.url)
                    .crossfade(true)
                    .build(),
                contentDescription = cover.source,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                contentScale = ContentScale.Crop
            )
            Text(
                text = cover.source,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun CropPhase(
    sourceBitmap: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    val bitmapImage = remember(sourceBitmap) { sourceBitmap.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val cropAspect = 2f / 3f

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    val cropRect = remember(canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@remember Rect.Zero
        val cw = canvasSize.width.toFloat()
        val ch = canvasSize.height.toFloat()
        val maxCropW = cw * 0.85f
        val maxCropH = ch * 0.85f
        val cropW: Float
        val cropH: Float
        if (maxCropW / maxCropH < cropAspect) {
            cropW = maxCropW; cropH = cropW / cropAspect
        } else {
            cropH = maxCropH; cropW = cropH * cropAspect
        }
        Rect((cw - cropW) / 2f, (ch - cropH) / 2f, (cw + cropW) / 2f, (ch + cropH) / 2f)
    }

    LaunchedEffect(canvasSize, sourceBitmap) {
        if (canvasSize.width == 0 || canvasSize.height == 0 || initialized) return@LaunchedEffect
        val cRect = cropRect
        if (cRect == Rect.Zero) return@LaunchedEffect
        val scaleToFill = max(cRect.width / sourceBitmap.width, cRect.height / sourceBitmap.height)
        scale = scaleToFill
        offsetX = cRect.left - (sourceBitmap.width * scaleToFill - cRect.width) / 2f
        offsetY = cRect.top - (sourceBitmap.height * scaleToFill - cRect.height) / 2f
        initialized = true
    }

    fun clamp() {
        val bw = sourceBitmap.width * scale
        val bh = sourceBitmap.height * scale
        offsetX = min(cropRect.left, max(cropRect.right - bw, offsetX))
        offsetY = min(cropRect.top, max(cropRect.bottom - bh, offsetY))
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(20.dp),
            color = Navy
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Crop Cover",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("Pinch to zoom, drag to position", color = TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black, RoundedCornerShape(12.dp))
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val oldScale = scale
                                val minScale = max(
                                    cropRect.width / sourceBitmap.width,
                                    cropRect.height / sourceBitmap.height
                                )
                                scale = (scale * zoom).coerceIn(minScale, minScale * 5f)
                                val cx = cropRect.center.x
                                val cy = cropRect.center.y
                                offsetX = cx - (cx - offsetX) * (scale / oldScale) + pan.x
                                offsetY = cy - (cy - offsetY) * (scale / oldScale) + pan.y
                                clamp()
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(
                            image = bitmapImage,
                            dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                            dstSize = IntSize(
                                (sourceBitmap.width * scale).roundToInt(),
                                (sourceBitmap.height * scale).roundToInt()
                            )
                        )
                        val overlay = Color.Black.copy(alpha = 0.6f)
                        drawRect(overlay, Offset.Zero, Size(size.width, cropRect.top))
                        drawRect(overlay, Offset(0f, cropRect.bottom), Size(size.width, size.height - cropRect.bottom))
                        drawRect(overlay, Offset(0f, cropRect.top), Size(cropRect.left, cropRect.height))
                        drawRect(overlay, Offset(cropRect.right, cropRect.top), Size(size.width - cropRect.right, cropRect.height))
                        drawRect(
                            color = Amber,
                            topLeft = Offset(cropRect.left, cropRect.top),
                            size = Size(cropRect.width, cropRect.height),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                        val thirdW = cropRect.width / 3f
                        val thirdH = cropRect.height / 3f
                        val gridColor = Color.White.copy(alpha = 0.3f)
                        for (i in 1..2) {
                            drawLine(gridColor, Offset(cropRect.left + thirdW * i, cropRect.top), Offset(cropRect.left + thirdW * i, cropRect.bottom), strokeWidth = 1f)
                            drawLine(gridColor, Offset(cropRect.left, cropRect.top + thirdH * i), Offset(cropRect.right, cropRect.top + thirdH * i), strokeWidth = 1f)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val srcLeft = ((cropRect.left - offsetX) / scale).roundToInt().coerceIn(0, sourceBitmap.width)
                            val srcTop = ((cropRect.top - offsetY) / scale).roundToInt().coerceIn(0, sourceBitmap.height)
                            val srcW = (cropRect.width / scale).roundToInt().coerceIn(1, sourceBitmap.width - srcLeft)
                            val srcH = (cropRect.height / scale).roundToInt().coerceIn(1, sourceBitmap.height - srcTop)
                            val cropped = Bitmap.createBitmap(sourceBitmap, srcLeft, srcTop, srcW, srcH)
                            val final = Bitmap.createScaledBitmap(cropped, 400, 600, true)
                            onConfirm(final)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Navy)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Apply", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
