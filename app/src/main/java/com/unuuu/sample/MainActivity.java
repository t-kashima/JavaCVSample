package com.unuuu.sample;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.devbrackets.android.exomedia.EMVideoView;

import java.io.File;

public class MainActivity extends Activity {

    private static final String OUTPUT_PATH = "/mnt/sdcard/stream.mp4";

    private TextureView mTextureView;
    private Button mRecordButton;
    private FrameLayout mPreviewLayout;
    private RelativeLayout mVideoLayout;
    private EMVideoView mVideoView;
    private HolaModel mHolaModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        mHolaModel = new HolaModel();

        mPreviewLayout = (FrameLayout) findViewById(R.id.activity_main_layout_preview);
        mTextureView = (TextureView) findViewById(R.id.activity_main_surface_preview);
        mVideoLayout = (RelativeLayout) findViewById(R.id.activity_main_layout_video);
        mVideoView = (EMVideoView) findViewById(R.id.activity_main_video);

        mRecordButton = (Button) findViewById(R.id.activity_main_button_record);
        mRecordButton.setText("Start");
        mRecordButton.setOnClickListener(v -> {
            if (!mHolaModel.isRecording()) {
                mHolaModel.startRecording(mTextureView);
                hideVideoView();
            } else {
                mHolaModel.stopRecording();
                showVideoView();
            }
        });

        // 録画ファイルが存在している時はすぐ再生する
        if (new File(OUTPUT_PATH).exists()) {
            showVideoView();
        }
    }

    /**
     * 録画したビデオを非表示にする
     */
    private void hideVideoView() {
        mRecordButton.setText("Stop");
        mPreviewLayout.setVisibility(View.VISIBLE);
        if (mVideoView.isPlaying()) {
            mVideoView.stopPlayback();
        }
        mVideoLayout.setVisibility(View.INVISIBLE);
    }

    /**
     * 録画したビデオを再生する
     */
    private void showVideoView() {
        mRecordButton.setText("Start");
        mPreviewLayout.setVisibility(View.INVISIBLE);
        mVideoLayout.setVisibility(View.VISIBLE);
        mVideoView.setOnPreparedListener(mp -> mVideoView.start());
        mVideoView.setOnCompletionListener(mp -> {
            mVideoView.seekTo(0);
            mVideoView.start();
        });
        mVideoView.setVideoURI(Uri.parse(OUTPUT_PATH));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHolaModel.stopRecording();
    }
}
