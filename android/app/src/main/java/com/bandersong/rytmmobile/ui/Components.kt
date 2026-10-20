package com.bandersong.rytmmobile.ui

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Mono, letter-spaced, coral — the site's section kicker. */
@Composable
fun Kicker(text: String, color: Color = P.Signal, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = T.Kicker.copy(color = color), modifier = modifier)

/** Card with a header strip, like the site's "WHAT IT IS  overview" panels. */
@Composable
fun Panel(
    title: String? = null, sub: String? = null, modifier: Modifier = Modifier,
    accent: Color = P.Line, trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(P.Panel)
            .border(1.dp, P.Line, RoundedCornerShape(6.dp))
    ) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().background(P.Well).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 3.dp, height = 18.dp).background(accent))
                Spacer(Modifier.width(10.dp))
                Text(title.uppercase(), style = T.Title.copy(fontSize = 20.sp_()))
                if (sub != null) { Spacer(Modifier.width(8.dp)); Text(sub, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false)) }
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line))
        }
        Column(Modifier.padding(14.dp), content = content)
    }
}

private fun Int.sp_() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Pill with a hard offset shadow that collapses when pressed (site's ADD TO DISCORD button). */
@Composable
fun HardButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    color: Color = P.Cv, ink: Color = P.Bg, enabled: Boolean = true, small: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val depth = if (small) 3.dp else 4.dp
    val off by animateDpAsState(if (pressed && enabled) 0.dp else depth, tween(60), label = "press")
    val shape = RoundedCornerShape(50)
    Box(modifier.padding(end = depth, bottom = depth)) {
        Box(Modifier.matchParentSize().offset(depth, depth).clip(shape).background(P.Shade))
        Box(
            Modifier.offset(depth - off, depth - off).clip(shape)
                .background(if (enabled) color else P.Well)
                .border(2.dp, if (enabled) P.Shade else P.Line, shape)
                .clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = if (small) 14.dp else 22.dp, vertical = if (small) 7.dp else 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), style = T.Button.copy(color = if (enabled) ink else P.Muted, fontSize = if (small) 15.sp_() else 19.sp_()), maxLines = 1)
        }
    }
}

/** Flat outlined chip button, for secondary actions. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = P.Ink, enabled: Boolean = true) {
    Box(
        modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, if (enabled) color.copy(alpha = .55f) else P.Line, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text.uppercase(), style = T.Label.copy(color = if (enabled) color else P.Line)) }
}

/** A 3.5mm jack socket. lit = something is patched in. */
@Composable
fun Jack(color: Color, lit: Boolean, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawJack(center, this.size.minDimension / 2, color, lit) }
}

fun DrawScope.drawJack(c: Offset, r: Float, color: Color, lit: Boolean) {
    drawCircle(P.Shade, r, c)
    drawCircle(if (lit) color else P.Line, r * .86f, c, style = Stroke(r * .22f))
    drawCircle(P.Bg, r * .52f, c)
    if (lit) drawCircle(color, r * .26f, c)
}

/** LED dot. */
@Composable
fun Led(on: Boolean, color: Color = P.Signal, blink: Boolean = false, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "led")
    val a by t.animateFloat(1f, .25f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "blink")
    Box(Modifier.size(size).clip(CircleShape).background(if (on) color.copy(alpha = if (blink) a else 1f) else P.Well).border(1.dp, P.Shade, CircleShape))
}

/** Patch cable between two points: dark outline, colored core, gravity sag. */
fun DrawScope.drawCable(a: Offset, b: Offset, color: Color, width: Float, sag: Float = 0.35f) {
    val d = (b - a).getDistance()
    val drop = d * sag
    val p = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x, a.y + drop, b.x, b.y + drop, b.x, b.y)
    }
    drawPath(p, P.Shade, style = Stroke(width + 4f, cap = StrokeCap.Round))
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round))
    drawPath(p, Color.White.copy(alpha = .18f), style = Stroke(width * .25f, cap = StrokeCap.Round))
}

/** 16 trig-key progress, like the Rytm's step row. */
@Composable
fun StepProgress(progress: Float, modifier: Modifier = Modifier, color: Color = P.Signal) {
    val lit = (progress.coerceIn(0f, 1f) * 16).toInt()
    val t = rememberInfiniteTransition(label = "steps")
    val blink by t.animateFloat(1f, .2f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "b")
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * 15) / 16
        val h = size.height
        for (i in 0 until 16) {
            val x = i * (w + gap)
            val on = i < lit
            val cur = i == lit && progress < 1f
            val fill = when { on -> color; cur -> color.copy(alpha = blink * .7f); else -> Color.Transparent }
            drawRoundRect(fill, Offset(x, 0f), Size(w, h), CornerRadius(3f))
            drawRoundRect(if (i % 4 == 0) P.Cv else P.Cv.copy(alpha = .55f), Offset(x, 0f), Size(w, h), CornerRadius(3f), style = Stroke(2f))
        }
    }
}

/** Hardware-style toggle: label + LED. */
@Composable
fun Switch(label: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(3.dp)).background(P.Well).border(1.dp, if (on) P.Cv.copy(alpha = .6f) else P.Line, RoundedCornerShape(3.dp))
            .clickable(role = Role.Switch) { onChange(!on) }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Led(on, P.Cv); Spacer(Modifier.width(8.dp)); Text(label.uppercase(), style = T.Label.copy(color = if (on) P.Ink else P.Muted))
    }
}

/** Single-line text field in a well, mono, labelled. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, secret: Boolean = false) {
    Column(modifier) {
        Text(label.uppercase(), style = T.Label)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = T.Data,
            cursorBrush = SolidColor(P.Cv),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(P.Bg)
                .border(1.dp, P.Line, RoundedCornerShape(3.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

/**
 * Bottom nav as a patch bay: one jack per section, and a cable that hangs from the top of the
 * bar into the selected jack. The cable re-patches with a spring when you switch.
 */
@Composable
fun PatchBay(items: List<Pair<String, Color>>, selected: Int, onSelect: (Int) -> Unit, lit: List<Boolean>) {
    val density = LocalDensity.current
    val x by animateFloatAsState(selected.toFloat(), spring(dampingRatio = .55f, stiffness = 260f), label = "patch")
    val col by animateColorAsState(items[selected].second, label = "col")
    Box(Modifier.fillMaxWidth().background(P.Panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.Line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(78.dp)) {
            items.forEachIndexed { i, (label, c) ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
                ) {
                    Jack(c, lit[i] || i == selected, 26.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(label.uppercase(), style = T.Label.copy(color = if (i == selected) P.Ink else P.Muted))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        Canvas(Modifier.matchParentSize().navigationBarsPadding()) {
            val slot = size.width / items.size
            val jackY = size.height - with(density) { (10 + 13 + 6 + 14).dp.toPx() }
            val end = Offset(slot * (x + .5f), jackY - with(density) { 13.dp.toPx() })
            // the cable hangs in from the top edge, sags, and drops straight down into the plug
            val start = Offset(slot * (x + .5f) - slot * .85f, -4f)
            val plugTop = Offset(end.x, end.y - 22f)
            val w = with(density) { 5.dp.toPx() }
            val path = Path().apply {
                moveTo(start.x, start.y)
                cubicTo(start.x, size.height * .95f, plugTop.x, plugTop.y - size.height * .55f, plugTop.x, plugTop.y)
            }
            drawPath(path, P.Shade, style = Stroke(w + 4f, cap = StrokeCap.Round))
            drawPath(path, col, style = Stroke(w, cap = StrokeCap.Round))
            drawPath(path, Color.White.copy(alpha = .18f), style = Stroke(w * .25f, cap = StrokeCap.Round))
            // plug body
            drawRoundRect(col, Offset(end.x - 7f, end.y - 22f), Size(14f, 24f), CornerRadius(4f))
            drawRoundRect(P.Shade, Offset(end.x - 7f, end.y - 22f), Size(14f, 24f), CornerRadius(4f), style = Stroke(2f))
        }
    }
}
