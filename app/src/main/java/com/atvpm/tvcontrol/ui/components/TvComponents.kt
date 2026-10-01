package com.atvpm.tvcontrol.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atvpm.tvcontrol.ui.theme.TvColors

@Composable
fun TvFocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    focusScale: Float = 1.025f,
    shape: Shape = RoundedCornerShape(10.dp),
    containerColor: Color = TvColors.SurfaceElevated,
    focusedContainerColor: Color = TvColors.SurfaceFocused,
    selectedContainerColor: Color = Color(0xFF163853),
    focusBorderColor: Color = TvColors.FocusRing,
    onFocused: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable (isFocused: Boolean) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "tvFocusScale",
    )

    val bg = when {
        !enabled && isFocused -> Color(0xFF1A2233)
        !enabled -> TvColors.SurfaceDisabled
        isFocused -> focusedContainerColor
        selected -> selectedContainerColor
        else -> containerColor
    }

    val borderStroke = when {
        isFocused && enabled -> BorderStroke(2.5.dp, focusBorderColor)
        isFocused && !enabled -> BorderStroke(2.dp, TvColors.TextMuted)
        selected -> BorderStroke(1.5.dp, TvColors.FocusGlow)
        else -> BorderStroke(1.dp, TvColors.Divider.copy(alpha = if (enabled) 0.55f else 0.25f))
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused?.invoke()
            }
            .clip(shape)
            .focusable()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.52f),
        shape = shape,
        color = bg,
        border = borderStroke,
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content(isFocused)
        }
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    accentColor: Color = TvColors.FocusRing,
    containerColor: Color = TvColors.SurfaceElevated,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = containerColor,
        focusBorderColor = accentColor,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    ) { isFocused ->
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = text,
                color = if (isFocused && enabled) accentColor else TvColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TvColors.TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
fun TvFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvFocusableSurface(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        focusScale = 1.03f,
        shape = RoundedCornerShape(18.dp),
        containerColor = if (selected) Color(0xFF0E3A5A) else TvColors.Surface,
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp),
    ) { isFocused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TvColors.FocusRing),
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = label,
                color = when {
                    isFocused -> TvColors.FocusRing
                    selected -> TvColors.TextPrimary
                    else -> TvColors.TextSecondary
                },
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun AppIconBox(
    drawable: Drawable?,
    label: String,
    size: Dp = 34.dp,
) {
    val bitmap: ImageBitmap? = remember(drawable) {
        drawable?.toImageBitmapOrNull(64)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(TvColors.InfoBg)
                .border(1.dp, TvColors.FocusRing.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(2).uppercase(),
                color = TvColors.FocusRing,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Drawable.toImageBitmapOrNull(maxPx: Int): ImageBitmap? = runCatching {
    if (this is BitmapDrawable && bitmap != null) {
        val src = bitmap
        if (src.width <= maxPx && src.height <= maxPx) {
            return src.asImageBitmap()
        }
        return Bitmap.createScaledBitmap(src, maxPx, maxPx, true).asImageBitmap()
    }
    val w = intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
    val h = intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()
