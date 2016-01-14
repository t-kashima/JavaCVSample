package com.unuuu.sample;

import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class HolaModel implements SurfaceHolder.Callback, Camera.PreviewCallback {

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

    public HolaModel() {
        mCameraRepository = new CameraRepository();
        mRecorderRepository = new RecorderRepository();
    }

    /**
     * 録画を開始する
     *
     * @param surfaceView プレビューを表示するView
     */
    public void startRecording(SurfaceView surfaceView) {
        mCamera = mCameraRepository.getCamera(CameraRepository.CameraType.REAR, PREVIEW_BASE_WIDTH, PREVIEW_BASE_HEIGHT);
        if (mCamera == null) {
            return;
        }
        Camera.Parameters parameters = mCamera.getParameters();
        Camera.Size previewSize = parameters.getPreviewSize();
        mRecorder = mRecorderRepository.getRecorder(OUTPUT_PATH, previewSize.width, previewSize.height);
        mAudioRecordRunnable = new AudioRecordRunnable(mRecorder.getSampleRate());
        mAudioThread = new Thread(mAudioRecordRunnable);
        mRunAudioThread = true;

        mImagesIndex = 0;
        mImageFrames = new Frame[MAX_RECORD_SECONDS * (int)(mRecorder.getFrameRate())];
        mTimestamps = new long[mImageFrames.length];
        for (int i = 0; i < mImageFrames.length; i++) {
            mImageFrames[i] = new Frame(previewSize.width, previewSize.height, Frame.DEPTH_UBYTE, 2);
            mTimestamps[i] = -1;
        }

        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // SurfaceViewの縦横比をプレビューの縦横比に合わせる
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)surfaceView.getLayoutParams();
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
        surfaceView.setLayoutParams(layoutParams);

        Log.d(LOG_TAG, "SurfaceViewのサイズ: " + previewLayoutWidth + ", " + previewLayoutHeight);

        try {
            mRecorder.start();
            mStartTime = System.currentTimeMillis();
            mIsRecording = true;
            mAudioThread.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            Log.e(LOG_TAG, e.getMessage());
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
            Log.v(LOG_TAG,"Writing frames");
            try {
                int firstIndex = mImagesIndex % mSamples.length;
                int lastIndex = (mImagesIndex - 1) % mImageFrames.length;
                if (mImagesIndex <= mImageFrames.length) {
                    firstIndex = 0;
                    lastIndex = mImagesIndex - 1;
                }
                if ((mStartTime = mTimestamps[lastIndex] - MAX_RECORD_SECONDS * 1000000L) < 0) {
                    mStartTime = 0;
                }
                if (lastIndex < firstIndex) {
                    lastIndex += mImageFrames.length;
                }
                for (int i = firstIndex; i <= lastIndex; i++) {
                    long t = mTimestamps[i % mTimestamps.length] - mStartTime;
                    if (t >= 0) {
                        if (t > mRecorder.getTimestamp()) {
                            mRecorder.setTimestamp(t);
                        }
                        mRecorder.record(mImageFrames[i % mImageFrames.length]);
                    }
                }

                firstIndex = mSamplesIndex % mSamples.length;
                lastIndex = (mSamplesIndex - 1) % mSamples.length;
                if (mSamplesIndex <= mSamples.length) {
                    firstIndex = 0;
                    lastIndex = mSamplesIndex - 1;
                }
                if (lastIndex < firstIndex) {
                    lastIndex += mSamples.length;
                }
                for (int i = firstIndex; i <= lastIndex; i++) {
                    mRecorder.recordSamples(mSamples[i % mSamples.length]);
                }
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
        }
    }

    /**
     * 録画中かどうか
     *
     * @return 録画中かどうか
     */
    public boolean isRecording() {
        return mIsRecording;
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

            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mCamera == null) {
            return;
        }

        mCamera.stopPreview();
        try {
            mCamera.setPreviewCallback(this);
            mCamera.setPreviewDisplay(holder);
            mCamera.startPreview();
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera == null) {
            return;
        }

        try {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, e.getMessage());
        }
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
            ((ByteBuffer) yuvImage.image[0].position(0)).put(data);
            Log.d(LOG_TAG, "Writing Frame...");
        }
    }
}
