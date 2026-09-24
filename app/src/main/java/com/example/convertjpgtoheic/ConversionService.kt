package com.example.convertjpgtoheic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a conversion run alive while the app is in the background.
 *
 * Without this the work sits in the Activity's process with nothing holding it up, and Android
 * reclaims it part-way through a long run — leaving a half-converted library. The service does no
 * work itself: [ConversionEngine] owns the loop, and this just pins the process and mirrors the
 * engine's state into a notification.
 */
class ConversionService : Service() {

    private val engine by lazy { ConversionEngine.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var runMode = RunMode.CONVERT

    /** Last time a progress notification went out. */
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read the mode first so the very first notification is titled correctly; going foreground
        // before this would briefly announce "Converting photos" for a dry run.
        intent?.getStringExtra(EXTRA_MODE)?.let { runMode = RunMode.valueOf(it) }

        // Must happen within a few seconds of startForegroundService or the system kills us.
        goForeground(buildNotification("Starting…", 0, 0, indeterminate = true, mode = runMode))

        when (intent?.action) {
            ACTION_CANCEL -> {
                engine.cancel()
                // If nothing is running, this start would otherwise leave us foreground forever.
                if (observer?.isActive != true) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Reset so the first progress update of this run is not swallowed by a
                // throttle window left over from the previous one.
                lastNotifyAt = 0L
                if (engine.start(runMode, intent.toOptions())) {
                    observeEngine()
                } else {
                    // Nothing to do — a run is already going, or originals are awaiting
                    // confirmation. Without this we would stay foreground with no work to end us.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun observeEngine() {
        if (observer?.isActive == true) return
        observer = scope.launch {
            engine.state.collectLatest { state ->
                when (state) {
                    is UiState.Working -> notifyProgress(
                        buildNotification(
                            text = if (state.progress.promptAt > 0) {
                                "${state.progress.currentName}  " +
                                    "· delete at ${state.progress.pendingDeletions}/${state.progress.promptAt}"
                            } else {
                                state.progress.currentName
                            },
                            done = state.progress.done,
                            total = state.progress.total,
                            indeterminate = false,
                            mode = state.mode,
                        )
                    )

                    // Not a finished run: the work is blocked on a dialog, so stay foreground.
                    is UiState.AwaitingDeletion -> notify(
                        NOTIFICATION_ID,
                        buildNotification(
                            text = if (state.lowOnSpace) {
                                "Storage low — confirm deleting ${state.pending} originals"
                            } else {
                                "Confirm deleting ${state.pending} originals"
                            },
                            done = state.done,
                            total = state.total,
                            indeterminate = false,
                            mode = state.mode,
                        ),
                    )

                    UiState.Scanning -> notify(
                        NOTIFICATION_ID,
                        buildNotification("Scanning…", 0, 0, indeterminate = true, mode = runMode),
                    )

                    is UiState.Finished -> {
                        // Stop observing first: deletion carries on in the Activity and emits
                        // further Finished states, and we must not re-run the teardown for those.
                        observer?.cancel()
                        observer = null
                        finishWith(state.report)
                        return@collectLatest
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Drops the foreground notification and leaves a plain one behind. Deletion needs an Activity
     * for the system confirmation dialog, so when originals are still pending the notification's
     * job is to get the user back into the app.
     */
    private fun finishWith(report: RunReport) {
        val pending = report.deletionOutcome == DeletionOutcome.PENDING
        val summary = when {
            // Clean-up converts nothing, so the conversion wording below would read as nonsense.
            report.mode == RunMode.CLEAN_UP && pending -> "Leftovers found — tap to remove them"
            report.mode == RunMode.CLEAN_UP -> "No leftover originals found"

            report.failureCount > 0 && report.converted == 0 -> "Nothing converted — tap for details"
            pending -> "Converted ${report.converted} — tap to delete the originals"
            report.cancelled -> "Cancelled after ${report.converted} photos"
            report.mode == RunMode.DRY_RUN -> "Dry run finished — tap for the numbers"
            else -> "Converted ${report.converted} photos"
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        // Deliberately a different id from the ongoing one. Re-posting the id that was just
        // removed can lose the notification, and this is the only thing telling the user to come
        // back and confirm the deletion.
        notify(
            COMPLETION_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(summary)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        )
        stopSelf()
    }

    private fun buildNotification(
        text: String,
        done: Int,
        total: Int,
        indeterminate: Boolean,
        mode: RunMode = RunMode.CONVERT,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(
            when (mode) {
                RunMode.DRY_RUN -> "Measuring photos"
                RunMode.CLEAN_UP -> "Finding leftovers"
                RunMode.CONVERT -> "Converting photos"
            }
        )
        .setContentText(if (total > 0) "$text  (${done + 1}/$total)" else text)
        .setProgress(total, done, indeterminate)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent())
        // A real drawable rather than 0: some builds reject an action with no icon resource.
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.action_cancel),
            cancelIntent(),
        )
        .build()

    /**
     * Rate-limited variant for the per-photo updates. A fast run posts several a second, and the
     * system starts dropping notifications when an app exceeds roughly ten per second.
     */
    private fun notifyProgress(notification: android.app.Notification) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < PROGRESS_INTERVAL_MS) return
        lastNotifyAt = now
        notify(NOTIFICATION_ID, notification)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS may be denied; the run must carry on regardless, so this is advisory.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching {
                NotificationManagerCompat.from(this).notify(id, notification)
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conversion progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Android 14 requires the service type at the call site. Android 15 added a type specifically
     * for transcoding media, which is exactly this workload, so prefer it where it exists.
     */
    private fun goForeground(notification: android.app.Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_NOTIFICATION_ID = 2

        /** Floor between progress notification updates, to stay under the system's rate limit. */
        private const val PROGRESS_INTERVAL_MS = 500L

        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_START = "start_ms"
        private const val EXTRA_END = "end_ms"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_DELETE = "delete"
        private const val EXTRA_ONLY_SMALLER = "only_smaller"
        private const val EXTRA_SKIP_SMALL = "skip_small"
        private const val EXTRA_SKIP_EXISTING = "skip_existing"
        private const val EXTRA_MOTION_POLICY = "motion_policy"

        /**
         * Clears the "tap to delete the originals" notification.
         *
         * setAutoCancel only fires when the notification itself is tapped; a user who opens the
         * app from the launcher and deals with the prompt there would otherwise be left looking at
         * a notification asking for something already done.
         */
        fun clearCompletionNotification(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context).cancel(COMPLETION_NOTIFICATION_ID)
            }
        }

        fun start(context: Context, mode: RunMode, options: RunOptions) {
            val intent = Intent(context, ConversionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_START, options.startMs)
                .putExtra(EXTRA_END, options.endMs)
                .putExtra(EXTRA_QUALITY, options.quality)
                .putExtra(EXTRA_DELETE, options.deleteOriginals)
                .putExtra(EXTRA_ONLY_SMALLER, options.onlyIfSmaller)
                .putExtra(EXTRA_SKIP_SMALL, options.skipSmall)
                .putExtra(EXTRA_SKIP_EXISTING, options.skipAlreadyConverted)
                .putExtra(EXTRA_MOTION_POLICY, options.motionPolicy.name)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun Intent.toOptions() = RunOptions(
            startMs = getLongExtra(EXTRA_START, 0L),
            endMs = getLongExtra(EXTRA_END, Long.MAX_VALUE),
            quality = getIntExtra(EXTRA_QUALITY, Settings.DEFAULT_QUALITY),
            deleteOriginals = getBooleanExtra(EXTRA_DELETE, false),
            onlyIfSmaller = getBooleanExtra(EXTRA_ONLY_SMALLER, true),
            skipSmall = getBooleanExtra(EXTRA_SKIP_SMALL, true),
            skipAlreadyConverted = getBooleanExtra(EXTRA_SKIP_EXISTING, true),
            motionPolicy = runCatching {
                MotionPhotoPolicy.valueOf(getStringExtra(EXTRA_MOTION_POLICY) ?: "")
            }.getOrDefault(MotionPhotoPolicy.KEEP_VIDEO),
        )
    }
}
