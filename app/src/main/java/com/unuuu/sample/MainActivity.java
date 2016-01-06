package com.unuuu.sample;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.List;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;

public class MainActivity extends Activity {

    private static final String CLASS_LABEL = "RecordActivity";
    private static final String LOG_TAG = CLASS_LABEL;
    private static final String OUTPUT_PATH = "/mnt/sdcard/stream.mp4";
    private static final String OUTPUT_TEXT_PATH = "/mnt/sdcard/stream_2.mp4";
    private static final int SAMPLE_AUDIO_RATE = 44100;
    private static final int FRAME_RATE = 30;
    private static final int MAX_RECORD_SECONDS = 5;
    private static final int PREVIEW_BASE_WIDTH = 320;
    private static final int PREVIEW_BASE_HEIGHT = 240;
    private static final String VIDEO_FORMAT = "mp4";

    long mStartTime = 0;
    boolean mIsRecording = false;
    private FFmpegFrameRecorder mRecorder;

    private int mPreviewWidth;
    private int mPreviewHeight;

    /* audio data getting thread */
    private AudioRecord mAudioRecord;
    private AudioRecordRunnable mAudioRecordRunnable;
    private Thread mAudioThread;
    volatile boolean mRunAudioThread = true;
    private FFmpegFrameFilter mFilter;

    private CameraView mCameraView;
    private Button mRecordButton;
    private FrameLayout mPreviewLayout;

    private Frame[] mImageFrames;
    private long[] mTimestamps;
    private ShortBuffer[] mSamples;
    private int mImagesIndex;
    private int mSamplesIndex;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        mRecordButton = (Button) findViewById(R.id.activity_main_button_record);
        mRecordButton.setText("Start");
        mRecordButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mIsRecording) {
                    startRecording();
                    mRecordButton.setText("Stop");
                } else {
                    stopRecording();
                    mRecordButton.setText("Start");
                }
            }
        });

        mPreviewLayout = (FrameLayout) findViewById(R.id.activity_main_layout_preview);
        mCameraView = new CameraView(this);
        mPreviewLayout.addView(mCameraView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIsRecording = false;
    }

    /**
     * 録画を開始する
     */
    public void startRecording() {
        mImagesIndex = 0;
        mImageFrames = new Frame[MAX_RECORD_SECONDS * FRAME_RATE];
        mTimestamps = new long[mImageFrames.length];
        for (int i = 0; i < mImageFrames.length; i++) {
            mImageFrames[i] = new Frame(mPreviewWidth, mPreviewHeight, Frame.DEPTH_UBYTE, 2);
            mTimestamps[i] = -1;
        }

        Log.d(LOG_TAG, "output: " + OUTPUT_PATH);


//        try {
//            mFilter.start();
//        } catch (FrameFilter.Exception e) {
//            Log.e(LOG_TAG, e.getMessage());
//        }

        mRecorder = new FFmpegFrameRecorder(OUTPUT_PATH, mPreviewWidth, mPreviewHeight, 1);
        mRecorder.setFormat(VIDEO_FORMAT);
        mRecorder.setVideoCodec(AV_CODEC_ID_H264);
        mRecorder.setFrameRate(FRAME_RATE);
        mRecorder.setAudioCodec(AV_CODEC_ID_AAC);
        mRecorder.setSampleRate(SAMPLE_AUDIO_RATE);

        mAudioRecordRunnable = new AudioRecordRunnable();
        mAudioThread = new Thread(mAudioRecordRunnable);
        mRunAudioThread = true;

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
        try {
            mAudioThread.join();
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, e.toString());
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

//    private void appendText() {
//        try {
//            Movie movie = MovieCreator.build(OUTPUT_PATH);
//
//            TextTrackImpl subTitle = new TextTrackImpl();
//            subTitle.getTrackMetaData().setLanguage("eng");
//            subTitle.getSubs().add(new TextTrackImpl.Line(0, 1000, "Five"));
//            subTitle.getSubs().add(new TextTrackImpl.Line(1000, 2000, "Two"));
//            movie.addTrack(subTitle);
//
//            Container container = new DefaultMp4Builder().build(movie);
//            container.writeContainer(new FileOutputStream(OUTPUT_TEXT_PATH).getChannel());
//        } catch (Exception e) {
//            Log.e(LOG_TAG, e.getMessage());
//        }
//        Log.d(LOG_TAG, "字幕をつけました");
//    }

    /**
     * 録画中に録音するクラス
     */
    class AudioRecordRunnable implements Runnable {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            mSamplesIndex = 0;
            mSamples = new ShortBuffer[MAX_RECORD_SECONDS * SAMPLE_AUDIO_RATE * 2 / bufferSize + 1];
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
                if (bufferReadResult > 0) {
                    if (mIsRecording) {
                        try {
                            mRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                        }
                    }
                }
            }
            Log.v(LOG_TAG, "AudioThread Finished, release audioRecord");

            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
            }
        }
    }

    /**
     * カメラのプレビューを表示するクラス
     */
    class CameraView extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback {
        private Camera mCamera;

        public CameraView(Context context) {
            super(context);
            SurfaceHolder holder = getHolder();
            holder.addCallback(this);
            holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera = Camera.open();
            } catch (Exception e) {
                Log.e(LOG_TAG, e.toString());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mCamera == null) {
                return;
            }

            mCamera.stopPreview();

            // 最適なプレビューのサイズを探す
            Camera.Parameters parameters = mCamera.getParameters();
            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            Camera.Size optimalSize = getOptimalPreviewSize(sizes, PREVIEW_BASE_WIDTH, PREVIEW_BASE_HEIGHT);
            mPreviewWidth = optimalSize.width;
            mPreviewHeight = optimalSize.height;

            Log.d(LOG_TAG, "最適なプレビューサイズ: " + mPreviewWidth + ", " + mPreviewHeight);

            // CameraViewの縦横比をプレビューの縦横比に合わせる
            RelativeLayout.LayoutParams previewLayoutParams = (RelativeLayout.LayoutParams)mPreviewLayout.getLayoutParams();
            int previewLayoutWidth = previewLayoutParams.width;
            int previewLayoutHeight = previewLayoutParams.height;
            int topMargin = 0;
            int leftMargin = 0;
            if (mPreviewWidth < mPreviewHeight) {
                previewLayoutHeight = (int)(previewLayoutWidth * ((float)mPreviewHeight / mPreviewWidth));
                topMargin = (previewLayoutHeight - previewLayoutParams.height) / 2;
            } else {
                previewLayoutWidth = (int)(previewLayoutHeight * ((float)mPreviewWidth / mPreviewHeight));
                leftMargin = (previewLayoutWidth - previewLayoutParams.width) / 2;
            }
            FrameLayout.LayoutParams cameraViewParams = (FrameLayout.LayoutParams)mCameraView.getLayoutParams();
            cameraViewParams.topMargin = -topMargin;
            cameraViewParams.leftMargin = -leftMargin;
            cameraViewParams.width = previewLayoutWidth;
            cameraViewParams.height = previewLayoutHeight;
            mCameraView.setLayoutParams(cameraViewParams);

            Log.d(LOG_TAG, "CameraViewのサイズ: " + previewLayoutWidth + ", " + previewLayoutHeight);

            parameters.setPreviewSize(mPreviewWidth, mPreviewHeight);
            parameters.setPreviewFrameRate(FRAME_RATE);
            mCamera.setParameters(parameters);
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
            try {
                mCamera.setPreviewCallback(null);
                mCamera.stopPreview();
                mCamera.release();
                mCamera = null;
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
                ((ByteBuffer)yuvImage.image[0].position(0)).put(data);
                try {
                    Log.d(LOG_TAG, "Writing Frame...");
                    long t = DateUtils.SECOND_IN_MILLIS * (System.currentTimeMillis() - mStartTime);
                    if (t > mRecorder.getTimestamp()) {
                        mRecorder.setTimestamp(t);
                    }
                    mRecorder.record(yuvImage);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.e(LOG_TAG, e.getMessage());
                }
            }
        }

        private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
            int min = Integer.MAX_VALUE;
            Camera.Size optimalSize = sizes.get(0);

            for(Camera.Size size : sizes){
                if(size .width < w || size.height < h){
                    continue;
                }

                int dw = Math.abs(w - size.width);
                int dh = Math.abs(h - size.height);

                if(min > dw + dh){
                    min = dw + dh;
                    optimalSize = size;
                }
            }

            return optimalSize;
        }
    }
}
