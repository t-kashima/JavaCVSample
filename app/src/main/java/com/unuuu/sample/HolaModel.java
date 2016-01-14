package com.unuuu.sample;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.ViewGroup;

import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class HolaModel implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {

    private static final String CLASS_LABEL = "HolaModel";
    private static final String LOG_TAG = CLASS_LABEL;
    private static final String OUTPUT_PATH = "/mnt/sdcard/stream.mp4";
    private static final int PREVIEW_BASE_WIDTH = 480;
    private static final int PREVIEW_BASE_HEIGHT = 480;
    private static final int MAX_RECORD_SECONDS = 5;

    private CameraRepository mCameraRepository;
    private RecorderRepository mRecorderRepository;

    private Camera mCamera;
    private FFmpegFrameRecorder mRecorder;
    private long mStartTime = 0;

    /* audio data getting thread */
    private AudioRecord mAudioRecord;
    volatile boolean mRunAudioThread = true;
    private AudioRecordRunnable mAudioRecordRunnable;
    private Thread mAudioThread;
    private boolean mIsRecording = false;
    private Frame[] mImageFrames;
    private long[] mTimestamps;
    private ShortBuffer[] mSamples;
    private int mImagesIndex;
    private int mSamplesIndex;

    public interface OnRecordInfoListener {
        void onStart();
        void onCancel();
        void onFinish();
    }

    private OnRecordInfoListener mRecordInfoListener;

    public HolaModel() {
        mCameraRepository = new CameraRepository();
        mRecorderRepository = new RecorderRepository();
    }

    /**
     * 録画を開始する
     *
     * @param textureView プレビューを表示するView
     */
    public void startRecording(TextureView textureView) {
        mCamera = mCameraRepository.getCamera(CameraRepository.CameraType.REAR, PREVIEW_BASE_WIDTH, PREVIEW_BASE_HEIGHT);
        if (mCamera == null) {
            return;
        }
        Camera.Parameters parameters = mCamera.getParameters();
        Camera.Size previewSize = parameters.getPreviewSize();
        mRecorder = mRecorderRepository.getRecorder(OUTPUT_PATH, PREVIEW_BASE_WIDTH, PREVIEW_BASE_HEIGHT);
        mAudioRecordRunnable = new AudioRecordRunnable(mRecorder.getSampleRate());
        mAudioThread = new Thread(mAudioRecordRunnable);
        mRunAudioThread = true;

        mImagesIndex = 0;
        mImageFrames = new Frame[MAX_RECORD_SECONDS * (int)(mRecorder.getFrameRate())];
        mTimestamps = new long[mImageFrames.length];
        for (int i = 0; i < mImageFrames.length; i++) {
            mImageFrames[i] = new Frame(PREVIEW_BASE_WIDTH, PREVIEW_BASE_HEIGHT, Frame.DEPTH_UBYTE, 2);
            mTimestamps[i] = -1;
        }

        textureView.setSurfaceTextureListener(this);

        // SurfaceViewの縦横比をプレビューの縦横比に合わせる
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)textureView.getLayoutParams();
        int previewLayoutWidth = layoutParams.width;
        int previewLayoutHeight = layoutParams.height;
        int topMargin = 0;
        int leftMargin = 0;
        if (previewSize.width < previewSize.height) {
            previewLayoutHeight = (int)(previewLayoutWidth * ((float)previewSize.height / previewSize.width));
            topMargin = (previewLayoutHeight - layoutParams.height) / 2;
        } else {
            previewLayoutWidth = (int)(previewLayoutHeight * ((float)previewSize.width / previewSize.height));
            leftMargin = (previewLayoutWidth - layoutParams.width) / 2;
        }
        layoutParams.topMargin = -topMargin;
        layoutParams.leftMargin = -leftMargin;
        layoutParams.width = previewLayoutWidth;
        layoutParams.height = previewLayoutHeight;
        textureView.setLayoutParams(layoutParams);

        Log.d(LOG_TAG, "SurfaceViewのサイズ: " + previewLayoutWidth + ", " + previewLayoutHeight);

        try {
            mRecorder.start();
            mStartTime = System.currentTimeMillis();
            mIsRecording = true;
            mAudioThread.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            Log.e(LOG_TAG, e.getMessage());
        }

        if (textureView.isAvailable()) {
            startPreview(textureView.getSurfaceTexture());
        }

        if (mRecordInfoListener != null) {
            mRecordInfoListener.onStart();
        }
    }

    /**
     * 録画を中断する
     */
    public void cancelRecording() {
        mRunAudioThread = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;

        if (mRecorder != null && mIsRecording) {
            mIsRecording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                mRecorder.stop();
                mRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.e(LOG_TAG, e.getMessage());
            }
            mRecorder = null;

            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;

            if (mRecordInfoListener != null) {
                mRecordInfoListener.onCancel();
            }
        }
    }

    /**
     * 録画を止める
     */
    public void stopRecording() {
        mRunAudioThread = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;

        if (mRecorder != null && mIsRecording) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();

            Log.v(LOG_TAG,"Writing frames");
            try {
                int firstIndex = 0;
                int lastIndex = mImagesIndex - 1;
                for (int i = firstIndex; i <= lastIndex; i++) {
                    long t = mTimestamps[i];
                    if (t > mRecorder.getTimestamp()) {
                        mRecorder.setTimestamp(t);
                    }
                    mRecorder.record(mImageFrames[i]);

                    if (i <= (mSamplesIndex - 1)) {
                        mRecorder.recordSamples(mSamples[i]);
                    }
                }
                Log.d(LOG_TAG, "動画のフレーム数: " + mImageFrames.length + ", 音声のフレーム数: " + mSamples.length);
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.e(LOG_TAG, e.getMessage());
            }

            mIsRecording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                mRecorder.stop();
                mRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.e(LOG_TAG, e.getMessage());
            }
            mRecorder = null;

            mCamera.release();
            mCamera = null;

            if (mRecordInfoListener != null) {
                mRecordInfoListener.onFinish();
            }
        }
    }

    /**
     * 録画中に録音するクラス
     */
    private class AudioRecordRunnable implements Runnable {
        private int mSamplingRate;

        public AudioRecordRunnable(int samplingRate) {
            mSamplingRate = samplingRate;
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize = AudioRecord.getMinBufferSize(mSamplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, mSamplingRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            mSamplesIndex = 0;
            mSamples = new ShortBuffer[MAX_RECORD_SECONDS * mSamplingRate * 2 / bufferSize + 1];
            for (int i = 0; i < mSamples.length; i++) {
                mSamples[i] = ShortBuffer.allocate(bufferSize);
            }
            mAudioRecord.startRecording();

            ShortBuffer audioData;
            int bufferReadResult;
            while (mRunAudioThread) {
                audioData = mSamples[mSamplesIndex % mSamples.length];
                audioData.position(0).limit(0);

                mSamplesIndex += 1;

                bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
            }
            Log.v(LOG_TAG, "AudioThread Finished, release audioRecord");

            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (mCamera == null) {
            return;
        }
        startPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (mCamera == null) {
            return true;
        }
        stopPreview();
        return true;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (mAudioRecord == null || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            mStartTime = System.currentTimeMillis();
            return;
        }

        int i = mImagesIndex % mImageFrames.length;
        mImagesIndex += 1;
        Frame yuvImage = mImageFrames[i];

        mTimestamps[i] = DateUtils.SECOND_IN_MILLIS * (System.currentTimeMillis() - mStartTime);
        if (yuvImage != null && mIsRecording) {
            try {
                Camera.Parameters parameters = camera.getParameters();
                Camera.Size previewSize = parameters.getPreviewSize();
                FFmpegFrameFilter filter = new FFmpegFrameFilter("crop=" + PREVIEW_BASE_WIDTH + ":" + PREVIEW_BASE_HEIGHT, previewSize.width, previewSize.height);
                filter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
                filter.start();
                Frame frame = new Frame(previewSize.width, previewSize.height, Frame.DEPTH_UBYTE, 2);
                ((ByteBuffer) frame.image[0].position(0)).put(data);
                filter.push(frame);
                mImageFrames[i] = filter.pull();
            } catch (FFmpegFrameFilter.Exception e) {
            }
            Log.d(LOG_TAG, "Writing Frame...");
        }

        if (mImagesIndex % mImageFrames.length == 0) {
            stopRecording();
        }
    }

    /**
     * プレビューを開始する
     * @param surface プレビューを表示するテクスチャ
     */
    private void startPreview(SurfaceTexture surface) {
        mCamera.stopPreview();
        try {
            mCamera.setPreviewCallback(this);
            mCamera.setPreviewTexture(surface);
            mCamera.startPreview();
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
        }

    }

    /**
     * プレビューを止める
     */
    private void stopPreview() {
        try {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    /**
     * 録画に関するリスナー
     *
     * @param listener リスナー
     */
    public void setOnRecordInfoListener(OnRecordInfoListener listener) {
        mRecordInfoListener = listener;
    }
}
