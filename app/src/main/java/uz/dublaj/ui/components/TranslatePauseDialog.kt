package uz.dublaj.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import uz.dublaj.data.TranslatePause
import uz.dublaj.util.safeStart

/**
 * Tarjima kalit/limit/internet xatosi tufayli to'xtaganda chiqadi.
 * Jarayon yo'qolmaydi: yangi kalit kiritib yoki limit ochilganda aynan shu joydan davom etiladi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslatePauseDialog(
    info: TranslatePause,
    providerLabel: String,
    keyUrl: String,
    onContinue: (newKey: String) -> Unit,
    onSkipRest: () -> Unit,
    onPause: () -> Unit
) {
    val context = LocalContext.current
    var newKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* tasodifan yopilib ketmasin */ },
        title = { Text("Tarjima to'xtab qoldi") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "$providerLabel: ${info.done}/${info.total} gap tayyor. Tayyor qismi saqlangan, " +
                        "davom etganda qaytadan tarjima qilinmaydi.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    info.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Yana bir API kalit (ixtiyoriy)") },
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    supportingText = { Text("Barcha kalitlar ishlamadi. Yangi kalit ro'yxatga qo'shiladi, eskilari saqlanadi. Bo'sh qoldirsangiz hamma kalit qayta sinab ko'riladi.") }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Kalitni yashirish" else "Kalitni ko'rsatish") }
                    TextButton(onClick = {
                        safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                    }) { Text("Kalit olish") }
                }
                TextButton(onClick = onSkipRest) { Text("Qolganini tarjimasiz o'tkazib yuborish") }
            }
        },
        confirmButton = {
            Button(onClick = { onContinue(newKey) }) {
                Text(if (newKey.isNotBlank()) "Kalitni qo'shib davom etish" else "Qayta urinish")
            }
        },
        dismissButton = {
            TextButton(onClick = onPause) { Text("Keyinroq davom etaman") }
        }
    )
}
