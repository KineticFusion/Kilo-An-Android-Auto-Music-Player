package com.example.kilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kilo.ui.design.KiloCorners
import com.example.kilo.ui.design.KiloSpacing
import com.example.kilo.ui.design.LocalKiloDesign

@Composable
fun KiloCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(KiloCorners.card),
    containerColor: Color = LocalKiloDesign.current.surface,
    backgroundBrush: Brush? = null,
    borderBrush: Brush? = null,
    borderWidth: Dp = 1.dp,
    contentPadding: Dp = KiloSpacing.standard,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .then(if (backgroundBrush != null) Modifier.background(backgroundBrush) else Modifier.background(containerColor))
            .then(if (borderBrush != null) Modifier.border(borderWidth, borderBrush, shape) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun KiloHeroCard(
    title: String,
    subtitle: String,
    eyebrow: String,
    accent: Color = LocalKiloDesign.current.accent,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    shape: Shape = RoundedCornerShape(KiloCorners.hero),
    glassFrame: Boolean = false,
    onClick: () -> Unit,
    artwork: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.aspectRatio(16f / 9f))
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        artwork()
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.02f), Color.Black.copy(alpha = 0.88f)))))
        if (glassFrame) {
            Box(
                Modifier.fillMaxSize().padding(10.dp).clip(shape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(eyebrow, color = Color.White.copy(alpha = 0.76f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Canvas(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(48.dp)) {
            drawCircle(color = accent, radius = size.minDimension / 2f)
            val center = Offset(size.width * 0.54f, size.height * 0.5f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.43f, size.height * 0.30f)
                lineTo(size.width * 0.70f, size.height * 0.50f)
                lineTo(size.width * 0.43f, size.height * 0.70f)
                close()
            }
            drawPath(path, color = Color.Black)
        }
    }
}

@Composable
fun KiloMoodCard(
    title: String,
    selected: Boolean,
    accent: Color = LocalKiloDesign.current.accent,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    height: Dp = 124.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier.width(width).height(height).clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = if (selected) 0.58f else 0.34f), Color(0xFF151722), Color(0xFF101117))))
            .then(if (selected) Modifier.border(1.5.dp, accent.copy(alpha = 0.9f), shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp)
    ) {
        Canvas(Modifier.align(Alignment.TopEnd).size(54.dp)) {
            drawCircle(color = accent.copy(alpha = 0.16f), radius = size.minDimension * 0.47f)
            val heights = listOf(0.34f, 0.62f, 0.92f, 0.56f, 0.76f)
            heights.forEachIndexed { index, fraction ->
                val x = size.width * (0.25f + index * 0.13f)
                val centerY = size.height * 0.5f
                val half = size.height * fraction * 0.28f
                drawLine(accent.copy(alpha = 0.92f), Offset(x, centerY - half), Offset(x, centerY + half), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (selected) "Selected" else "Play this mood", color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
fun KiloPlaylistCard(title: String, subtitle: String? = null, modifier: Modifier = Modifier, artworkSize: Dp = 72.dp, onClick: () -> Unit, trailingContent: @Composable RowScope.() -> Unit = {}, artwork: @Composable BoxScope.() -> Unit) {
    Row(modifier.clip(RoundedCornerShape(KiloCorners.card)).background(LocalKiloDesign.current.surface).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(artworkSize), content = artwork)
        Column(Modifier.weight(1f).padding(horizontal = KiloSpacing.standard, vertical = 12.dp)) {
            Text(title, color = LocalKiloDesign.current.foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = LocalKiloDesign.current.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(content = trailingContent)
    }
}

@Composable
fun KiloArtistCard(name: String, modifier: Modifier = Modifier, onClick: () -> Unit, artwork: @Composable BoxScope.() -> Unit) {
    Column(modifier.width(132.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(LocalKiloDesign.current.surface), content = artwork)
        Text(name, color = LocalKiloDesign.current.foreground, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KiloAlbumCard(title: String, subtitle: String? = null, modifier: Modifier = Modifier, width: Dp = 156.dp, artworkSize: Dp = 156.dp, onClick: () -> Unit, artwork: @Composable BoxScope.() -> Unit) {
    Column(modifier.width(width).clip(RoundedCornerShape(KiloCorners.card)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(artworkSize).clip(RoundedCornerShape(KiloCorners.card)).background(LocalKiloDesign.current.surface), content = artwork)
        Text(title, color = LocalKiloDesign.current.foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, color = LocalKiloDesign.current.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KiloButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = LocalKiloDesign.current.accent)) {
        Text(text, color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun KiloFloatingAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    FloatingActionButton(onClick = onClick, containerColor = LocalKiloDesign.current.accent, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiloBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, content = content)
}

@Composable
fun KiloHeader(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    titleSize: androidx.compose.ui.unit.TextUnit = 25.sp,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) Text(eyebrow, color = LocalKiloDesign.current.accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Text(title, color = LocalKiloDesign.current.foreground, fontSize = titleSize, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(content = trailingContent)
    }
}

@Composable
fun KiloSection(title: String, modifier: Modifier = Modifier, accent: Color = LocalKiloDesign.current.accent, trailingContent: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.15.sp)
        Row(content = trailingContent)
    }
}

@Composable
fun KiloListItem(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        leadingContent()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (titleContent != null) Row(content = titleContent)
            else Text(title, color = LocalKiloDesign.current.foreground, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = LocalKiloDesign.current.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(content = trailingContent)
    }
}
