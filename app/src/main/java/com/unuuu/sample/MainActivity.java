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
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.List;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;

public class MainActivity extends Activity {

    private static final String CLASS_LABEL = "RecordActivity";
    private static final String LOG_TAG = CLASS_LABEL;
    private static final String OUTPUT_PATH = "/mnt/sdcard/stream.mp4";
    private static final int SAMPLE_AUDIO_RATE = 44100;
    private static final int FRAME_RATE = 30;
    /* The number of seconds in the continuous record loop (or 0 to disable loop). */
    private static final int RECORD_LENGTH = 10;
    private static final int PREVIEW_BASE_WIDTH = 320;
    private static final int PREVIEW_BASE_HEIGHT = 240;

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

        /* add control button: start and stop */
        mRecordButton = (Button) findViewById(R.id.activity_main_button_record);
        mRecordButton.setText("Start");
        mRecordButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mIsRecording) {
                    startRecording();
                    mRecordButton.setText("Stop");
                } else {
                    // This will trigger the audio recording loop to stop and then set isRecorderStart = false;
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

    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initRecorder() {

        Log.w(LOG_TAG,"init recorder");

        Frame yuvImage = null;
        if (RECORD_LENGTH > 0) {
            mImagesIndex = 0;
            mImageFrames = new Frame[RECORD_LENGTH * FRAME_RATE];
            mTimestamps = new long[mImageFrames.length];
            for (int i = 0; i < mImageFrames.length; i++) {
                mImageFrames[i] = new Frame(mPreviewWidth, mPreviewHeight, Frame.DEPTH_UBYTE, 2);
                mTimestamps[i] = -1;
            }
        } else if (yuvImage == null) {
            yuvImage = new Frame(mPreviewWidth, mPreviewHeight, Frame.DEPTH_UBYTE, 2);
            Log.i(LOG_TAG, "create yuvImage");
        }

        Log.i(LOG_TAG, "output: " + OUTPUT_PATH);

        mRecorder = new FFmpegFrameRecorder(OUTPUT_PATH, mPreviewWidth, mPreviewHeight, 1);
        mRecorder.setFormat("mp4");
        mRecorder.setVideoCodec(AV_CODEC_ID_H264);
        mRecorder.setSampleRate(SAMPLE_AUDIO_RATE);
        mRecorder.setFrameRate(FRAME_RATE);

        mAudioRecordRunnable = new AudioRecordRunnable();
        mAudioThread = new Thread(mAudioRecordRunnable);
        mRunAudioThread = true;
    }

    public void startRecording() {

        initRecorder();

        try {
            mRecorder.start();
            mStartTime = System.currentTimeMillis();
            mIsRecording = true;
            mAudioThread.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {
        mRunAudioThread = false;
        try {
            mAudioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;

        if (mRecorder != null && mIsRecording) {
            if (RECORD_LENGTH > 0) {
                Log.v(LOG_TAG,"Writing frames");
                try {
                    int firstIndex = mImagesIndex % mSamples.length;
                    int lastIndex = (mImagesIndex - 1) % mImageFrames.length;
                    if (mImagesIndex <= mImageFrames.length) {
                        firstIndex = 0;
                        lastIndex = mImagesIndex - 1;
                    }
                    if ((mStartTime = mTimestamps[lastIndex] - RECORD_LENGTH * 1000000L) < 0) {
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
                    Log.v(LOG_TAG,e.getMessage());
                    e.printStackTrace();
                }
            }

            mIsRecording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                mRecorder.stop();
                mRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            mRecorder = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mIsRecording) {
                stopRecording();
            }

            finish();

            return true;
        }

        return super.onKeyDown(keyCode, event);
    }


    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_AUDIO_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (RECORD_LENGTH > 0) {
                mSamplesIndex = 0;
                mSamples = new ShortBuffer[RECORD_LENGTH * SAMPLE_AUDIO_RATE * 2 / bufferSize + 1];
                for (int i = 0; i < mSamples.length; i++) {
                    mSamples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else {
                audioData = ShortBuffer.allocate(bufferSize);
            }

            Log.d(LOG_TAG, "audioRecord.startRecording()");
            mAudioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (mRunAudioThread) {
                if (RECORD_LENGTH > 0) {
                    audioData = mSamples[mSamplesIndex++ % mSamples.length];
                    audioData.position(0).limit(0);
                }
                //Log.v(LOG_TAG,"recording? " + recording);
                bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    Log.v(LOG_TAG,"bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (mIsRecording) {
                        if (RECORD_LENGTH <= 0) try {
                            mRecorder.recordSamples(audioData);
                            //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG,e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(LOG_TAG,"AudioThread Finished, release audioRecord");

            /* encoding finish, release recorder */
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
                Log.v(LOG_TAG,"audioRecord released");
            }
        }
    }

    //---------------------------------------------
    // camera thread, gets and encodes video data
    //---------------------------------------------
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
                Log.e(LOG_TAG, e.toString());
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
                Log.e(LOG_TAG, e.toString());
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (mAudioRecord == null || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                mStartTime = System.currentTimeMillis();
                return;
            }

            Frame yuvImage = null;
            if (RECORD_LENGTH > 0) {
                int i = mImagesIndex++ % mImageFrames.length;
                yuvImage = mImageFrames[i];
                mTimestamps[i] = DateUtils.SECOND_IN_MILLIS * (System.currentTimeMillis() - mStartTime);
            }

            /* get video data */
            if (yuvImage != null && mIsRecording) {
                ((ByteBuffer) yuvImage.image[0].position(0)).put(data);

                if (RECORD_LENGTH <= 0) try {
                    Log.v(LOG_TAG,"Writing Frame");
                    long t = DateUtils.SECOND_IN_MILLIS * (System.currentTimeMillis() - mStartTime);
                    if (t > mRecorder.getTimestamp()) {
                        mRecorder.setTimestamp(t);
                    }
                    mRecorder.record(yuvImage);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.v(LOG_TAG,e.getMessage());
                    e.printStackTrace();
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
