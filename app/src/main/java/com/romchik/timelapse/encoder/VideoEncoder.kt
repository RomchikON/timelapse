package com.romchik.timelapse.encoder

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.romchik.timelapse.utils.StorageHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoEncoder {
    private const val TAG = "VideoEncoder"
    private const val MIME_TYPE = "video/avc"
    private const val TIMEOUT_USEC = 10000L

    fun encodeSession(
        context: Context,
        sessionDir: File,
        outputVideoFile: File,
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        bitRate: Int = 8_000_000,
        deleteJpegsAfterSuccess: Boolean = true,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        val frameFiles = sessionDir.listFiles { file ->
            file.isFile && file.name.startsWith("frame_") && file.name.endsWith(".jpg")
        }?.sortedBy { it.name } ?: emptyList()

        if (frameFiles.isEmpty()) {
            Log.w(TAG, "No frames found in ${sessionDir.absolutePath}")
            return false
        }

        val totalFrames = frameFiles.size
        Log.i(TAG, "Starting encoding of $totalFrames frames to ${outputVideoFile.absolutePath}")

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglHelper: EglHelper? = null

        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val inputSurface = encoder.createInputSurface()
            eglHelper = EglHelper(inputSurface, width, height)
            encoder.start()

            muxer = MediaMuxer(outputVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val frameDurationNs = 1_000_000_000L / frameRate
            var ptsNs = 0L

            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inMutable = true
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setShadowLayer(3f, 1f, 1f, Color.argb(180, 0, 0, 0))
            }

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(140, 0, 0, 0)
                style = Paint.Style.FILL
            }

            for (i in frameFiles.indices) {
                val file = frameFiles[i]
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                if (bitmap != null) {
                    try {
                        val timeText = dateFormat.format(Date(file.lastModified()))
                        val textWidth = textPaint.measureText(timeText)
                        val fontMetrics = textPaint.fontMetrics
                        val textHeight = fontMetrics.descent - fontMetrics.ascent

                        val padX = 12f
                        val padY = 6f
                        val margin = 20f
                        val bW = bitmap.width.toFloat()
                        val bH = bitmap.height.toFloat()

                        val bgRect = RectF(
                            bW - margin - textWidth - padX * 2,
                            bH - margin - textHeight - padY * 2,
                            bW - margin,
                            bH - margin
                        )
                        val canvas = Canvas(bitmap)
                        canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
                        val textX = bgRect.left + padX
                        val textY = bgRect.bottom - padY - fontMetrics.descent
                        canvas.drawText(timeText, textX, textY, textPaint)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to draw timestamp overlay on frame ${file.name}", e)
                    }

                    eglHelper.drawBitmap(bitmap, ptsNs)
                    bitmap.recycle()
                }

                ptsNs += frameDurationNs
                drainEncoder(encoder, muxer, bufferInfo, false, { idx ->
                    trackIndex = idx
                    muxerStarted = true
                }, muxerStarted, trackIndex)

                onProgress?.invoke(i + 1, totalFrames)
            }

            // Signal End of Stream
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, bufferInfo, true, { idx ->
                trackIndex = idx
                muxerStarted = true
            }, muxerStarted, trackIndex)

            Log.i(TAG, "Encoding finished successfully!")

            // Register video in Android Gallery
            StorageHelper.scanMediaFile(context, outputVideoFile)

            // Delete frames if requested
            if (deleteJpegsAfterSuccess) {
                Log.i(TAG, "Cleaning up JPEG frames...")
                for (file in frameFiles) {
                    file.delete()
                }
                sessionDir.delete()
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed", e)
            return false
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                eglHelper?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onTrackAdded: (Int) -> Unit,
        isMuxerStarted: Boolean,
        currentTrackIndex: Int
    ) {
        var muxerStarted = isMuxerStarted
        var trackIndex = currentTrackIndex

        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw RuntimeException("Format changed twice")
                }
                val newFormat = encoder.outputFormat
                trackIndex = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
                onTrackAdded(trackIndex)
            } else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)
                    ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }

                encoder.releaseOutputBuffer(encoderStatus, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }
}
