package com.romchik.timelapse.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.romchik.timelapse.utils.StorageHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class TimelapseCameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraId: String? = null

    init {
        findBackCamera()
    }

    private fun findBackCamera() {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id
                break
            }
        }
        if (cameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
            cameraId = cameraManager.cameraIdList[0]
        }
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
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
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
        val reader = imageReader ?: run {
            onError("ImageReader not initialized")
            return
        }

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        Log.i(TAG, "CameraCaptureSession configured")
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

    fun capturePhoto(targetFile: File, onComplete: (Boolean) -> Unit) {
        ensureCameraOpen(
            onReady = {
                val session = captureSession
                val reader = imageReader
                val device = cameraDevice
                if (session == null || reader == null || device == null) {
                    onComplete(false)
                    return@ensureCameraOpen
                }

                try {
                    reader.setOnImageAvailableListener({ ir ->
                        val image = ir.acquireLatestImage()
                        if (image != null) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                // Save frame to session
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { fos ->
                                    fos.write(bytes)
                                }

                                // Update last_frame.jpg for CLI preview
                                val lastFrame = StorageHelper.lastFrameFile
                                lastFrame.parentFile?.mkdirs()
                                FileOutputStream(lastFrame).use { fos ->
                                    fos.write(bytes)
                                }

                                onComplete(true)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed saving image", e)
                                onComplete(false)
                            } finally {
                                image.close()
                            }
                        } else {
                            onComplete(false)
                        }
                    }, backgroundHandler)

                    val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.JPEG_QUALITY, 80.toByte())
                    }

                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            Log.e(TAG, "Capture failed: ${failure.reason}")
                            onComplete(false)
                        }
                    }, backgroundHandler)

                } catch (e: Exception) {
                    Log.e(TAG, "Exception during capture", e)
                    onComplete(false)
                }
            },
            onError = { err ->
                Log.e(TAG, "Camera open error: $err")
                onComplete(false)
            }
        )
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
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
    }
}
