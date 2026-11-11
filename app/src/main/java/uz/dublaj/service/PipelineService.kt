package uz.dublaj.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import timber.log.Timber
import uz.dublaj.MainActivity
import uz.dublaj.R

/**
 * Uzoq jarayon (STT/tarjima/TTS/mux) paytida ilovani fonda tirik tutadi.
 * Ishning o'zi ViewModel'da bajariladi; bu servis faqat jarayonni himoya qiladi.
 */
class PipelineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Bildirishnomadagi «Bekor qilish» tugmasi
        if (intent?.action == ACTION_CANCEL) {
            onCancelRequested?.invoke()
            return START_NOT_STICKY
        }
        createChannel()
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Dublyaj ishlamoqda…"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
        val notification = buildNotification(text, progress)
        try {
            val dataSync = intent?.getBooleanExtra(EXTRA_DATA_SYNC, false) == true
            val type = when {
                // Modellarni yuklash/import = dataSync; video qayta ishlash (Android 15+) = mediaProcessing
                Build.VERSION.SDK_INT >= 35 && !dataSync -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } catch (e: Exception) {
            Timber.w(e, "startForeground")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DublajUz:pipeline").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: tizim foreground-servis vaqt limitiga yetganda chaqiriladi — servisni to'xtatamiz. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Timber.w("Foreground servis vaqt limiti (type=$fgsType)")
        notifyDone(this, "DublajUz", "Tizim fonda ishlash vaqtini tugatdi. Ilovani oching va jarayonni davom ettiring.")
        stopSelf()
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dublyaj jarayoni", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String, progress: Int = -1): Notification {
        val open = PendingIntent.getActivity(
            this, 0, openAppIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DublajUz")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_dublaj)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(cancelAction(this))
            .setContentIntent(open)
        if (progress in 0..100) b.setProgress(100, progress, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "dublaj_pipeline"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DATA_SYNC = "data_sync"
        private const val EXTRA_PROGRESS = "progress"
        private const val ACTION_CANCEL = "uz.dublaj.action.CANCEL"

        /** Bildirishnomadagi «Bekor qilish» bosilganda ViewModel shuni chaqiradi. */
        @Volatile
        var onCancelRequested: (() -> Unit)? = null

        private fun cancelAction(context: Context): NotificationCompat.Action {
            val pi = PendingIntent.getService(
                context, 2,
                Intent(context, PipelineService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Action.Builder(0, "Bekor qilish", pi).build()
        }

        /**
         * Bildirishnomadan ilovani ochish: mavjud oynani oldinga olib chiqadi
         * (yangi nusxa yaratilsa, jarayon holati — ViewModel — yo'qolar edi).
         */
        private fun openAppIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        /** Ishlayotgan bildirishnomadagi matn va foizni yangilaydi (servis ishlab turgan bo'lsa). */
        fun update(context: Context, text: String, progressPercent: Int) {
            try {
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val open = PendingIntent.getActivity(
                    context, 0, openAppIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val b = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("DublajUz")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_stat_dublaj)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(cancelAction(context))
                    .setContentIntent(open)
                if (progressPercent in 0..100) b.setProgress(100, progressPercent, false) else b.setProgress(0, 0, true)
                nm.notify(NOTIF_ID, b.build())
            } catch (e: Exception) {
                Timber.w(e, "notification update")
            }
        }

        fun start(context: Context, text: String, dataSync: Boolean = false) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PipelineService::class.java)
                        .putExtra(EXTRA_TEXT, text)
                        .putExtra(EXTRA_DATA_SYNC, dataSync)
                )
            } catch (e: Exception) {
                Timber.w(e, "PipelineService start")   // servis bo'lmasa ham ish davom etadi
            }
        }

        /** Ish tugaganda (ilova fonda bo'lsa ham) ko'rinadigan oddiy bildirishnoma. */
        fun notifyDone(context: Context, title: String, text: String) {
            try {
                // Ruxsat berilmagan bo'lsa (Android 13+) yoki bildirishnomalar o'chirilgan bo'lsa — jim o'tamiz
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel("dublaj_done") == null) {
                    nm.createNotificationChannel(
                        NotificationChannel("dublaj_done", "Tayyor bo'lgan ishlar", NotificationManager.IMPORTANCE_HIGH)
                    )
                }
                val open = PendingIntent.getActivity(
                    context, 1, openAppIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = NotificationCompat.Builder(context, "dublaj_done")
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_stat_dublaj)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                nm.notify(1002, n)
            } catch (e: Exception) {
                Timber.w(e, "notifyDone")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PipelineService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
