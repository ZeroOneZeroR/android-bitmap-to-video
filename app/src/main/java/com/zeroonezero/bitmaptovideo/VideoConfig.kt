package com.appilian.vimory.Recorder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.IOException

// We can get some information on video settings from here
// https://github.com/PhilLab/Android-MediaCodec-Examples/blob/master/EncodeDecodeTest.java

class VideoConfig(val path: String, val width: Int, val height: Int, val videoEncoder: VideoEncoder = VideoEncoder.AVC_H264) {

    enum class VideoEncoder(val mimeType: String){
        H263(MediaFormat.MIMETYPE_VIDEO_H263),
        AVC_H264(MediaFormat.MIMETYPE_VIDEO_AVC),
        HEVC_H265(MediaFormat.MIMETYPE_VIDEO_HEVC);
    }

    //Default bit rate is 2 Mbps
    var bitRate = 3000000 // Bits per second //2000000

    // Default FPS
    var frameRate = 30f // Frames per second

    var iFrameInterval = 1f

    var maxBFrames = 15

    fun getFrameTimeUs(): Long = ((1.0f / frameRate) * 1000000).toLong()

    fun createVideoFormat(): MediaFormat {

        var hardwareCodecFound = false;

        var profile = -1;
        var level = -1;

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfoList = codecList.codecInfos

        outerloop@ for(i in codecInfoList.indices){

            val codecInfo = codecInfoList.get(i)

            if(codecInfo.isEncoder){
                val types = codecInfo.supportedTypes

                for (j in types.indices){
                    if (types[j] == videoEncoder.mimeType){

                        val capabilities = codecInfo.getCapabilitiesForType(types[j])
                        val videoCapabilities = capabilities.videoCapabilities

                        val profileLevels = capabilities.profileLevels


                        profileLevels.forEach {
                            if(it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                                profile = it.profile
                        }

                        if(profile == -1){
                            profileLevels.forEach {
                                if(it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                                    profile = it.profile
                            }
                        }

                        if(profile == -1){
                            profileLevels.forEach {
                                if(it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                                    profile = it.profile
                            }
                        }

                        if(profile != -1){
                            profileLevels.forEach {
                                if(it.profile == profile)
                                    level = it.level
                            }
                        }

                        val bitrateRange = videoCapabilities.bitrateRange
                        bitRate = Math.max(bitrateRange.lower, Math.min(bitrateRange.upper, bitRate))
                        break@outerloop
                    }
                }
            }
        }


        val format = MediaFormat.createVideoFormat(videoEncoder.mimeType, width, height)

        if(profile != -1){
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            if(level != -1){
                format.setInteger(MediaFormat.KEY_LEVEL, level)
            }
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

        format.setString(MediaFormat.KEY_FRAME_RATE, null)
        // On LOLLIPOP, media format must not contain a KEY_FRAME_RATE.
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.LOLLIPOP) {
            format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate)
            //format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.toInt())
        }

        format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval.toInt())
        //format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        //format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, maxBFrames)
        return format
    }

     private fun isCodecHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            codecInfo.isHardwareAccelerated
        } else {
            !codecInfo.name.startsWith("OMX.google.")
        }
    }

    @Throws(IOException::class)
    fun createVideoFrameMuxer(): VideoFrameMuxer {
        return VideoFrameMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, getFrameTimeUs())
    }

}