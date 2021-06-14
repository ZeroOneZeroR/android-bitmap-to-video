package com.appilian.vimory.Recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer


class VideoFrameMuxer(val path: String, private val outputFormat: Int, private val frameTimeUs: Long) {

    val mediaMuxer: MediaMuxer
    private var videoTrackIndex = 0
    private var frame = 0

    var isStarted: Boolean = false

    init {
        mediaMuxer = MediaMuxer(path, outputFormat)
    }

    fun start(outputMediaFormat: MediaFormat) {
        videoTrackIndex = mediaMuxer.addTrack(outputMediaFormat)
        mediaMuxer.start()
        isStarted = true
    }

    fun muxVideoFrame(encodedData: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        // adjust the ByteBuffer values to match BufferInfo (not needed?)
        encodedData.position(bufferInfo.offset)
        encodedData.limit(bufferInfo.offset + bufferInfo.size)

        //bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME

        //This code will break if the encoder supports B frames.
        //Ideally we would use set the value in the encoder,
        //don't know how to do that without using OpenGL
        bufferInfo.presentationTimeUs = frameTimeUs * frame++
        mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
    }

    fun stop() {
        mediaMuxer.stop()
    }

    fun release() {
        mediaMuxer.release()
    }
}