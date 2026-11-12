package com.romchik.timelapse.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.romchik.timelapse.utils.StorageHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TimelapseCameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var meteringReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private val captureInProgressLock = Semaphore(1)
    private var cameraId: String? = null

    private var minIso: Int = 100
    private var maxIso: Int = 3200
    private var minExpNs: Long = 100_000L
    private var maxExpNs: Long = 32_000_000_000L
    private var minAeCompensation: Int = -24
    private var maxAeCompensation: Int = 12
    // -8 steps * (1/6 EV) = -1.33 EV daylight compensation to prevent blown-out windows
    private val daylightAeCompensation: Int
        get() = (-8).coerceIn(minAeCompensation, maxAeCompensation)

    @Volatile
    private var latestMeteringLuma: Double = 80.0

    @Volatile
    private var latestBrightRatio: Double = 0.0

    @Volatile
    private var smoothedTargetEv: Double = 0.0

    // Night EV stabilizer state (log domain), see stabilizeNightEv()
    private val recentNightLogEv = ArrayList<Double>()
    private var smoothedLogEv: Double = Double.NaN
    private var appliedLogEv: Double = Double.NaN
    private var evAdjusting: Boolean = false

    @Volatile
    private var smoothedFocusDistance: Float = 0.33f

    @Volatile
    private var isDaylightState: Boolean = false

    init {
        smoothedFocusDistance = StorageHelper.getSavedFocusDistance()
        findBackCamera()
    }

    private fun findBackCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id
                extractSensorRanges(chars)
                break
            }
        }
        if (cameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
            val fallbackId = cameraManager.cameraIdList[0]
            cameraId = fallbackId
            extractSensorRanges(cameraManager.getCameraCharacteristics(fallbackId))
        }
    }

    private fun extractSensorRanges(chars: CameraCharacteristics) {
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
            minIso = range.lower
            maxIso = range.upper
        }
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
            minExpNs = range.lower
            maxExpNs = range.upper
        }
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
            minAeCompensation = range.lower
            maxAeCompensation = range.upper
        }
        Log.i(TAG, "Sensor ranges: ISO [$minIso..$maxIso], Exposure [${minExpNs}ns..${maxExpNs}ns], AE Comp [$minAeCompensation..$maxAeCompensation]")
    }

    fun start() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("TimelapseCameraBg").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun stop() {
        closeCamera()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun ensureCameraOpen(onReady: () -> Unit, onError: (String) -> Unit) {
        if (cameraDevice != null && captureSession != null) {
            onReady()
            return
        }

        val targetId = cameraId ?: run {
            onError("No camera available")
            return
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                onError("Camera lock timeout")
                return
            }

            meteringReader = ImageReader.newInstance(640, 360, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ reader ->
                    val img = try {
                        reader.acquireLatestImage()
                    } catch (e: Exception) {
                        null
                    }
                    if (img != null) {
                        try {
                            val stats = computeYuvStats(img)
                            latestMeteringLuma = stats.meanLuma
                            latestBrightRatio = stats.brightRatio
                        } catch (e: Exception) {
                            // Ignore
                        } finally {
                            img.close()
                        }
                    }
                }, backgroundHandler)
            }

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

            cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession(camera, onReady, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    onError("CameraDevice error code: $error")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            onError("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        val mReader = meteringReader
        val jReader = imageReader
        if (mReader == null || jReader == null) {
            onError("ImageReaders not initialized")
            return
        }

        try {
            camera.createCaptureSession(
                listOf(mReader.surface, jReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        Log.i(TAG, "CameraCaptureSession configured (Metering YUV + Still JPEG)")
                        onReady()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("CameraCaptureSession configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            onError("createCaptureSession error: ${e.message}")
        }
    }

    private data class ExposureDecision(
        val useManual: Boolean,
        val iso: Int,
        val exposureNs: Long,
        val modeName: String
    )

    fun capturePhoto(
        targetFile: File,
        intervalSec: Int = 15,
        isPreview: Boolean = false,
        maxExposureCapSec: Double? = null,
        onComplete: (Boolean) -> Unit
    ) {
        if (!captureInProgressLock.tryAcquire()) {
            Log.w(TAG, "Capture already in progress, skipping concurrent request")
            onComplete(false)
            return
        }

        val wrappedComplete: (Boolean) -> Unit = { success ->
            closeCamera()
            captureInProgressLock.release()
            onComplete(success)
        }

        ensureCameraOpen(
            onReady = {
                val session = captureSession
                val mReader = meteringReader
                val jReader = imageReader
                val device = cameraDevice
                val handler = backgroundHandler
                if (session == null || mReader == null || jReader == null || device == null || handler == null) {
                    wrappedComplete(false)
                    return@ensureCameraOpen
                }

                runMeteringAndCapture(
                    device = device,
                    session = session,
                    mReader = mReader,
                    jReader = jReader,
                    handler = handler,
                    targetFile = targetFile,
                    intervalSec = intervalSec,
                    isPreview = isPreview,
                    maxExposureCapSec = maxExposureCapSec,
                    onComplete = wrappedComplete
                )
            },
            onError = { err ->
                Log.e(TAG, "Camera open error: $err")
                wrappedComplete(false)
            }
        )
    }

    private fun runMeteringAndCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        mReader: ImageReader,
        jReader: ImageReader,
        handler: Handler,
        targetFile: File,
        intervalSec: Int,
        isPreview: Boolean,
        maxExposureCapSec: Double?,
        onComplete: (Boolean) -> Unit
    ) {
        val meteringDone = AtomicBoolean(false)
        var measuredIso = 200
        var measuredExpNs = 33_333_333L
        var lastAeState = CameraMetadata.CONTROL_AE_STATE_INACTIVE
        var framesCount = 0
        var settledSamples = 0
        var sumEv = 0.0
        var sumLuma = 0.0
        var sumBright = 0.0
        val meteringStartMs = System.currentTimeMillis()
        val lockedFocusDist = if (smoothedFocusDistance in 0.15f..1.00f) smoothedFocusDistance else 0.33f

        val proceedToStillCapture = Runnable {
            if (!meteringDone.compareAndSet(false, true)) return@Runnable
            try {
                session.stopRepeating()
            } catch (e: Exception) {
                // Ignore
            }

            val currentLuma = if (settledSamples > 0) sumLuma / settledSamples else latestMeteringLuma
            val currentBrightRatio = if (settledSamples > 0) sumBright / settledSamples else latestBrightRatio
            val rawPreviewEv = if (settledSamples > 0) {
                sumEv / settledSamples
            } else {
                measuredIso.toDouble() * measuredExpNs.toDouble()
            }

            // Smooth low-light baseline floor without any discrete luma step cliff
            val darkWeight = ((25.0 - currentLuma) / 15.0).coerceIn(0.0, 1.0)
            val minFloorEv = 30_000_000_000.0 * darkWeight
            val effectivePreviewEv = rawPreviewEv.coerceAtLeast(minFloorEv)

            val userExpMode = StorageHelper.getExposureMode()
            val decision = if (userExpMode == "CLASSIC" || userExpMode == "BASIC" || userExpMode == "AUTO") {
                // Basic standard Android auto-exposure mode (no manual long shutter, no custom cap)
                ExposureDecision(
                    useManual = false,
                    iso = measuredIso,
                    exposureNs = measuredExpNs,
                    modeName = "CLASSIC_AUTO"
                )
            } else if (userExpMode == "TEST15" || userExpMode == "TEST15S") {
                // Forced 15-second long exposure at max ISO 3200 for extreme dark testing
                ExposureDecision(
                    useManual = true,
                    iso = 3200,
                    exposureNs = 15_000_000_000L,
                    modeName = "TEST_15S_EXPOSURE"
                )
            } else {
                calculateSmartExposure(
                    autoIso = measuredIso,
                    autoExpNs = measuredExpNs,
                    previewEv = effectivePreviewEv,
                    aeState = lastAeState,
                    liveLuma = currentLuma,
                    brightRatio = currentBrightRatio,
                    intervalSec = intervalSec,
                    isPreview = isPreview,
                    maxExposureCapSec = maxExposureCapSec
                )
            }

            Log.i(
                TAG,
                "Metering [ISO=$measuredIso, Exp=${measuredExpNs / 1_000_000}ms, Luma=${String.format("%.1f", currentLuma)}, Bright=${String.format("%.2f", currentBrightRatio)}, AE=$lastAeState, Focus=${String.format("%.2f", lockedFocusDist)}] -> " +
                    "Decision [Mode=${decision.modeName}, ISO=${decision.iso}, Exp=${decision.exposureNs / 1_000_000}ms]"
            )

            // Wait for in-flight preview frames (66-74ms each) to drain before firing still capture
            handler.postDelayed({
                fireStillCapture(
                    device = device,
                    session = session,
                    jReader = jReader,
                    handler = handler,
                    targetFile = targetFile,
                    decision = decision,
                    targetFocusDist = lockedFocusDist,
                    onComplete = onComplete
                )
            }, 150L)
        }

        // Safety timeout for metering phase (850 ms)
        handler.postDelayed(proceedToStillCapture, 850L)

        try {
            val meteringReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(mReader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, daylightAeCompensation)
                set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    if (isDaylightState) CameraMetadata.CONTROL_AWB_MODE_AUTO else CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                )
                // Permanently lock focus to calibrated room distance during preview too,
                // preventing VCM motor scanning, focus breathing, and mid-sweep blur
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, lockedFocusDist)
            }

            session.setRepeatingRequest(
                meteringReq.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { measuredIso = it }
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { measuredExpNs = it }
                        result.get(CaptureResult.CONTROL_AE_STATE)?.let { lastAeState = it }

                        framesCount++
                        if (framesCount >= 6) {
                            settledSamples++
                            sumEv += measuredIso.toDouble() * measuredExpNs.toDouble()
                            sumLuma += latestMeteringLuma
                            sumBright += latestBrightRatio
                        }

                        val elapsed = System.currentTimeMillis() - meteringStartMs
                        val converged = (lastAeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                            lastAeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)

                        // Wait for at least 10 preview frames to ensure ISP AE filter converges smoothly in low light
                        if (framesCount >= 10 && (converged || elapsed >= 650L || framesCount >= 16)) {
                            handler.removeCallbacks(proceedToStillCapture)
                            proceedToStillCapture.run()
                        }
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metering request failed, falling back to direct capture", e)
            handler.removeCallbacks(proceedToStillCapture)
            proceedToStillCapture.run()
        }
    }

    private fun resetNightEvFilter() {
        recentNightLogEv.clear()
        smoothedLogEv = Double.NaN
        appliedLogEv = Double.NaN
        evAdjusting = false
    }

    /**
     * Stabilizes the exposure value (ISO * exposure time) across frames, all in the log domain:
     * 1. Median of the last [NIGHT_EV_WINDOW] estimates rejects single-frame noise/monitor outliers.
     * 2. Slow EMA smoothing for minor fluctuations, faster EMA when a real lighting shift persists.
     * 3. Dead-band + continuous slew-rate limit so exposure NEVER jumps in a single frame.
     */
    private fun stabilizeNightEv(rawEv: Double): Double {
        val logRaw = Math.log(rawEv.coerceAtLeast(1e8))
        recentNightLogEv.add(logRaw)
        if (recentNightLogEv.size > NIGHT_EV_WINDOW) recentNightLogEv.removeAt(0)
        val median = recentNightLogEv.sorted()[recentNightLogEv.size / 2]

        if (smoothedLogEv.isNaN() || appliedLogEv.isNaN()) {
            smoothedLogEv = median
            appliedLogEv = median
            evAdjusting = false
            return Math.exp(median)
        }

        val deviation = Math.abs(median - smoothedLogEv)
        val alpha = when {
            deviation > 1.2 -> 0.35
            deviation > 0.4 -> 0.15
            else -> 0.04
        }
        smoothedLogEv += alpha * (median - smoothedLogEv)

        val gap = smoothedLogEv - appliedLogEv
        if (!evAdjusting && Math.abs(gap) > NIGHT_EV_DEADBAND_LN) evAdjusting = true
        if (evAdjusting) {
            val maxStep = if (Math.abs(gap) > 0.8) 0.16 else NIGHT_EV_MAX_STEP_LN
            appliedLogEv += gap.coerceIn(-maxStep, maxStep)
            if (Math.abs(smoothedLogEv - appliedLogEv) < 0.025) evAdjusting = false
        }
        return Math.exp(appliedLogEv)
    }

    private fun calculateSmartExposure(
        autoIso: Int,
        autoExpNs: Long,
        previewEv: Double,
        aeState: Int,
        liveLuma: Double,
        brightRatio: Double,
        intervalSec: Int,
        isPreview: Boolean,
        maxExposureCapSec: Double? = null
    ): ExposureDecision {
        // 1. Determine adaptive max exposure time based on interval and custom cap
        val defaultCapSec = if (isPreview) {
            // Default 4.5s preview limit unless user specified custom cap
            (intervalSec - 1.5).coerceIn(2.0, 4.5)
        } else {
            // Reserve ~2.0s for metering + 1080p ISP readout + JPEG save
            (intervalSec - 2.0).coerceIn(0.5, 30.0)
        }

        val maxAllowedSec = if (maxExposureCapSec != null && maxExposureCapSec > 0.1) {
            maxExposureCapSec.coerceIn(0.2, 32.0)
        } else {
            defaultCapSec
        }
        val adaptiveMaxExpNs = (maxAllowedSec * 1_000_000_000.0).toLong().coerceIn(minExpNs, maxExpNs)

        // 2. Calculate continuous target light energy (no step cliffs in brightRatio or luma)
        val effectiveLuma = (liveLuma - 6.0).coerceAtLeast(2.0)
        val baseTargetLuma = (66.0 - (brightRatio * 180.0)).coerceIn(48.0, 66.0)
        val boostT = ((brightRatio - 0.005) / 0.055).coerceIn(0.0, 1.0)
        val maxBoost = 20.0 - boostT * (20.0 - 5.0)
        val lumaBoost = if (effectiveLuma < baseTargetLuma) {
            (baseTargetLuma / effectiveLuma).coerceIn(1.0, maxBoost)
        } else {
            1.0
        }

        val rawTargetEv = previewEv * lumaBoost

        // 3. True Bright Daylight Detection with hysteresis (never triggers in indoor/night lighting)
        val enterDaylight = (autoExpNs <= 25_000_000L && autoIso <= 250 && liveLuma >= 55.0)
        val stayDaylight = (autoExpNs <= 33_000_000L && autoIso <= 400 && liveLuma >= 45.0)
        isDaylightState = if (isDaylightState) stayDaylight else enterDaylight

        if (isDaylightState) {
            if (!isPreview) stabilizeNightEv(rawTargetEv)
            return ExposureDecision(
                useManual = false,
                iso = autoIso.coerceIn(minIso, maxIso),
                exposureNs = autoExpNs.coerceIn(minExpNs, adaptiveMaxExpNs),
                modeName = "AUTO"
            )
        }

        // 4. Stabilize EV across all indoor, twilight, and night frames
        val targetEv = if (isPreview) rawTargetEv else stabilizeNightEv(rawTargetEv)

        // 5. Indoor / Fast shutter ladder:
        val fastShutterNs = 66_666_666L // 1/15 sec
        val neededIsoAtFastShutter = (targetEv / fastShutterNs.toDouble()).toInt()

        if (neededIsoAtFastShutter <= PREFERRED_ISO_CAP) {
            val iso = neededIsoAtFastShutter.coerceIn(200, PREFERRED_ISO_CAP)
            val shutterNs = (targetEv / iso.toDouble()).toLong().coerceIn(minExpNs, fastShutterNs)
            return ExposureDecision(
                useManual = true,
                iso = iso,
                exposureNs = shutterNs,
                modeName = "INDOOR_FAST"
            )
        }

        // 6. Twilight / Moderate Night:
        val neededExpAtIso800 = (targetEv / PREFERRED_ISO_CAP.toDouble()).toLong()

        if (neededExpAtIso800 <= adaptiveMaxExpNs) {
            return ExposureDecision(
                useManual = true,
                iso = PREFERRED_ISO_CAP,
                exposureNs = neededExpAtIso800.coerceIn(fastShutterNs, adaptiveMaxExpNs),
                modeName = "NIGHT_LONG_EXP"
            )
        }

        // 7. Deep Night / Pitch Black:
        val neededIso = (targetEv / adaptiveMaxExpNs.toDouble()).toInt().coerceIn(PREFERRED_ISO_CAP, maxIso)

        return ExposureDecision(
            useManual = true,
            iso = neededIso,
            exposureNs = adaptiveMaxExpNs,
            modeName = "NIGHT_MAX_BOOST"
        )
    }

    private fun fireStillCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        jReader: ImageReader,
        handler: Handler,
        targetFile: File,
        decision: ExposureDecision,
        targetFocusDist: Float? = null,
        onComplete: (Boolean) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        var actualIso = decision.iso
        var actualExpNs = decision.exposureNs

        val finishOnce: (Boolean) -> Unit = { success ->
            if (completed.compareAndSet(false, true)) {
                onComplete(success)
            }
        }

        try {
            jReader.setOnImageAvailableListener({ ir ->
                val image = try {
                    ir.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image != null) {
                    val bytes = try {
                        val buffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed reading image buffer", e)
                        null
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {}
                    }

                    if (bytes != null) {
                        try {
                            // Save frame to targetFile atomically
                            targetFile.parentFile?.mkdirs()
                            val tmpTarget = File(targetFile.parentFile, "${targetFile.name}.tmp")
                            FileOutputStream(tmpTarget).use { fos ->
                                fos.write(bytes)
                                fos.fd.sync()
                            }
                            tmpTarget.renameTo(targetFile)

                            // Update last_frame.jpg for CLI preview if targetFile wasn't already lastFrameFile
                            val lastFrame = StorageHelper.lastFrameFile
                            if (targetFile.absolutePath != lastFrame.absolutePath) {
                                lastFrame.parentFile?.mkdirs()
                                val tmpLast = File(lastFrame.parentFile, "${lastFrame.name}.tmp")
                                FileOutputStream(tmpLast).use { fos ->
                                    fos.write(bytes)
                                    fos.fd.sync()
                                }
                                tmpLast.renameTo(lastFrame)
                            }

                            val jpegLuma = computeJpegLuma(bytes)
                            StorageHelper.updateLastCaptureMeta(
                                StorageHelper.CaptureMetadata(
                                    iso = actualIso,
                                    exposureNs = actualExpNs,
                                    luma = jpegLuma,
                                    mode = decision.modeName
                                )
                            )
                            Log.i(
                                TAG,
                                "Saved frame: mode=${decision.modeName}, ISO=$actualIso, " +
                                    "Exp=${actualExpNs / 1_000_000}ms, JpegLuma=$jpegLuma"
                            )

                            finishOnce(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed saving image", e)
                            finishOnce(false)
                        }
                    } else {
                        finishOnce(false)
                    }
                } else {
                    finishOnce(false)
                }
            }, handler)

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jReader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())

                val focusToUse = if (targetFocusDist != null && targetFocusDist >= 0f) targetFocusDist else 0.33f
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focusToUse)

                if (decision.useManual) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, decision.iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, decision.exposureNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, decision.exposureNs)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
                    }

                    // Always use fixed DAYLIGHT white balance on manual captures so color temperature
                    // and ISP channel gains never flip-flop across luma or ISO thresholds.
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                    set(CaptureRequest.CONTROL_AWB_LOCK, false)

                    // Lock a deterministic sRGB contrast curve (gamma 2.2) instead of TONEMAP_MODE_HIGH_QUALITY
                    // so MediaTek's dynamic ADRC tone-mapper does not oscillate shadow boost by ±5 luma
                    // based on unconverged preview AE state.
                    val curvePoints = FloatArray(64)
                    for (i in 0 until 32) {
                        val x = i.toFloat() / 31.0f
                        val y = Math.pow(x.toDouble(), 1.0 / 2.2).toFloat()
                        curvePoints[i * 2] = x
                        curvePoints[i * 2 + 1] = y
                    }
                    set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                    set(
                        CaptureRequest.TONEMAP_CURVE,
                        android.hardware.camera2.params.TonemapCurve(curvePoints, curvePoints, curvePoints)
                    )
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, daylightAeCompensation)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AWB_LOCK, false)
                }
            }

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { actualIso = it }
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { actualExpNs = it }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e(TAG, "Capture failed: ${failure.reason}")
                    finishOnce(false)
                }
            }, handler)

        } catch (e: Exception) {
            Log.e(TAG, "Exception during still capture", e)
            finishOnce(false)
        }
    }

    private data class YuvMeteringStats(
        val meanLuma: Double,
        val brightRatio: Double
    )

    private fun computeYuvStats(image: Image): YuvMeteringStats {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        var sum = 0L
        var brightCount = 0
        var count = 0
        var y = 0
        while (y < height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < width) {
                val idx = rowOffset + x * pixelStride
                if (idx >= 0 && idx < buffer.limit()) {
                    val luma = buffer.get(idx).toInt() and 0xFF
                    sum += luma
                    if (luma >= 210) brightCount++
                    count++
                }
                x += 8
            }
            y += 8
        }
        val mean = if (count > 0) sum.toDouble() / count.toDouble() else 0.0
        val ratio = if (count > 0) brightCount.toDouble() / count.toDouble() else 0.0
        return YuvMeteringStats(mean, ratio)
    }

    private fun computeJpegLuma(jpegBytes: ByteArray): Int {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 16
            }
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return 0
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            if (pixels.isEmpty()) return 0
            var sum = 0L
            for (px in pixels) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                sum += (299 * r + 587 * g + 114 * b) / 1000
            }
            (sum / pixels.size).toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            meteringReader?.close()
            meteringReader = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    companion object {
        private const val TAG = "TimelapseCameraEngine"
        private const val PREFERRED_ISO_CAP = 800

        // Night EV stabilizer tuning (natural-log units: 0.69 = x2 brightness, 0.10 = ~10%)
        private const val NIGHT_EV_WINDOW = 9
        private const val NIGHT_EV_RESET_LN = 1.8        // ~6x change = real light switch, adopt at once
        private const val NIGHT_EV_DEADBAND_LN = 0.12    // ignore drifts under ~12%
        private const val NIGHT_EV_MAX_STEP_LN = 0.05    // glide at most ~5% per frame
    }
}
