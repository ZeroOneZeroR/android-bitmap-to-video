package com.zeroonezero.bitmaptovideo;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class AudioEncoder {

    private final int BYTES_PER_SHORT = 2;
    private final long TIMEOUT_USEC = 0000;

    private final int DEFAULT_SAMPLE_RATE = 44100;
    private final int DEFAULT_BIT_RATE = 128000;
    private final int DEFAULT_CHANNEL_COUNT = 2;

    private AudioDecoder decoder;
    private MediaCodec encoder;
    private MediaMuxer muxer;

    private InputDecodingDoneListener inputDecodingDoneListener;

    private int sampleRate;
    private int bitRate;
    private int channelCount;

    private long fadeDurationUs;

    private int muxerTrackIndex = -1;

    public AudioEncoder(Context context, Uri audioUri, MediaMuxer muxer) throws IOException {
        decoder = new AudioDecoder(context, audioUri, null);
        this.muxer = muxer;
    }

    public void start() throws IOException{
        sampleRate = decoder.getSampleRate() > 0 ? decoder.getSampleRate(): DEFAULT_SAMPLE_RATE;
        bitRate = decoder.getBitrateRate() > 0 ? decoder.getBitrateRate() : DEFAULT_BIT_RATE;
        channelCount = decoder.getChannelCount() > 0 ? decoder.getChannelCount() : DEFAULT_CHANNEL_COUNT;

        MediaFormat outputFormat = createOutputFormat(sampleRate, bitRate, channelCount);
        encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME));
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        decoder.start();

        addTrack();
    }

    private MediaFormat createOutputFormat(int sampleRate, int bitRate, int channelCount){
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1024 * 256); // Needs to be large enough to avoid BufferOverflowException
        return format;
    }

    private void addTrack(){
        while (muxerTrackIndex < 0) encode();
    }



    private boolean encoderInputDone;
    private boolean encodingDone = false;

    private long encoderInputPresentationTimeUs;
    private long lastMuxingPresentationTimeUs = 0;
    private long lastMuxingAudioTimeUs = 0;

    long audioShortsCount = 0;

    public void encode(){
        if(isEncodingDone()) return;

        if(!encoderInputDone){

            int encoderBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);

            if(encoderBufferIndex >= 0){
                if(!decoder.isDecodingDone()){

                    ShortBuffer encoderBuffer;
                    if (Build.VERSION.SDK_INT >= 21) {
                        encoderBuffer = encoder.getInputBuffer(encoderBufferIndex).asShortBuffer();
                    }else{
                        encoderBuffer = encoder.getInputBuffers()[encoderBufferIndex].asShortBuffer();
                    }

                    AudioDecoder.DecodedBufferData data = decoder.decode();
                    ShortBuffer shortBuffer = data.byteBuffer.asShortBuffer();

                    int smplrt = decoder.getSampleRate();
                    int chnl = decoder.getChannelCount();

                    int fadeEnd = AudioConversions.usToShorts(decoder.getEndTimeUs() - decoder.getStartTimeUs(), smplrt, chnl);
                    int fadeStart = fadeEnd - AudioConversions.usToShorts(fadeDurationUs, smplrt, chnl);

                    while (shortBuffer.hasRemaining()){
                        audioShortsCount++;
                        short sample = shortBuffer.get();
                        /*if(audioShortsCount >= fadeStart && audioShortsCount <= fadeEnd){
                            float progress = 1.0f - (audioShortsCount - fadeStart) / (float)(fadeEnd - fadeStart);
                            if(progress >= 0f && progress <= 1f){
                                sample = (short) (sample * progress);
                            }
                        }*/

                        if(audioShortsCount >= fadeStart){
                            float progress = 1.0f - (audioShortsCount - fadeStart) / (float)(fadeEnd - fadeStart);
                            if(progress < 0f) progress = 0f;
                            sample = (short) (sample * progress);
                        }

                        encoderBuffer.put(sample);
                    }

                    encoder.queueInputBuffer(encoderBufferIndex,
                            0,
                            encoderBuffer.position() * BYTES_PER_SHORT,
                            encoderInputPresentationTimeUs,
                            MediaCodec.BUFFER_FLAG_KEY_FRAME);
                    encoderInputPresentationTimeUs += AudioConversions.shortsToUs(encoderBuffer.position(), sampleRate, channelCount);

                    decoder.releaseOutputBuffer(data.index);

                    if(decoder.isDecodingDone()){
                        if(inputDecodingDoneListener != null){
                            inputDecodingDoneListener.onInputDecodingDone();
                        }
                    }

                }else{

                    encoder.queueInputBuffer(encoderBufferIndex,0,0,0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    encoderInputDone = true;

                }
            }
        }

        muxEncoderOutput();
    }

    private void muxEncoderOutput(){

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);

        if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // no output available yet
        } else if (outBufferId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

        } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

            muxerTrackIndex = muxer.addTrack(encoder.getOutputFormat());

        } else if (outBufferId < 0) {

            throw new RuntimeException("Unexpected result from decoder.dequeueOutputBuffer: " + outBufferId);

        } else if (outBufferId >= 0) {

            if(bufferInfo.size > 0){
                ByteBuffer encodedBuffer;
                if (Build.VERSION.SDK_INT >= 21) {
                    encodedBuffer = encoder.getOutputBuffer(outBufferId);
                }else{
                    encodedBuffer = encoder.getOutputBuffers()[outBufferId];
                }

                /*
                 * Current muxing-presentation-time can't be less than previous one for muxer.
                 * But we may get bufferInfo.presentation = 0 for the last buffer.
                 * That is why we use our last calculated audio-time as current presentation time in this case.
                 */
                if(bufferInfo.presentationTimeUs < lastMuxingPresentationTimeUs){
                    bufferInfo.presentationTimeUs = lastMuxingAudioTimeUs;
                }

                synchronized (muxer){
                    muxer.writeSampleData(muxerTrackIndex, encodedBuffer, bufferInfo);
                    lastMuxingPresentationTimeUs = bufferInfo.presentationTimeUs;

                    //AudioConversions.bytesToUs(bufferInfo.size, sampleRate, channelCount);
                    long approxPresentationTimeDiff = (1024 * 1000000) / sampleRate; // I don't know why this is;
                    lastMuxingAudioTimeUs = lastMuxingPresentationTimeUs + approxPresentationTimeDiff;
                }


            }
            encoder.releaseOutputBuffer(outBufferId, false);

            // Are we finished here?
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                encodingDone = true;
            }
        }
    }

    public void restartInputDecoding(){
        audioShortsCount = 0;
        decoder.restart();
    }

    public long getDurationUs(){
        return decoder.getDurationUs();
    }

    public boolean isEncodingDone() {
        return encodingDone;
    }

    public void setStartTimeUs(long startTimeUs){
        decoder.setStartTimeUs(startTimeUs);
    }

    public void setEndTimeUs(long endTimeUs){
        decoder.setEndTimeUs(endTimeUs);
    }

    public void setFadeDurationUs(long fadeDurationUs) {
        this.fadeDurationUs = fadeDurationUs;
    }

    public void setInputDecodingDoneListener(InputDecodingDoneListener inputDecodingDoneListener) {
        this.inputDecodingDoneListener = inputDecodingDoneListener;
    }

    public void stop(){
        decoder.stop();
        decoder.release();

        if(encoder != null){
            encoder.stop();
            encoder.release();
            encoder = null;
        }
    }

    public interface InputDecodingDoneListener{
        void onInputDecodingDone();
    }
}
