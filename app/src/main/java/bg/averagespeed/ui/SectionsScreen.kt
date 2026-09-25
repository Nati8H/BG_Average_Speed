package bg.averagespeed.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import bg.averagespeed.core.Geo
import bg.averagespeed.core.Section
import bg.averagespeed.service.TrackingHub
import bg.averagespeed.service.formatKm
import java.util.UUID

@Composable
fun SectionsScreen(sections: List<Section>) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Section?>(null) }
    val repo = TrackingHub.repository()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Вградените отсечки са с ПРИБЛИЗИТЕЛНИ координати. За точно засичане ги запишете сами " +
                    "(„Запиши нова отсечка“ в екрана Скорост), добавете по координати или импортирайте JSON. " +
                    "Официален списък: bgtoll.bg → Въпроси и отговори.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAdd = true }) { Text("Добави") }
                OutlinedButton(onClick = { showImport = true }) { Text("Импорт JSON") }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(repo.exportAll()))
                    toast(context, "Списъкът е копиран като JSON")
                }) { Text("Копирай JSON") }
                if (repo.hiddenCount() > 0) {
                    OutlinedButton(onClick = {
                        repo.restoreBuiltIn()
                        TrackingHub.reloadSections()
                    }) { Text("Върни вградените") }
                }
            }
        }
        items(sections, key = { it.id }) { s -> SectionItem(s, onDelete = { toDelete = s }) }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Изтриване") },
            text = { Text("Да се премахне ли „${s.fromName} – ${s.toName}“?") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(s)
                    TrackingHub.reloadSections()
                    toDelete = null
                }) { Text("Изтрий") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Назад") } },
        )
    }
    if (showAdd) AddSectionDialog(onDismiss = { showAdd = false })
    if (showImport) ImportDialog(onDismiss = { showImport = false })
}

@Composable
private fun SectionItem(s: Section, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.fromName} ↔ ${s.toName}", style = MaterialTheme.typography.titleSmall)
                Text(s.road, style = MaterialTheme.typography.bodySmall)
                val length = s.lengthM?.let { formatKm(it) }
                    ?: ("~" + formatKm(Geo.distanceM(s.startLat, s.startLon, s.endLat, s.endLon)))
                Text("Ограничение ${s.limitKmh} км/ч · $length", style = MaterialTheme.typography.bodySmall)
                val tag = when {
                    s.userDefined -> "добавена от вас"
                    s.approximate -> "приблизителни координати"
                    else -> null
                }
                tag?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.approximate) Amber else Green,
                    )
                }
            }
            TextButton(onClick = onDelete) { Text(if (s.userDefined) "Изтрий" else "Скрий") }
        }
    }
}

private fun parseLatLon(text: String): Pair<Double, Double>? {
    val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

@Composable
private fun AddSectionDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var road by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("140") }
    var lengthKm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Нова отсечка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(road, { road = it }, label = { Text("Път") }, singleLine = true)
                OutlinedTextField(from, { from = it }, label = { Text("От (име)") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("До (име)") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("Начална камера: шир., дълж.") },
                    placeholder = { Text("42.5745, 23.6930") }, singleLine = true)
                OutlinedTextField(end, { end = it }, label = { Text("Крайна камера: шир., дълж.") },
                    placeholder = { Text("42.4400, 23.8350") }, singleLine = true)
                OutlinedTextField(limit, { limit = it.filter(Char::isDigit) }, label = { Text("Ограничение, км/ч") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(lengthKm, { lengthKm = it }, label = { Text("Дължина, км (по желание)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = parseLatLon(start)
                val e = parseLatLon(end)
                val l = limit.toIntOrNull()
                if (s == null || e == null || l == null) {
                    toast(context, "Проверете координатите и ограничението")
                    return@TextButton
                }
                TrackingHub.repository().addUser(
                    Section(
                        id = "user-" + UUID.randomUUID().toString().take(8),
                        road = road.trim(),
                        fromName = from.trim().ifBlank { "Начало" },
                        toName = to.trim().ifBlank { "Край" },
                        startLat = s.first, startLon = s.second,
                        endLat = e.first, endLon = e.second,
                        limitKmh = l,
                        lengthM = lengthKm.replace(',', '.').toDoubleOrNull()?.let { it * 1000 },
                        userDefined = true,
                    )
                )
                TrackingHub.reloadSections()
                onDismiss()
            }) { Text("Запази") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт на отсечки") },
        text = {
            Column {
                Text(
                    "Поставете JSON във формата от „Копирай JSON“ (поле sections с from, to, start, end, limit, lengthKm).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { TrackingHub.repository().importUser(text) }
                    .onSuccess {
                        TrackingHub.reloadSections()
                        toast(context, "Импортирани отсечки: $it")
                        onDismiss()
                    }
                    .onFailure { toast(context, "Невалиден JSON: ${it.message}") }
            }) { Text("Импорт") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Назад") } },
    )
}
