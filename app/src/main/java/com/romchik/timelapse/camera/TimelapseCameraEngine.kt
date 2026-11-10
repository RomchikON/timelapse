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

    @Volatile
    private var latestMeteringLuma: Double = 80.0

    @Volatile
    private var smoothedTargetEv: Double = 0.0

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
        Log.i(TAG, "Sensor ranges: ISO [$minIso..$maxIso], Exposure [${minExpNs}ns..${maxExpNs}ns]")
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
                            latestMeteringLuma = computeYuvLuma(img)
                        } catch (e: Exception) {
                            // Ignore
                        } finally {
                            img.close()
                        }
                    }
                }, backgroundHandler)
            }

            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)

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

            // In total darkness, if AE is still searching (0) and hasn't ramped up, default to sensor max sensitivity
            if (lastAeState == CameraMetadata.CONTROL_AE_STATE_SEARCHING || lastAeState == CameraMetadata.CONTROL_AE_STATE_INACTIVE) {
                if (measuredIso <= 400 && latestMeteringLuma < 15.0) {
                    measuredIso = 3200
                    measuredExpNs = 74_000_000L
                }
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
                calculateSmartExposure(
                    autoIso = measuredIso,
                    autoExpNs = measuredExpNs,
                    aeState = lastAeState,
                    liveLuma = currentLuma,
                    intervalSec = intervalSec,
                    isPreview = isPreview,
                    maxExposureCapSec = maxExposureCapSec
                )
            }

            Log.i(
                TAG,
                "Metering [ISO=$measuredIso, Exp=${measuredExpNs / 1_000_000}ms, Luma=${String.format("%.1f", latestMeteringLuma)}, AE=$lastAeState] -> " +
                    "Decision [Mode=${decision.modeName}, ISO=${decision.iso}, Exp=${decision.exposureNs / 1_000_000}ms]"
            )

            // Wait briefly for repeating pipeline to drain before firing still capture
            handler.postDelayed({
                fireStillCapture(
                    device = device,
                    session = session,
                    jReader = jReader,
                    handler = handler,
                    targetFile = targetFile,
                    decision = decision,
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

    private fun calculateSmartExposure(
        autoIso: Int,
        autoExpNs: Long,
        aeState: Int,
        liveLuma: Double,
        intervalSec: Int,
        isPreview: Boolean,
        maxExposureCapSec: Double? = null
    ): ExposureDecision {
        // 1. Determine adaptive max exposure time based on interval and custom cap
        val defaultCapSec = if (isPreview) {
            // Default 4.5s preview limit unless user specified custom cap
            (intervalSec - 1.5).coerceIn(2.0, 4.5)
        } else {
            // Reserve ~1.8s for metering + ISP readout + JPEG save
            (intervalSec - 1.8).coerceIn(0.5, 30.0)
        }

        val maxAllowedSec = if (maxExposureCapSec != null && maxExposureCapSec > 0.1) {
            maxExposureCapSec.coerceIn(0.2, 32.0)
        } else {
            defaultCapSec
        }
        val adaptiveMaxExpNs = (maxAllowedSec * 1_000_000_000.0).toLong().coerceIn(minExpNs, maxExpNs)

        // 2. Check if scene has enough light for normal AUTO mode (ISO <= 400 and bright luma)
        val isBrightScene = (autoIso <= PREFERRED_ISO_CAP &&
            liveLuma >= 55.0 &&
            aeState != CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED)

        if (isBrightScene) {
            return ExposureDecision(
                useManual = false,
                iso = autoIso.coerceIn(minIso, maxIso),
                exposureNs = autoExpNs.coerceIn(minExpNs, adaptiveMaxExpNs),
                modeName = "AUTO"
            )
        }

        // 3. Low-light / Night scene: calculate total light energy required
        // Account for YUV black level pedestal (~6.0)
        val effectiveLuma = (liveLuma - 6.0).coerceAtLeast(2.5)
        val targetEffectiveLuma = 72.0 // Target comfortable indoor/night brightness (~78 raw luma)
        val lumaBoost = if (effectiveLuma < targetEffectiveLuma) {
            (targetEffectiveLuma / effectiveLuma).coerceIn(1.0, 20.0)
        } else {
            1.0
        }

        val measuredEv = autoIso.toDouble() * autoExpNs.toDouble()
        val rawTargetEv = measuredEv * lumaBoost

        // Anti-flicker Exponential Moving Average smoothing (EMA):
        // Prevents small lighting jitters from bouncing exposure back and forth between adjacent frames.
        // If light changed drastically (e.g. room light switched on/off > 3.0x), adapt immediately.
        val targetEv = if (smoothedTargetEv <= 0.0 || Math.abs(Math.log(rawTargetEv / smoothedTargetEv)) > 1.1) {
            smoothedTargetEv = rawTargetEv
            rawTargetEv
        } else {
            val smoothed = smoothedTargetEv * 0.85 + rawTargetEv * 0.15
            smoothedTargetEv = smoothed
            smoothed
        }

        // 4. In night/dim scene, prioritize keeping shutter fast (under ~1.8s) by scaling ISO up to 800
        val targetExpNs = 1_800_000_000L.coerceAtMost(adaptiveMaxExpNs)
        val isoForTargetExp = (targetEv / targetExpNs.toDouble()).toInt().coerceIn(200, PREFERRED_ISO_CAP)
        val shutterAtIso = (targetEv / isoForTargetExp.toDouble()).toLong()

        if (shutterAtIso <= adaptiveMaxExpNs) {
            return ExposureDecision(
                useManual = true,
                iso = isoForTargetExp,
                exposureNs = shutterAtIso.coerceIn(minExpNs, adaptiveMaxExpNs),
                modeName = "NIGHT_LONG_EXP"
            )
        }

        // Try at max ISO 800 specifically
        val expAt800 = (targetEv / PREFERRED_ISO_CAP.toDouble()).toLong()
        if (expAt800 <= adaptiveMaxExpNs) {
            return ExposureDecision(
                useManual = true,
                iso = PREFERRED_ISO_CAP,
                exposureNs = expAt800.coerceIn(minExpNs, adaptiveMaxExpNs),
                modeName = "NIGHT_LONG_EXP"
            )
        }

        // 5. Extreme darkness: shutter is already maxed out at adaptiveMaxExpNs,
        // so allow ISO to scale up from 800 up to maxIso (3200)
        val finalExpNs = adaptiveMaxExpNs
        val neededIso = (targetEv / finalExpNs.toDouble()).toInt().coerceIn(PREFERRED_ISO_CAP, maxIso)

        return ExposureDecision(
            useManual = true,
            iso = neededIso,
            exposureNs = finalExpNs,
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
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

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
                    } finally {
                        image.close()
                    }
                } else {
                    finishOnce(false)
                }
            }, handler)

            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jReader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.JPEG_QUALITY, 85.toByte())

                if (decision.useManual) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, decision.iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, decision.exposureNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, decision.exposureNs)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
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

    private fun computeYuvLuma(image: Image): Double {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        var sum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            val rowOffset = y * rowStride
            var x = 0
            while (x < width) {
                val idx = rowOffset + x * pixelStride
                if (idx >= 0 && idx < buffer.limit()) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count++
                }
                x += 8
            }
            y += 8
        }
        return if (count > 0) sum.toDouble() / count.toDouble() else 0.0
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
    }
}
