package bg.averagespeed.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bg.averagespeed.core.ActiveSection
import bg.averagespeed.service.TrackingHub
import bg.averagespeed.service.TrackingService
import bg.averagespeed.service.UiState
import bg.averagespeed.service.formatKm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Входна точка за Android Auto. */
class AvgSpeedCarAppService : CarAppService() {
    // Приложението не е от Google Play, затова се допуска всеки Android Auto хост.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = AvgSpeedSession()
}

class AvgSpeedSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        TrackingHub.init(carContext)
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted && !TrackingHub.state.value.tracking) {
            runCatching { TrackingService.start(carContext) }
        }
        return AvgSpeedScreen(carContext)
    }
}

/**
 * Екран в колата: отсечка, средна скорост, ограничение и оставащи км.
 * Винаги 4 реда и едно и също заглавие, за да се обновява като „refresh“ без лимит на стъпките.
 */
class AvgSpeedScreen(carContext: CarContext) : Screen(carContext) {
    private var state: UiState = TrackingHub.state.value

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingHub.state.collect { s ->
                    state = s
                    invalidate()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = state
        val active = s.active
        val pane = Pane.Builder()
        val rows = if (active != null) activeRows(active) else idleRows(s)
        rows.forEach { pane.addRow(it) }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Средна скорост")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun activeRows(a: ActiveSection): List<Row> {
        val avg = a.avgKmh
        val color = when {
            avg == null || a.limitKmh == null -> CarColor.DEFAULT
            avg > a.limitKmh -> CarColor.RED
            avg > a.limitKmh - 5 -> CarColor.YELLOW
            else -> CarColor.GREEN
        }
        val rest = a.maxAllowedKmhForRest?.let { max ->
            when {
                max.isInfinite() -> "почти сте в края"
                max < 0 -> "средната вече е над ограничението"
                else -> "до края карайте до ${max.roundToInt()} км/ч"
            }
        }
        val remaining = a.remainingM?.let { (if (a.lengthIsEstimate) "~" else "") + formatKm(it) } ?: "–"
        return listOf(
            row(a.title, a.road.ifBlank { "Отсечка за средна скорост" } + if (a.joinedMidway) " · от средата" else ""),
            row("Средна скорост", colored("${avg?.roundToInt() ?: "–"} км/ч", color)),
            row("Ограничение", a.limitKmh?.let { "$it км/ч" } ?: "–"),
            row("Остават", if (rest != null) "$remaining · $rest" else remaining),
        )
    }

    private fun idleRows(s: UiState): List<Row> {
        val status = when {
            !s.tracking -> "Отворете приложението на телефона и натиснете „Старт“"
            !s.hasFix -> "Търсене на GPS сигнал…"
            else -> "Следене на отсечките"
        }
        val next = s.nearest
        return listOf(
            row("Не сте в отсечка", status),
            row("Текуща скорост", s.currentKmh?.let { "${it.roundToInt()} км/ч" } ?: "–"),
            row(
                if (next?.ahead == true) "Следваща отсечка" else "Най-близка отсечка",
                next?.let { "${it.title} (${it.road})" } ?: "–",
            ),
            row("Разстояние", next?.let { "${formatKm(it.distanceM)} · огр. ${it.limitKmh} км/ч" } ?: "–"),
        )
    }

    private fun row(title: CharSequence, text: CharSequence): Row =
        Row.Builder().setTitle(title).addText(text).build()

    private fun colored(text: String, color: CarColor): CharSequence {
        if (color == CarColor.DEFAULT) return text
        return SpannableString(text).apply {
            setSpan(ForegroundCarColorSpan.create(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
