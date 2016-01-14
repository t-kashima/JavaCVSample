package com.unuuu.sample;

import android.hardware.Camera;

import java.util.List;

import rx.Observable;
import rx.functions.Func2;

public class CameraRepository {
    private static final int FRAME_RATE = 30;

    public enum CameraType {
        REAR,
        FRONT
    }

    public Camera getCamera(CameraType type, int baseWidth, int baseHeight) {
        try {
            Camera camera = getCamera(type);
            Camera.Parameters parameters = camera.getParameters();
            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            Camera.Size optimalSize = getOptimalPreviewSize(sizes, baseWidth, baseHeight);
            parameters.setPreviewSize(optimalSize.width, optimalSize.height);
            parameters.setPreviewFrameRate(FRAME_RATE);
            camera.setParameters(parameters);
            return camera;
        } catch (Exception e) {

        }
        return null;
    }

    private Camera getCamera(CameraType type) {
        Camera camera;
        try {
            if (CameraType.FRONT == type) {
                int cameraIndex = getFrontCameraIndex().toBlocking().first();
                camera = Camera.open(cameraIndex);
            } else {
                camera = Camera.open();
            }
            return camera;
        } catch (RuntimeException e) {

        }
        return null;
    }

    private Observable<Integer> getFrontCameraIndex() {
        final Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        return Observable.range(0, Camera.getNumberOfCameras())
                .filter(i -> {
                    Camera.getCameraInfo(i, cameraInfo);
                    return cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
                });
    }

    /**
     * 指定された横幅、高さに近いサイズをリストから取得する
     * @param sizes サイズのリスト
     * @param w 横幅
     * @param h 高さ
     * @return サイズ
     */
    public static  Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        if (sizes == null) {
            return null;
        }

        Func2<Camera.Size, Camera.Size, Camera.Size> reduceFunc = (minSize, size) -> {
            if (minSize == null) {
                return size;
            }

            if (w > size.width || h > size.height) {
                return minSize;
            }

            if (Math.abs(size.height - h) < Math.abs(minSize.height - h)) {
                return size;
            }
            return minSize;
        };

        double targetRatio = (double) w / h;
        Camera.Size optimalSize = Observable.from(sizes).filter(size -> {
            double ratio = (double) size.width / size.height;
            return Math.abs(ratio - targetRatio) <= 0.1f;
        }).reduce(null, reduceFunc).toBlocking().first();

        if (optimalSize == null) {
            optimalSize = Observable.from(sizes)
                    .reduce(null, reduceFunc).toBlocking().first();
        }

        return optimalSize;
    }
}
