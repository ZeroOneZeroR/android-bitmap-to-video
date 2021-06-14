package com.appilian.vimory.Recorder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMuxer
import android.net.Uri
import com.zeroonezero.bitmaptovideo.AudioEncoder

class Recorder(val context: Context, val path: String,
               val width: Int, val height: Int,
               val frameRate: Float, val videoDuration: Float): VideoEncoder.MuxerListener, AudioEncoder.InputDecodingDoneListener{

    var musicUri: Uri? = null
    var musicEndPosition = 0f
    var musicFadeDuration = 0f

    fun setMusic(musicUri: Uri, musicEndPosition: Float, musicFadeDuration: Float){
        this.musicUri = musicUri
        this.musicEndPosition = musicEndPosition
        this.musicFadeDuration = musicFadeDuration
    }



    val videoEncoder: VideoEncoder
    var audioEncoder: AudioEncoder? = null

    val audioLoopTimes = mutableListOf<Float>()
    var audioLoopCount = 0

    init {
        val videoConfig = VideoConfig(path, width, height);
        videoConfig.frameRate = frameRate
        videoEncoder = VideoEncoder(videoConfig)
        videoEncoder.muxerListener = this
    }

    override fun onMuxerPreStart(muxer: MediaMuxer) {
        if(musicUri != null){

            val numberOfExactAudio = (videoDuration / musicEndPosition).toInt()
            val restPortion = videoDuration - numberOfExactAudio * musicEndPosition
            for (i in 1..numberOfExactAudio) {
                audioLoopTimes.add(musicEndPosition)
            }
            if (restPortion > 0) audioLoopTimes.add(restPortion)

            audioEncoder = AudioEncoder(context, musicUri, muxer)
            audioEncoder?.setInputDecodingDoneListener(this)
            setParamsToAudioEncoder()
            audioEncoder?.start()
        }
    }

    fun start(){
        videoEncoder.start()
    }

    fun record(bitmap: Bitmap){
        videoEncoder.encodeFrame(bitmap)

        if(audioEncoder != null){
            for (i in 0..2) {
                if (audioEncoder!!.isEncodingDone) break
                audioEncoder!!.encode()
            }
        }
    }

    fun recordRestOfAudioFrames(){
        if(audioEncoder == null) return
        while (!audioEncoder!!.isEncodingDone){
            audioEncoder!!.encode()
        }
    }

    private fun setParamsToAudioEncoder(){
        if(audioEncoder != null){
            var fadeDuration = 0.0f
            if (audioLoopCount == audioLoopTimes.size - 1) { // last audio loop
                fadeDuration = if (videoDuration > 5f) 2.0f else 1.0f
            } else if (musicFadeDuration > 0.0f) {
                fadeDuration = musicFadeDuration
            }

            audioEncoder!!.setStartTimeUs(0)
            audioEncoder!!.setEndTimeUs((audioLoopTimes.get(audioLoopCount) * 1000000).toLong())
            audioEncoder!!.setFadeDurationUs((fadeDuration * 1000000).toLong())
        }
    }

    override fun onInputDecodingDone() {
        audioLoopCount++
        if(audioLoopCount < audioLoopTimes.size){
            setParamsToAudioEncoder()
            audioEncoder!!.restartInputDecoding()
        }
    }


    fun stop(){
        videoEncoder.stop()

        if(audioEncoder != null){
            audioEncoder?.stop()
            audioEncoder = null
        }
    }
}