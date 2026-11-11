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
    private var smoothedFocusDistance: Float = -1.0f

    init {
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
        val meteringStartMs = System.currentTimeMillis()

        val proceedToStillCapture = Runnable {
            if (!meteringDone.compareAndSet(false, true)) return@Runnable
            try {
                session.stopRepeating()
            } catch (e: Exception) {
                // Ignore
            }

            // In deep darkness where AE preview is still searching and didn't finish ramping up,
            // ensure a baseline floor without a discrete 8x jump that could trip the stabilizer
            val rawPreviewEv = measuredIso.toDouble() * measuredExpNs.toDouble()
            val effectivePreviewEv = if (latestMeteringLuma < 15.0 && rawPreviewEv < 30_000_000_000.0) {
                30_000_000_000.0 // Smooth baseline (~ISO 500 @ 60ms)
            } else {
                rawPreviewEv
            }

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
                val currentLuma = latestMeteringLuma
                val currentBrightRatio = latestBrightRatio
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
                "Metering [ISO=$measuredIso, Exp=${measuredExpNs / 1_000_000}ms, Luma=${String.format("%.1f", latestMeteringLuma)}, Bright=${String.format("%.2f", latestBrightRatio)}, AE=$lastAeState] -> " +
                    "Decision [Mode=${decision.modeName}, ISO=${decision.iso}, Exp=${decision.exposureNs / 1_000_000}ms]"
            )

            // In lit room/daylight, let hardware AF run continuously for maximum sharpness.
            // Only in deep darkness (luma < 18.0), lock the lens to the last known sharp focus distance.
            val focusDist = if (latestMeteringLuma < 18.0 && smoothedFocusDistance >= 0f) {
                smoothedFocusDistance
            } else {
                null
            }

            // Wait briefly for repeating pipeline to drain before firing still capture
            handler.postDelayed({
                fireStillCapture(
                    device = device,
                    session = session,
                    jReader = jReader,
                    handler = handler,
                    targetFile = targetFile,
                    decision = decision,
                    targetFocusDist = focusDist,
                    onComplete = onComplete
                )
            }, 80L)
        }

        // Safety timeout for metering phase (750 ms)
        handler.postDelayed(proceedToStillCapture, 750L)

        try {
            val meteringReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(mReader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, daylightAeCompensation)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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

                        // Track natural focus distance in lit scenes so we have an accurate value for night freeze
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { dist ->
                            if (dist >= 0f && latestMeteringLuma >= 18.0) {
                                smoothedFocusDistance = if (smoothedFocusDistance < 0f) {
                                    dist
                                } else {
                                    smoothedFocusDistance * 0.80f + dist * 0.20f
                                }
                            }
                        }
                        framesCount++

                        val elapsed = System.currentTimeMillis() - meteringStartMs
                        val converged = (lastAeState == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                            lastAeState == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)

                        // Wait for at least 10 preview frames to ensure ISP AE filter converges smoothly in low light
                        if (framesCount >= 10 && (converged || elapsed >= 550L || framesCount >= 16)) {
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

    private fun seedNightEvFilter(dayEv: Double) {
        val logEv = Math.log(dayEv.coerceAtLeast(1e8))
        recentNightLogEv.clear()
        recentNightLogEv.add(logEv)
        smoothedLogEv = logEv
        appliedLogEv = logEv
        evAdjusting = false
    }

    /**
     * Stabilizes the night exposure value (ISO * exposure time) across frames, all in the log domain:
     * 1. Median of the last [NIGHT_EV_WINDOW] estimates rejects single-frame noise outliers.
     * 2. A real, persistent change larger than [NIGHT_EV_RESET_LN] (e.g. room light on/off) is adopted at once.
     * 3. Otherwise slow EMA smoothing (faster for medium deviations such as a lamp or dusk).
     * 4. Dead-band: the applied EV only moves when the smoothed value drifts further than
     *    [NIGHT_EV_DEADBAND_LN]; it then glides at most [NIGHT_EV_MAX_STEP_LN] per frame until it catches up,
     *    so there are no visible brightness steps between adjacent frames.
     */
    private fun stabilizeNightEv(rawEv: Double): Double {
        recentNightLogEv.add(Math.log(rawEv))
        if (recentNightLogEv.size > NIGHT_EV_WINDOW) recentNightLogEv.removeAt(0)
        val median = recentNightLogEv.sorted()[recentNightLogEv.size / 2]

        if (smoothedLogEv.isNaN() || Math.abs(median - smoothedLogEv) > NIGHT_EV_RESET_LN) {
            smoothedLogEv = median
            appliedLogEv = median
            evAdjusting = false
            return Math.exp(median)
        }

        val deviation = Math.abs(median - smoothedLogEv)
        val alpha = if (deviation < 0.5) 0.04 else 0.25
        smoothedLogEv += alpha * (median - smoothedLogEv)

        val gap = smoothedLogEv - appliedLogEv
        if (!evAdjusting && Math.abs(gap) > NIGHT_EV_DEADBAND_LN) evAdjusting = true
        if (evAdjusting) {
            appliedLogEv += gap.coerceIn(-NIGHT_EV_MAX_STEP_LN, NIGHT_EV_MAX_STEP_LN)
            if (Math.abs(smoothedLogEv - appliedLogEv) < 0.03) evAdjusting = false
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

        // 2. Daytime / Bright Scene Detection:
        // When preview metering converges to a fast shutter (<= 35ms / ~1/28s) OR low ISO with reasonable EV,
        // the scene has ample daylight. Hardware AE with daylightAeCompensation (-1.33 EV) perfectly
        // protects window highlights from blowing out, eliminates motion blur, and tracks daylight smoothly.
        val isDaylight = (autoExpNs <= 35_000_000L && autoIso <= 800) ||
            (previewEv <= 25_000_000_000.0 && autoIso <= 500)

        if (isDaylight) {
            // Seed the night filter with current daytime EV so sunset starts smoothly without jumps
            if (!isPreview) seedNightEvFilter(previewEv)
            return ExposureDecision(
                useManual = false,
                iso = autoIso.coerceIn(minIso, maxIso),
                exposureNs = autoExpNs.coerceIn(minExpNs, adaptiveMaxExpNs),
                modeName = "AUTO"
            )
        }

        // 3. Evening / Night scene: calculate total light energy required
        // Account for YUV black level pedestal (~6.0)
        val effectiveLuma = (liveLuma - 6.0).coerceAtLeast(2.0)

        // Highlight protection: if bright lights (lamp, monitor, window) occupy > 2% of frame,
        // dynamically lower target luma to prevent blowing them out.
        val baseTargetLuma = if (brightRatio > 0.02) {
            (66.0 - (brightRatio * 180.0)).coerceIn(48.0, 66.0)
        } else {
            66.0
        }
        val maxBoost = if (brightRatio > 0.03) 4.5 else 20.0
        val lumaBoost = if (effectiveLuma < baseTargetLuma) {
            (baseTargetLuma / effectiveLuma).coerceIn(1.0, maxBoost)
        } else {
            1.0
        }

        val rawTargetEv = previewEv * lumaBoost

        // Night anti-flicker: the dark metering preview is noisy, so raw EV estimate jitters.
        // Median filter + slow smoothing + dead-band + slew-rate limit keep applied exposure steady.
        val targetEv = if (isPreview) rawTargetEv else stabilizeNightEv(rawTargetEv)

        // 4. Indoor / Fast shutter ladder:
        // Base shutter threshold for indoor / evening: 1/15s (66.7ms).
        // If the scene can be properly exposed at <= 1/15s with ISO <= 800, keep shutter fast to avoid blur!
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

        // 5. Twilight / Moderate Night:
        // Keep ISO at 800 (cleanest low-light ISO), and smoothly extend shutter speed
        // up to adaptiveMaxExpNs (e.g. 13s for 15s interval)
        val neededExpAtIso800 = (targetEv / PREFERRED_ISO_CAP.toDouble()).toLong()

        if (neededExpAtIso800 <= adaptiveMaxExpNs) {
            return ExposureDecision(
                useManual = true,
                iso = PREFERRED_ISO_CAP,
                exposureNs = neededExpAtIso800.coerceIn(fastShutterNs, adaptiveMaxExpNs),
                modeName = "NIGHT_LONG_EXP"
            )
        }

        // 6. Deep Night / Pitch Black:
        // Shutter is maxed out at adaptiveMaxExpNs, scale ISO up from 800 to maxIso (3200)
        // to pull out all available light from extreme darkness.
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

                if (targetFocusDist != null && targetFocusDist >= 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, targetFocusDist)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }

                if (decision.useManual) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, decision.iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, decision.exposureNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, decision.exposureNs)

                    // Never freeze an unconverged dark preview AWB.
                    // In deep darkness (ISO >= 1600 or dark luma), using DAYLIGHT preset balances
                    // the red/blue channel gains against dark-current thermal leakage on Samsung GW1,
                    // eliminating the purple/magenta tint. Otherwise let AWB run unlocked.
                    if (decision.iso >= 1600 || latestMeteringLuma < 18.0) {
                        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                    } else {
                        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                    }
                    set(CaptureRequest.CONTROL_AWB_LOCK, false)
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
