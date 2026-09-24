package com.example.convertjpgtoheic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings as AndroidSettings
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.convertjpgtoheic.databinding.ActivityMainBinding
import com.example.convertjpgtoheic.databinding.ItemFailedPhotoBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine by lazy { ConversionEngine.get(this) }
    private val settings by lazy { Settings(this) }

    private var range: DateRange? = null
    private var scannedOnLaunch = false

    private val dateFormat: DateFormat by lazy { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionState() }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        engine.onDeletionResult(result.resultCode == RESULT_OK)
        if (!engine.hasPendingDeletion) ConversionService.clearCompletionNotification(this)
    }

    /**
     * The failed photo whose system delete dialog is on screen, paired with its row, so the row can
     * be removed once the delete is confirmed. Separate from [deleteLauncher] because this is an
     * ad-hoc single delete from the report, not part of the run's batched deletion sequence.
     */
    private var pendingFailureDelete: Pair<FailedPhoto, View>? = null

    private val failureDeleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingFailureDelete
        pendingFailureDelete = null
        if (result.resultCode == RESULT_OK && pending != null) {
            val (failure, row) = pending
            binding.failuresList.removeView(row)
            if (binding.failuresList.childCount == 0) binding.failuresHeading.isVisible(false)
            Toast.makeText(this, getString(R.string.deleted_one, failure.name), Toast.LENGTH_SHORT)
                .show()
            refreshStorage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        restoreSettings()

        binding.rangeButton.setOnClickListener { showRangePicker() }
        binding.dryRunButton.setOnClickListener { startRun(RunMode.DRY_RUN) }
        binding.convertButton.setOnClickListener { confirmThenConvert() }
        binding.cleanupButton.setOnClickListener { confirmThenCleanUp() }
        binding.cancelButton.setOnClickListener { engine.cancel() }

        binding.qualitySlider.addOnChangeListener { _, value, _ ->
            showQuality(value.toInt())
            settings.quality = value.toInt()
        }
        binding.switchDelete.setOnCheckedChangeListener { _, on -> settings.deleteOriginals = on }
        binding.switchOnlySmaller.setOnCheckedChangeListener { _, on -> settings.onlyIfSmaller = on }
        binding.switchSkipSmall.setOnCheckedChangeListener { _, on -> settings.skipSmall = on }
        binding.switchSkipExisting.setOnCheckedChangeListener { _, on -> settings.skipAlreadyConverted = on }
        binding.motionPolicyGroup.setOnCheckedChangeListener { _, _ ->
            settings.motionPolicy = selectedMotionPolicy()
        }

        observeEngine()
        requestPermissions()
    }

    /**
     * Re-scans the remembered range once, after permissions are known. Doing this in onCreate
     * instead would query MediaStore before access is granted.
     */
    private fun scanRestoredRange() {
        if (scannedOnLaunch || engine.isBusy || engine.hasPendingDeletion) return
        if (!granted(readImagePermission())) return
        val current = range ?: return
        scannedOnLaunch = true
        engine.scan(current)
    }

    override fun onResume() {
        super.onResume()
        // Access may have been changed from the system settings screen while we were away.
        refreshPermissionState()
        // Nothing left to confirm, so the finished-run notification has served its purpose.
        if (!engine.hasPendingDeletion) ConversionService.clearCompletionNotification(this)
        refreshStorage()
    }

    private fun restoreSettings() {
        binding.qualitySlider.value = settings.quality.toFloat()
        showQuality(settings.quality)
        binding.switchDelete.isChecked = settings.deleteOriginals
        binding.switchOnlySmaller.isChecked = settings.onlyIfSmaller
        binding.switchSkipSmall.isChecked = settings.skipSmall
        binding.switchSkipExisting.isChecked = settings.skipAlreadyConverted
        checkMotionPolicy(settings.motionPolicy)
        range = settings.range
        showRange()
    }

    private fun selectedMotionPolicy(): MotionPhotoPolicy =
        when (binding.motionPolicyGroup.checkedRadioButtonId) {
            R.id.motionSkip -> MotionPhotoPolicy.SKIP
            R.id.motionDropVideo -> MotionPhotoPolicy.DROP_VIDEO
            else -> MotionPhotoPolicy.KEEP_VIDEO
        }

    private fun checkMotionPolicy(policy: MotionPhotoPolicy) {
        binding.motionPolicyGroup.check(
            when (policy) {
                MotionPhotoPolicy.SKIP -> R.id.motionSkip
                MotionPhotoPolicy.DROP_VIDEO -> R.id.motionDropVideo
                MotionPhotoPolicy.KEEP_VIDEO -> R.id.motionKeepVideo
            }
        )
    }

    // region permissions

    private fun readImagePermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = buildList {
            add(readImagePermission())
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            // Only gates the progress notification; the run itself proceeds either way.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filterNot { granted(it) }

        if (wanted.isEmpty()) refreshPermissionState() else permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun refreshPermissionState() {
        val hasImages = granted(readImagePermission())
        val hasLocation = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

        // Android 14 can grant access to a hand-picked subset instead. A date range cannot be
        // scanned in that mode, so say so plainly rather than reporting "0 photos found".
        val partialOnly = !hasImages &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

        val warning = when {
            partialOnly -> getString(R.string.permission_partial)
            !hasImages -> getString(R.string.permission_needed)
            !EncoderSupport.hasHevcEncoder -> getString(R.string.no_encoder)
            !hasLocation -> getString(R.string.permission_location)
            else -> null
        }
        binding.permissionWarning.isVisible(warning != null)
        binding.permissionWarning.text = warning.orEmpty()
        binding.permissionWarning.setOnClickListener {
            when {
                partialOnly || !hasImages -> openAppSettings()
                !EncoderSupport.hasHevcEncoder -> Unit
                else -> requestPermissions()
            }
        }
        updateButtons(engine.state.value)
        scanRestoredRange()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }

    // endregion

    // region date range

    private fun showRangePicker() {
        val current = range
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.range_title)
            .setSelection(
                androidx.core.util.Pair(
                    current?.let { DateRange.toUtcDayPick(it.startMs) }
                        ?: MaterialDatePicker.thisMonthInUtcMilliseconds(),
                    current?.let { DateRange.toUtcDayPick(it.endMs) }
                        ?: MaterialDatePicker.todayInUtcMilliseconds(),
                )
            )
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val from = selection.first ?: return@addOnPositiveButtonClickListener
            val to = selection.second ?: from
            range = DateRange.fromUtcDayPicks(from, to).also { settings.range = it }
            showRange()
            range?.let { engine.scan(it) }
        }
        picker.show(supportFragmentManager, "range")
    }

    private fun showRange() {
        val current = range
        binding.rangeText.text = if (current == null) {
            getString(R.string.range_none)
        } else {
            "${dateFormat.format(Date(current.startMs))}  —  ${dateFormat.format(Date(current.endMs))}"
        }
    }

    // endregion

    /**
     * Shows free space, and what this range would need.
     *
     * Originals are only deleted once a run finishes, so both copies are on disk throughout: the
     * extra needed is roughly the HEIC output, which lands near [ESTIMATED_HEIC_PERCENT] of the
     * JPG total. A run that cannot fit is worth knowing about before it fails on every photo.
     */
    private fun refreshStorage() {
        val free = engine.freeStorageBytes()
        if (free < 0) {
            binding.storageText.text = ""
            return
        }

        // Just the figure. Whether a range "fits" stopped being a useful warning once the run
        // started pausing to delete on its own — a long range no longer needs to fit all at once.
        binding.storageText.text = getString(R.string.storage_free, formatSize(free))
    }

    /**
     * The live status during a run: where it is, how close the next deletion prompt is, and what
     * it has been passing over. On a run of thousands the counters are the only way to see that
     * most of the library is being skipped rather than converted.
     */
    private fun runningStatus(progress: RunProgress, headline: Int): String = buildString {
        appendLine(
            getString(headline, progress.done + 1, progress.total, progress.currentName)
        )
        appendLine(
            getString(R.string.run_saved, progress.converted, formatSize(progress.savedBytes))
        )
        // The same still/motion split the final report shows, kept live so a long run's saving is
        // legible while it happens rather than only at the end.
        appendLine(
            getString(
                R.string.run_saved_stills,
                progress.converted - progress.convertedMotion,
                formatSize(progress.savedBytes - progress.savedMotionBytes),
            )
        )
        if (progress.convertedMotion > 0) {
            appendLine(
                getString(
                    R.string.run_saved_motion,
                    progress.convertedMotion,
                    formatSize(progress.savedMotionBytes),
                )
            )
        }
        if (progress.skippedTooSmall > 0) {
            appendLine(
                getString(
                    R.string.run_skipped_small,
                    progress.skippedTooSmall,
                    formatSize(progress.skippedTooSmallBytes),
                )
            )
        }
        if (progress.promptAt > 0) {
            appendLine(
                getString(R.string.pending_delete, progress.pendingDeletions, progress.promptAt)
            )
        }
        append(
            getString(
                R.string.run_counters,
                progress.skippedMotion,
                progress.skippedExisting,
                progress.skippedNotSmaller,
                progress.skippedNameClash,
                progress.failures,
            )
        )
    }

    private fun showQuality(value: Int) {
        binding.qualityLabel.text = getString(R.string.quality_label, value)
    }

    private fun options(current: DateRange) = RunOptions(
        startMs = current.startMs,
        endMs = current.endMs,
        quality = binding.qualitySlider.value.toInt(),
        deleteOriginals = binding.switchDelete.isChecked,
        onlyIfSmaller = binding.switchOnlySmaller.isChecked,
        skipSmall = binding.switchSkipSmall.isChecked,
        skipAlreadyConverted = binding.switchSkipExisting.isChecked,
        motionPolicy = selectedMotionPolicy(),
    )

    /**
     * Work runs in [ConversionService], not here — a few hundred photos takes minutes and the
     * Activity cannot be relied on to survive it.
     */
    private fun startRun(mode: RunMode) {
        val current = range
        if (current == null || !current.isValid) {
            binding.statusText.setText(R.string.pick_range_first)
            return
        }
        // A scan or an earlier run is still going. The service would start, be turned away by
        // the engine and stop again, leaving the tap looking like it did nothing.
        if (engine.isBusy) {
            binding.statusText.setText(R.string.already_running)
            return
        }
        // Starting now would overwrite the un-confirmed batch and strand those originals.
        if (engine.hasPendingDeletion) {
            binding.statusText.setText(R.string.finish_deletion_first)
            engine.requestNextDeletionBatch()
            return
        }
        binding.resultText.text = ""
        try {
            ConversionService.start(this, mode, options(current))
        } catch (e: Exception) {
            // Android refuses a foreground service start if the app lost the foreground in the
            // moment between the tap and the call. Say so rather than appearing to do nothing.
            Log.w(TAG, "Could not start the conversion service", e)
            binding.statusText.setText(R.string.service_start_failed)
        }
    }

    private fun confirmThenConvert() {
        val current = range
        if (current == null) {
            binding.statusText.setText(R.string.pick_range_first)
            return
        }
        val count = (engine.state.value as? UiState.Ready)?.photos?.size
        val deleting = binding.switchDelete.isChecked
        val motionPolicy = selectedMotionPolicy()

        val message = buildString {
            if (count != null) appendLine("$count JPG photos are in this range.").appendLine()
            appendLine("Converting re-encodes each photo, so a little image quality is lost — this cannot be undone.")
            appendLine()
            appendLine("Dates and EXIF (including GPS) are copied across. XMP and colour profiles cannot be, so edits and wide-gamut colour are lost.")
            appendLine()
            // Motion photos are the case worth spelling out before deletion: with "keep the video"
            // nothing is lost, but "convert to a still" destroys the video for good.
            when (motionPolicy) {
                MotionPhotoPolicy.KEEP_VIDEO -> {
                    appendLine("Motion photos keep their video: each becomes a HEIC motion photo, kept only if it comes out smaller.")
                    appendLine()
                }

                MotionPhotoPolicy.DROP_VIDEO -> {
                    appendLine(
                        if (deleting) {
                            "Motion photos will be flattened to stills — their embedded videos will be destroyed."
                        } else {
                            "Motion photos will be flattened to stills — their embedded videos will not be carried over."
                        }
                    )
                    appendLine()
                }

                MotionPhotoPolicy.SKIP -> Unit
            }
            append(
                if (deleting) "The originals will be deleted once each HEIC is written."
                else "The originals will be kept."
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(message)
            .setPositiveButton(
                if (deleting) R.string.confirm_delete_button else R.string.confirm_keep_button
            ) { _, _ -> startRun(RunMode.CONVERT) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmThenCleanUp() {
        if (range == null) {
            binding.statusText.setText(R.string.pick_range_first)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cleanup_title)
            .setMessage(R.string.cleanup_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> startRun(RunMode.CLEAN_UP) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Coming back from anywhere: if originals are still waiting and no dialog is up,
                // put the prompt back on screen rather than leaving the run half-finished.
                engine.requestNextDeletionBatch()
                launch { engine.state.collectLatest(::render) }
                launch {
                    engine.deletionRequest.collectLatest { request ->
                        if (request != null) launchDeleteRequest(request)
                    }
                }
            }
        }
    }

    private fun launchDeleteRequest(request: DeletionRequest) {
        try {
            val sender = engine.createDeleteIntentSender(request.uris)
            engine.consumeDeletionRequest()
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            engine.onDeletionFailed(e)
        }
    }

    private fun updateButtons(state: UiState) {
        val busy = state is UiState.Scanning || state is UiState.Working ||
            state is UiState.AwaitingDeletion
        val usable = granted(readImagePermission()) && !busy
        binding.dryRunButton.isEnabled = usable && EncoderSupport.hasHevcEncoder
        binding.convertButton.isEnabled = usable && EncoderSupport.hasHevcEncoder
        binding.cleanupButton.isEnabled = usable
        binding.rangeButton.isEnabled = !busy
        binding.cancelButton.isVisible(state is UiState.Working || state is UiState.AwaitingDeletion)
        binding.progressBar.isVisible(busy)
    }

    private fun render(state: UiState) {
        updateButtons(state)
        // The failed-photo list belongs to a finished run only; drop it for every other state so a
        // new run does not start under the previous run's failures.
        if (state !is UiState.Finished) clearFailures()

        when (state) {
            UiState.Idle -> {
                binding.statusText.text = ""
                binding.scanText.text = ""
            }

            UiState.Scanning -> {
                binding.progressBar.isIndeterminate = true
                binding.statusText.setText(R.string.scanning)
            }

            is UiState.Ready -> {
                refreshStorage()
                binding.statusText.text = ""
                binding.scanText.text = if (state.photos.isEmpty()) {
                    getString(R.string.scan_none)
                } else {
                    getString(R.string.scan_summary, state.photos.size, formatSize(state.totalBytes))
                }
            }

            is UiState.Working -> {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = state.progress.total
                binding.progressBar.setProgressCompat(state.progress.done, true)
                binding.statusText.text = when (state.mode) {
                    RunMode.CLEAN_UP -> getString(R.string.progress_cleanup)
                    RunMode.DRY_RUN -> runningStatus(state.progress, R.string.progress_measuring)
                    RunMode.CONVERT -> runningStatus(state.progress, R.string.progress_converting)
                }
            }

            is UiState.AwaitingDeletion -> {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = state.total
                binding.progressBar.setProgressCompat(state.done, true)
                binding.statusText.text = resources.getQuantityString(
                    if (state.lowOnSpace) {
                        R.plurals.awaiting_deletion_low_space
                    } else {
                        R.plurals.awaiting_deletion
                    },
                    state.pending,
                    state.pending,
                )
            }

            is UiState.Finished -> {
                binding.statusText.text = ""
                binding.resultText.text = describe(state.report)
                showFailures(state.report)
                // A finished run changes the picture, sometimes a lot.
                refreshStorage()
            }
        }
    }

    private fun describe(report: RunReport): String = buildString {
        val label = when (report.mode) {
            RunMode.DRY_RUN -> "Dry run"
            RunMode.CONVERT -> "Conversion"
            RunMode.CLEAN_UP -> "Clean-up"
        }
        appendLine(if (report.cancelled) "$label cancelled — partial results below." else "$label complete.")
        appendLine()

        if (report.mode == RunMode.CLEAN_UP) {
            describeCleanUp(report)
            return@buildString
        }

        if (report.converted == 0) {
            appendLine("No photos were converted.")
        } else {
            appendLine("Photos converted:  ${report.converted}")
            appendLine(
                "  Stills:  ${report.convertedStills} · saved ${formatSize(report.stillSavedBytes)}"
            )
            if (report.convertedMotion > 0) {
                appendLine(
                    "  Motion:  ${report.convertedMotion} · saved ${formatSize(report.motionSavedBytes)}"
                )
            }
            appendLine("Original JPGs:     ${formatSize(report.originalBytes)}")
            appendLine("HEIC output:       ${formatSize(report.heicBytes)}")
            appendLine("Space saved:       ${formatSize(report.savedBytes)} (${report.savedPercent}%)")
            appendLine("EXIF preserved:    ${report.exifPreserved} of ${report.converted}")
            if (report.exifSynthesised > 0) {
                appendLine("Date-only EXIF written: ${report.exifSynthesised} (source had none)")
            }
        }

        if (report.skippedExisting > 0) {
            appendLine("Skipped, already converted: ${report.skippedExisting}")
        }
        if (report.skippedNotSmaller > 0) appendLine("Skipped, HEIC was not smaller: ${report.skippedNotSmaller}")
        if (report.skippedTooSmall > 0) {
            appendLine(
                "Skipped, under 1 MB: ${report.skippedTooSmall} " +
                    "(${formatSize(report.skippedTooSmallBytes)} left as JPG)"
            )
        }
        if (report.skippedMotionPhoto > 0) appendLine("Skipped, motion photos: ${report.skippedMotionPhoto}")

        // The win worth stating plainly: a motion photo carried across whole, video and all.
        if (report.motionPhotosPreserved > 0) {
            appendLine("Motion photos kept whole (video re-attached): ${report.motionPhotosPreserved}")
        }
        // Losses the user cannot see in the file browser, called out because originals get deleted.
        if (report.convertedMotionPhotos > 0) {
            appendLine("Motion photos flattened to stills (video lost): ${report.convertedMotionPhotos}")
        }
        if (report.droppedXmp > 0) appendLine("XMP metadata lost: ${report.droppedXmp}")
        if (report.flattenedColour > 0) appendLine("Colour profile lost (flattened to sRGB): ${report.flattenedColour}")
        if (report.renamedOutput > 0) {
            appendLine("Skipped, a file of that name exists: ${report.renamedOutput}")
        }
        if (report.relocated > 0) appendLine("Saved under Pictures/ instead: ${report.relocated}")
        if (report.dateWriteFailed > 0) {
            appendLine("Date could not be set on: ${report.dateWriteFailed} (EXIF still has it)")
        }
        if (report.mirroredOrientation > 0) {
            appendLine("Mirrored orientation, check these: ${report.mirroredOrientation}")
        }

        // Per-file failures get their own interactive list below the summary (view / delete per
        // photo). Only whole-run failures — which have no single file behind them — and the
        // headline count belong in the text.
        val fileFailures = report.failures.count { it.hasFile }
        report.failures.filterNot { it.hasFile }.forEach { appendLine(it.reason) }
        if (report.failureCount > 0) {
            appendLine()
            if (fileFailures > 0) {
                appendLine("Failed to convert: ${report.failureCount} (listed below)")
            } else {
                appendLine("Failed: ${report.failureCount}")
            }
        }

        // Everything already had a HEIC beside it, which is what an interrupted or declined
        // run leaves behind. Point at the action that clears it rather than leaving a dead end.
        if (report.converted == 0 && report.skippedExisting > 0 && report.mode != RunMode.DRY_RUN) {
            appendLine()
            appendLine("These already have a HEIC beside them. Use \"Clean up leftovers\" to")
            appendLine("remove the leftover JPGs.")
        }

        appendLine()
        when {
            report.mode == RunMode.DRY_RUN ->
                appendLine("Nothing was written to your gallery and no original was deleted.")

            report.deletionOutcome == DeletionOutcome.NOT_REQUESTED && report.converted > 0 ->
                appendLine("Originals were kept.")

            report.deletionOutcome == DeletionOutcome.PENDING ->
                appendLine("Waiting for you to confirm deletion of the originals…")

            report.deletionOutcome == DeletionOutcome.COMPLETED ->
                appendLine("Deleted ${report.deletedOriginals} originals.")

            report.deletionOutcome == DeletionOutcome.DECLINED ->
                appendLine(
                    "Deletion was declined, so the originals are still there" +
                        if (report.deletedOriginals > 0) {
                            " (${report.deletedOriginals} already removed)."
                        } else {
                            ". Use \"Clean up leftovers\" to clear them later."
                        }
                )
        }
    }

    private fun StringBuilder.describeCleanUp(report: RunReport) {
        when (report.deletionOutcome) {
            DeletionOutcome.NOT_REQUESTED ->
                if (report.cancelled) {
                    appendLine("Stopped before the search finished.")
                } else {
                    appendLine("No leftover originals found in this range.")
                }

            DeletionOutcome.PENDING ->
                appendLine("Waiting for you to confirm deletion…")

            DeletionOutcome.COMPLETED -> {
                appendLine("Deleted ${report.deletedOriginals} leftover JPGs.")
                appendLine("Reclaimed: ${formatSize(report.originalBytes)}")
            }

            // Deletion is confirmed in batches, so declining a later one does not undo the
            // earlier ones. Saying "nothing was removed" would be untrue about files already gone.
            DeletionOutcome.DECLINED ->
                if (report.deletedOriginals > 0) {
                    appendLine("Deleted ${report.deletedOriginals} before you declined.")
                    appendLine("The rest are still there.")
                } else {
                    appendLine("Deletion was declined; nothing was removed.")
                }
        }
    }

    private fun clearFailures() {
        binding.failuresList.removeAllViews()
        binding.failuresHeading.isVisible(false)
    }

    /**
     * Fills the interactive failed-photo list: one row per photo that could not be converted, each
     * with its name, size and reason, and a view/delete action. Whole-run failures (no file behind
     * them) are left out — they are already stated in the summary text above.
     */
    private fun showFailures(report: RunReport) {
        val failures = report.failures.filter { it.hasFile }
        binding.failuresList.removeAllViews()
        binding.failuresHeading.isVisible(failures.isNotEmpty())
        if (failures.isEmpty()) return

        binding.failuresHeading.text = getString(R.string.failures_subheading, failures.size)
        for (failure in failures) {
            val row = ItemFailedPhotoBinding.inflate(layoutInflater, binding.failuresList, false)
            row.failedName.text = failure.name
            row.failedDetail.text =
                getString(R.string.failed_detail, formatSize(failure.sizeBytes), failure.reason)
            row.failedViewButton.setOnClickListener { viewPhoto(failure) }
            row.failedDeleteButton.setOnClickListener { deleteFailedPhoto(failure, row.root) }
            binding.failuresList.addView(row.root)
        }
    }

    private fun viewPhoto(failure: FailedPhoto) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(failure.uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No viewer for ${failure.uri}", e)
            Toast.makeText(this, R.string.view_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Deletes a single failed original through the system's delete confirmation, which is its own
     * safeguard — the file was never converted, so this is the only copy. The row is removed once
     * the delete is confirmed, in [failureDeleteLauncher].
     */
    private fun deleteFailedPhoto(failure: FailedPhoto, row: View) {
        try {
            val sender = engine.createDeleteIntentSender(listOf(failure.uri))
            pendingFailureDelete = failure to row
            failureDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            Log.e(TAG, "Could not start delete for ${failure.uri}", e)
            Toast.makeText(this, R.string.view_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private companion object {
        const val TAG = "MainActivity"
    }

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
