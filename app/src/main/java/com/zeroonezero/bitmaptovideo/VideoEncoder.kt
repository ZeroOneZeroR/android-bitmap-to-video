package com.appilian.vimory.Recorder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.IOException


class VideoEncoder(val videoConfig: VideoConfig) {
    
    private var bufferInfo: MediaCodec.BufferInfo? = null
    private var videoEncoder: MediaCodec? = null
    private var surface: Surface? = null
    private var canvasRect: Rect? = null
    private var frameMuxer: VideoFrameMuxer? = null

    /*private val paint = Paint()

    init {
        paint.isAntiAlias = true
        //paint.isDither = true
        paint.isFilterBitmap = true
    }*/

    @Throws(IOException::class)
    fun start() {
        bufferInfo = MediaCodec.BufferInfo()

        val mediaFormat = videoConfig.createVideoFormat()
        videoEncoder = MediaCodec.createEncoderByType(videoConfig.videoEncoder.mimeType)
        videoEncoder?.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            canvasRect = Rect(0, 0, videoConfig.width, videoConfig.height)
        }
        
        surface = videoEncoder?.createInputSurface()
        frameMuxer = videoConfig.createVideoFrameMuxer()
        videoEncoder?.start()
        drainEncoder(false)
    }


    fun getCanvas(): Canvas {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            surface!!.lockHardwareCanvas()
        } else {
            surface!!.lockCanvas(canvasRect)
        }
    }

    fun encodeFrame(bitmap: Bitmap) {
        val canvas = getCanvas()
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        encodeFrame(canvas)
    }

    /**
     *
     * @param canvas acquired from getCanvas()
     */
    fun encodeFrame(canvas: Canvas) {
        surface!!.unlockCanvasAndPost(canvas)
        drainEncoder(false)
    }

    private fun drainEncoder(endOfStream: Boolean) {

        if (endOfStream) {
            videoEncoder?.signalEndOfInputStream()
        }

        var encoderOutputBuffers = videoEncoder?.getOutputBuffers()

        while (true) {
            val encoderStatus: Int? = videoEncoder?.dequeueOutputBuffer(bufferInfo!!, TIMEOUT_USEC.toLong())
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = videoEncoder?.getOutputBuffers()
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (frameMuxer?.isStarted!!) {
                    throw RuntimeException("VideoFormat changed twice")
                }

                muxerListener?.onMuxerPreStart(frameMuxer!!.mediaMuxer)

                // now that we have the Magic Goodies, start the muxer
                frameMuxer?.start(videoEncoder?.outputFormat!!)
            } else if (encoderStatus!! < 0) {
                // let's ignore it
            } else {
                val encodedData = encoderOutputBuffers!![encoderStatus]
                if (bufferInfo?.flags!! and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    bufferInfo?.size = 0
                }

                if (bufferInfo?.size != 0) {
                    if (!frameMuxer?.isStarted!!) {
                        throw RuntimeException("Muxer hasn't started yet.")
                    }
                    frameMuxer?.muxVideoFrame(encodedData, bufferInfo!!)
                }

                videoEncoder?.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo?.flags!! and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }
    
    

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     */
    fun stop() {
        if (videoEncoder != null) {
            //This line could be isn't only call, like flush() or something
            drainEncoder(true)
            videoEncoder!!.stop()
            videoEncoder!!.release()
            videoEncoder = null
        }
        
        surface?.release()
        surface = null
        
        frameMuxer?.release()
        frameMuxer = null
    }


    companion object {
        private val TAG = VideoEncoder::class.java.simpleName
        private const val VERBOSE = false
        private const val TIMEOUT_USEC = 10000
    }

    var muxerListener: MuxerListener? = null

    interface MuxerListener{
        fun onMuxerPreStart(muxer: MediaMuxer);
    }

}