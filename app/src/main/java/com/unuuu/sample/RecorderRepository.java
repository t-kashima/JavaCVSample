package com.unuuu.sample;

import org.bytedeco.javacv.FFmpegFrameRecorder;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;

public class RecorderRepository {
    private static final int SAMPLE_AUDIO_RATE = 44100;
    private static final int FRAME_RATE = 30;
    private static final String VIDEO_FORMAT = "mp4";
    private static final int VIDEO_BITRATE  = 400000;

    public FFmpegFrameRecorder getRecorder(String outputPath, int width, int height) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, width, height, 1);
        recorder.setFormat(VIDEO_FORMAT);
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setFrameRate(FRAME_RATE);
        recorder.setAudioCodec(AV_CODEC_ID_AAC);
        recorder.setSampleRate(SAMPLE_AUDIO_RATE);
        recorder.setVideoBitrate(VIDEO_BITRATE);
        return recorder;
    }
}
