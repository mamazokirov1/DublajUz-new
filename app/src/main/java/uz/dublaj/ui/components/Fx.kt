package uz.dublaj.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Bosilganda yumshoq kichrayadi, qo'yib yuborilganda sakrab qaytadi. Bosishni to'smaydi. */
fun Modifier.pressScale(down: Float = 0.95f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(
        if (pressed) down else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        }
}

/** Ekranda paydo bo'lganda pastdan yumshoq suzib chiqadi; [index] ketma-ket kechikish beradi. */
fun Modifier.appear(index: Int = 0): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 70L).coerceAtMost(420L))
        a.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 40f
    }
}

/** Bosh ekran uchun ixcham tezkor tugma: ikona + qisqa nom (izohsiz, chalkashlik kam). */
@Composable
fun MiniTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.pressScale(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (highlighted) cs.primary else cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 4.dp else 1.dp)
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (highlighted) cs.onPrimary.copy(alpha = 0.2f) else cs.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (highlighted) cs.onPrimary else cs.primary, modifier = Modifier.size(22.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlighted) cs.onPrimary else cs.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}
