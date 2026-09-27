package bg.averagespeed.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.averagespeed.core.ActiveSection
import bg.averagespeed.core.SectionResult
import bg.averagespeed.service.TrackingHub
import bg.averagespeed.service.UiState
import bg.averagespeed.service.formatKm
import kotlin.math.roundToInt

val Green = Color(0xFF43A047)
val Amber = Color(0xFFFFB300)
val Red = Color(0xFFE53935)

private val LIMITS = listOf(50, 60, 70, 80, 90, 100, 110, 120, 130, 140)

@Composable
fun TrackingScreen(state: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusRow(state, onStart, onStop)

        val active = state.active
        if (active != null) ActiveCard(active) else IdleCard(state)

        state.lastResult?.let { ResultCard(it) }

        if (state.tracking) {
            ManualCard(state)
            RecordCard(state)
        }
        VoiceSwitch()
    }
}

@Composable
private fun StatusRow(state: UiState, onStart: () -> Unit, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !state.tracking -> "Следенето е спряно"
                    !state.hasFix -> "Търсене на GPS сигнал…"
                    else -> "GPS: ±${state.accuracyM?.roundToInt() ?: "?"} м"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.tracking) {
            OutlinedButton(onClick = onStop) { Text("Спри") }
        } else {
            Button(onClick = onStart) { Text("Старт") }
        }
    }
}

fun avgColor(avg: Double?, limit: Int?): Color = when {
    avg == null || limit == null -> Color.Unspecified
    avg > limit -> Red
    avg > limit - 5 -> Amber
    else -> Green
}

@Composable
private fun ActiveCard(a: ActiveSection) {
    val color = avgColor(a.avgKmh, a.limitKmh)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (a.manual) "Ръчно измерване" else "В отсечка за средна скорост", style = MaterialTheme.typography.labelLarge)
            Text(a.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (a.road.isNotBlank()) Text(a.road, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Средна скорост до момента", style = MaterialTheme.typography.bodyMedium)
            Text(
                a.avgKmh?.roundToInt()?.toString() ?: "–",
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text("км/ч" + (a.limitKmh?.let { "  (ограничение $it)" } ?: ""), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Време", formatDuration(a.elapsedMs))
                Stat("Изминати", formatKm(a.travelledM))
                a.remainingM?.let { Stat(if (a.lengthIsEstimate) "Остават ~" else "Остават", formatKm(it)) }
            }
            a.maxAllowedKmhForRest?.let { max ->
                Spacer(Modifier.height(12.dp))
                val (text, c) = when {
                    max.isInfinite() -> "Почти сте в края на отсечката" to Green
                    max < 0 -> "Средната вече не може да падне под ограничението" to Red
                    else -> "За да сте в норма, карайте до ${max.roundToInt()} км/ч средно до края" to
                        if (a.limitKmh != null && max < a.limitKmh) Amber else Green
                }
                Text(text, color = c, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
            if (a.joinedMidway) {
                Text(
                    "Влязохте по средата на отсечката – средната се мери от точката на влизане",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                    textAlign = TextAlign.Center,
                )
            } else if (a.lengthIsEstimate && !a.manual) {
                Text(
                    "Координатите на камерите са приблизителни – показанията са ориентировъчни",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IdleCard(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Текуща скорост", style = MaterialTheme.typography.bodyMedium)
            Text(state.currentKmh?.roundToInt()?.toString() ?: "–", fontSize = 80.sp, fontWeight = FontWeight.Bold)
            Text("км/ч", style = MaterialTheme.typography.titleMedium)
            state.nearest?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (it.ahead) "Следваща отсечка по посоката на движение:" else "Най-близка отсечка:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("${it.title} (${it.road})", textAlign = TextAlign.Center)
                Text("${formatKm(it.distanceM)} · ограничение ${it.limitKmh} км/ч", style = MaterialTheme.typography.bodySmall)
            }
            if (!state.tracking) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Натиснете „Старт“. Приложението автоматично засича началото и края на отсечките и показва средната ви скорост.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(r: SectionResult) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Последна отсечка: ${r.title}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Средна: ${r.avgKmh.roundToInt()} км/ч" + (r.limitKmh?.let { " (огр. $it)" } ?: ""),
                color = if (r.limitKmh == null) Color.Unspecified else if (r.overLimit) Red else Green,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "${formatKm(r.lengthM)} за ${formatDuration(r.durationMs)}" + if (r.partial) " (част от отсечката)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ManualCard(state: UiState) {
    var limit by rememberSaveable { mutableIntStateOf(140) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Ръчно измерване", style = MaterialTheme.typography.titleSmall)
            val active = state.active
            if (active != null) {
                Text(
                    if (active.manual) "Натиснете „Край“, когато минете крайната камера." else "Автоматичното измерване може да бъде прекъснато.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { TrackingHub.stopActive() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(if (active.manual) "Край" else "Прекъсни") }
            } else {
                Text(
                    "За отсечка, която я няма в списъка: изберете ограничение и натиснете „Начало“ при първата камера.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LIMITS.forEach { l ->
                        FilterChip(selected = limit == l, onClick = { limit = l }, label = { Text("$l") })
                    }
                }
                Button(onClick = { TrackingHub.startManual(limit) }, enabled = state.hasFix) { Text("Начало") }
            }
        }
    }
}

@Composable
private fun RecordCard(state: UiState) {
    val context = LocalContext.current
    var showSave by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Запиши нова отсечка", style = MaterialTheme.typography.titleSmall)
            val rec = state.recording
            if (rec == null) {
                Text(
                    "Натиснете при минаване под началната камера, после при крайната – отсечката се запазва с точни координати и дължина.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (!TrackingHub.startRecording()) toast(context, "Няма GPS сигнал") },
                    enabled = state.hasFix,
                ) { Text("Маркирай НАЧАЛО") }
            } else {
                Text("Записване… изминати ${formatKm(rec.lengthM)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSave = true }) { Text("Маркирай КРАЙ") }
                    TextButton(onClick = { TrackingHub.cancelRecording() }) { Text("Откажи") }
                }
            }
        }
    }
    if (showSave) {
        SaveRecordingDialog(
            onDismiss = { showSave = false },
            onSave = { road, from, to, limit ->
                val saved = TrackingHub.finishRecording(road, from, to, limit)
                toast(context, if (saved != null) "Отсечката е запазена" else "Записът е твърде кратък или няма GPS")
                showSave = false
            },
        )
    }
}

@Composable
private fun SaveRecordingDialog(onDismiss: () -> Unit, onSave: (String, String, String, Int) -> Unit) {
    var road by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("140") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запази отсечката") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(road, { road = it }, label = { Text("Път (напр. АМ Тракия)") }, singleLine = true)
                OutlinedTextField(from, { from = it }, label = { Text("От") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("До") }, singleLine = true)
                OutlinedTextField(
                    limit, { limit = it.filter(Char::isDigit) },
                    label = { Text("Ограничение, км/ч") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(road.trim(), from.trim().ifBlank { "Начало" }, to.trim().ifBlank { "Край" }, limit.toIntOrNull() ?: 140) },
            ) { Text("Запази") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } },
    )
}

@Composable
private fun VoiceSwitch() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice", true)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Гласови съобщения", Modifier.weight(1f))
        Switch(checked = voice, onCheckedChange = {
            voice = it
            prefs.edit().putBoolean("voice", it).apply()
        })
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
