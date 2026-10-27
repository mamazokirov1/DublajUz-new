package uz.dublaj.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bosh ekrandagi katta tezkor tugma (masalan, "Matn → ovoz"). */
@Composable
fun QuickActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val container = if (highlighted) cs.primary else cs.surface
    val content = if (highlighted) cs.onPrimary else cs.onSurface
    val sub = if (highlighted) cs.onPrimary.copy(alpha = 0.85f) else cs.onSurfaceVariant
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 4.dp else 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (highlighted) Color.White.copy(alpha = 0.2f) else cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = if (highlighted) cs.onPrimary else cs.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlighted) cs.primary else cs.onTertiaryContainer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (highlighted) Color.White else cs.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Pro'ga chorlovchi karta (bepul foydalanuvchilarga). */
@Composable
fun ProPromoCard(priceText: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Star, null, tint = Color.White)
                Text("DublajUz Pro", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(
                "Cheksiz dublyaj, reklamasiz, uzun matnlar va maxsus ranglar — bir marta to'lov bilan.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProLine(Icons.Default.AllInclusive, "Kunlik limit yo'q, video uzunligi cheklanmaydi")
                ProLine(Icons.Default.Block, "Reklama yo'q")
                ProLine(Icons.Default.Queue, "Cheksiz navbat")
                ProLine(Icons.Default.TextFields, "Matn → ovoz: 200 000 belgigacha")
                ProLine(Icons.Default.Palette, "5 ta maxsus rang mavzusi")
            }
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = cs.primary)
            ) {
                Text(
                    if (priceText != null) "Pro olish — $priceText" else "Pro olish",
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ProLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/** Kichik "PRO" belgisi — Pro'ga xos sozlamalar yonida. */
@Composable
fun ProTag(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        "PRO",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = cs.onTertiary,
        modifier = modifier
            .clip(CircleShape)
            .background(cs.tertiary)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
