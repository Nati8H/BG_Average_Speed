package bg.averagespeed.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import bg.averagespeed.MainActivity
import bg.averagespeed.R
import bg.averagespeed.core.Fix
import bg.averagespeed.core.TrackerEvent
import java.util.Locale
import kotlin.math.roundToInt

/** Фонова услуга: получава GPS точки, подава ги на TrackingHub, показва известие и гласови съобщения. */
class TrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var wasOverLimit = false
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TrackingHub.init(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("bg", "BG"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Следене на отсечките за средна скорост…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        locationManager.removeUpdates(this)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        TrackingHub.setTracking(true)
        return START_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        TrackingHub.setTracking(false)
        tts?.shutdown()
        tone?.release()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val fix = Fix(
            lat = location.latitude,
            lon = location.longitude,
            timeMs = location.elapsedRealtimeNanos / 1_000_000,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        )
        TrackingHub.onFix(fix).forEach(::handleEvent)
        val state = TrackingHub.state.value
        val active = state.active

        val over = active?.overLimit == true
        if (over && !wasOverLimit) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            speak("Внимание, средната скорост е над ограничението")
        }
        wasOverLimit = over

        val text = if (active != null) {
            val avg = active.avgKmh?.roundToInt()?.toString() ?: "–"
            val limit = active.limitKmh?.let { " / огр. $it" } ?: ""
            "Средна: $avg км/ч$limit · ${active.title}"
        } else {
            state.nearest?.let { "Най-близка: ${it.title} (${formatKm(it.distanceM)})" }
                ?: "Следене на отсечките за средна скорост…"
        }
        if (text != lastNotificationText) {
            lastNotificationText = text
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun handleEvent(event: TrackerEvent) {
        when (event) {
            is TrackerEvent.Entered -> {
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                speak("Начало на отсечка за средна скорост. Ограничение ${event.active.limitKmh ?: ""} километра в час.")
            }
            is TrackerEvent.Finished -> {
                tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                speak("Край на отсечката. Средна скорост ${event.result.avgKmh.roundToInt()} километра в час.")
            }
            is TrackerEvent.Cancelled -> Unit
        }
    }

    private fun speak(text: String) {
        if (ttsReady && getSharedPreferences("settings", MODE_PRIVATE).getBoolean("voice", true)) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Средна скорост", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle("Средна скорост")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Спри", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "bg.averagespeed.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}

fun formatKm(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} м" else String.format(Locale.US, "%.1f км", meters / 1000)
