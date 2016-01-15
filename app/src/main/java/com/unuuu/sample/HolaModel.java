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
import java.util.ArrayList;
import java.util.List;

public class HolaModel implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {

    private static final String CLASS_LABEL = "HolaModel";
    private static final String LOG_TAG = CLASS_LABEL;
    private static final String OUTPUT_PATH = "/mnt/sdcard/stream.mp4";
    private static final int BASE_VIDEO_WIDTH = 480;
    private static final int BASE_VIDEO_HEIGHT = 480;
    private static final int MAX_RECORD_SECONDS = 5;

    private CameraRepository mCameraRepository;
    private RecorderRepository mRecorderRepository;

    private Camera mCamera;
    private FFmpegFrameRecorder mRecorder;
    private long mStartTime = 0;

    /* audio data getting thread */
    private AudioRecord mAudioRecord;
    volatile boolean mIsRunAudioThread = true;
    private AudioRecordRunnable mAudioRecordRunnable;
    private Thread mAudioThread;
    private ShortBuffer[] mSamples;
    private int mSamplesIndex;

    volatile boolean mIsRunRecordThread = true;
    private RecordRunnable mRecordRunnable;
    private Thread mRecordThread;

    private boolean mIsRecording = false;
    private int mMaxFrameIndex;
    private int mFrameIndex;

    private List<Long> mQueueTimestamps;
    private List<Frame> mQueueImageFrames;

    private int mPreviewWidth = 0;
    private int mPreviewHeight = 0;

    private int mVideoWidth = 0;
    private int mVideoHeight = 0;

    /**
     * レコーダーの状態を表すリスナー
     */
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
        mCamera = mCameraRepository.getCamera(CameraRepository.CameraType.REAR, BASE_VIDEO_WIDTH, BASE_VIDEO_HEIGHT);
        if (mCamera == null) {
            return;
        }
        textureView.setSurfaceTextureListener(this);

        Camera.Parameters parameters = mCamera.getParameters();
        Camera.Size previewSize = parameters.getPreviewSize();
        mPreviewWidth = previewSize.width;
        mPreviewHeight = previewSize.height;

        // 正方形に切り抜くので短い方を一辺にする
        if (mPreviewWidth < mPreviewHeight) {
            mVideoWidth = mPreviewWidth;
            mVideoHeight = mPreviewWidth;
        } else {
            mVideoWidth = mPreviewHeight;
            mVideoHeight = mPreviewHeight;
        }

        mRecorder = mRecorderRepository.getRecorder(OUTPUT_PATH, mVideoWidth, mVideoHeight);
        mAudioRecordRunnable = new AudioRecordRunnable(mRecorder.getSampleRate());
        mAudioThread = new Thread(mAudioRecordRunnable);
        mIsRunAudioThread = true;

        mRecordRunnable = new RecordRunnable();
        mRecordThread = new Thread(mRecordRunnable);
        mIsRunRecordThread = true;

        mFrameIndex = 0;
        mMaxFrameIndex = MAX_RECORD_SECONDS * (int)(mRecorder.getFrameRate());

        mQueueTimestamps = new ArrayList<>();
        mQueueImageFrames = new ArrayList<>();

        // SurfaceViewの縦横比をプレビューの縦横比に合わせる
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)textureView.getLayoutParams();
        int previewLayoutWidth = layoutParams.width;
        int previewLayoutHeight = layoutParams.height;
        int topMargin = 0;
        int leftMargin = 0;
        if (mPreviewWidth < mPreviewHeight) {
            previewLayoutHeight = (int)(previewLayoutWidth * ((float)mPreviewHeight / mPreviewWidth));
            topMargin = (previewLayoutHeight - previewLayoutWidth) / 2;
        } else {
            previewLayoutWidth = (int)(previewLayoutHeight * ((float)mPreviewWidth / mPreviewHeight));
            leftMargin = (previewLayoutWidth - previewLayoutHeight) / 2;
        }
        layoutParams.topMargin = -topMargin;
        layoutParams.leftMargin = -leftMargin;
        layoutParams.width = previewLayoutWidth;
        layoutParams.height = previewLayoutHeight;
        textureView.setLayoutParams(layoutParams);

        Log.d(LOG_TAG, "TextureViewのサイズ: " + previewLayoutWidth + ", " + previewLayoutHeight);

        try {
            mRecorder.start();
            mStartTime = System.currentTimeMillis();
            mIsRecording = true;
            mAudioThread.start();
            mRecordThread.start();
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
        if (mRecorder != null && mIsRecording) {
            mIsRecording = false;

            stopPreview();
            releaseAudioRecordThread();
            releaseRecordThread();
            releaseRecorder();

            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");

            if (mRecordInfoListener != null) {
                mRecordInfoListener.onCancel();
            }
        }
    }

    /**
     * 録画を止める
     */
    public void stopRecording() {
        if (mRecorder != null && mIsRecording) {
            mIsRecording = false;

            stopPreview();
            releaseAudioRecordThread();
            releaseRecordThread();

            Log.d(LOG_TAG, "音声のフレーム数: " + mSamples.length);
            try {
                for (int i = 0; i < mSamplesIndex; i++) {
                    mRecorder.recordSamples(mSamples[i]);
                }
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.e(LOG_TAG, e.getMessage());
            }

            releaseRecorder();

            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");

            if (mRecordInfoListener != null) {
                mRecordInfoListener.onFinish();
            }
        }
    }

    /**
     * 録音のスレッドを解放する
     */
    private void releaseAudioRecordThread() {
        mIsRunAudioThread = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;
    }

    /**
     * 録画のスレッドを解放する
     */
    private void releaseRecordThread() {
        mIsRunRecordThread = false;
        if (mRecordThread != null) {
            try {
                mRecordThread.join();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, e.toString());
            }
        }
        mRecordRunnable = null;
        mRecordThread = null;
    }

    /**
     * レコーダーを解放する
     */
    private void releaseRecorder() {
        try {
            mRecorder.stop();
            mRecorder.release();
        } catch (FFmpegFrameRecorder.Exception e) {
            Log.e(LOG_TAG, e.getMessage());
        }
        mRecorder = null;

        mCamera.release();
        mCamera = null;
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
            while (mIsRunAudioThread) {
                if (mSamplesIndex >= mSamples.length) {
                    return;
                }
                audioData = mSamples[mSamplesIndex];
                audioData.position(0).limit(0);

                bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);

                mSamplesIndex += 1;
            }
        }
    }

    /**
     * 録画中に録画するクラス
     */
    private class RecordRunnable implements Runnable {
        @Override
        public void run() {
            while (mIsRunRecordThread) {
                if (!mQueueTimestamps.isEmpty()) {
                    long t = mQueueTimestamps.get(0);
                    if (t > mRecorder.getTimestamp()) {
                        mRecorder.setTimestamp(t);
                    }
                    mQueueTimestamps.remove(0);
                }

                if (!mQueueImageFrames.isEmpty()) {
                    try {
                        String command = String.format("crop=%d:%d:%d:%d", mVideoWidth, mVideoHeight,
                                (mPreviewWidth - mVideoWidth) / 2, (mPreviewHeight - mVideoHeight) / 2);
                        FFmpegFrameFilter filter = new FFmpegFrameFilter(command, mPreviewWidth, mPreviewHeight);
                        filter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
                        filter.start();
                        filter.push(mQueueImageFrames.get(0));
                        mRecorder.record(filter.pull());
                    } catch (FFmpegFrameFilter.Exception | FFmpegFrameRecorder.Exception e) {
                        Log.e(LOG_TAG, e.getMessage());
                    }
                    mQueueImageFrames.remove(0);
                }
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

        // 録画のキューに追加する
        mQueueTimestamps.add(DateUtils.SECOND_IN_MILLIS * (System.currentTimeMillis() - mStartTime));
        Frame frame = new Frame(mPreviewWidth, mPreviewHeight, Frame.DEPTH_UBYTE, 2);
        ((ByteBuffer) frame.image[0].position(0)).put(data);
        mQueueImageFrames.add(frame);

        // 指定数の撮影が終了した時
        if (mFrameIndex >= mMaxFrameIndex) {
            stopRecording();
            return;
        }

        mFrameIndex += 1;
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
