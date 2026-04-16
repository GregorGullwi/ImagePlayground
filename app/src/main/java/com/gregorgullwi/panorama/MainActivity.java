package com.gregorgullwi.panorama;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import com.gregorgullwi.panorama.databinding.ActivityMainBinding;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final String TAG = "Panorama";
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    static {
        System.loadLibrary("panorama");
    }

    private ActivityMainBinding binding;
    private Mat rgbaFrame;
    private Mat grayFrame;
    private Mat edgeFrame;
    private Mat firstPanoramaFrame;
    private Bitmap panoramaBitmap;
    private boolean useCppKernel = true;
    private boolean panoramaMode = false;
    private int panoramaCaptureCount = 0;
    private volatile boolean captureRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.cameraView.setVisibility(SurfaceView.VISIBLE);
        binding.cameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK);
        binding.cameraView.setCvCameraViewListener(this);
        binding.modeButton.setOnClickListener(view -> toggleProcessingMode());
        binding.panoramaButton.setOnClickListener(view -> handlePanoramaButtonClick());
        binding.okButton.setOnClickListener(view -> hidePanoramaResult());
        updateModeButtonText();

        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed");
            Toast.makeText(this, "OpenCV kunde inte startas.", Toast.LENGTH_LONG).show();
            return;
        }

        requestCameraIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            binding.cameraView.enableView();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.cameraView.disableView();
    }

    @Override
    protected void onDestroy() {
        binding.cameraView.disableView();
        releasePanoramaFrame();
        super.onDestroy();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        rgbaFrame = new Mat();
        grayFrame = new Mat();
        edgeFrame = new Mat();
    }

    @Override
    public void onCameraViewStopped() {
        if (rgbaFrame != null) {
            rgbaFrame.release();
            rgbaFrame = null;
        }
        if (grayFrame != null) {
            grayFrame.release();
            grayFrame = null;
        }
        if (edgeFrame != null) {
            edgeFrame.release();
            edgeFrame = null;
        }
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        rgbaFrame = inputFrame.rgba();

        if (captureRequested) {
            Mat capturedFrame = rgbaFrame.clone();
            captureRequested = false;
            runOnUiThread(() -> handleCapturedPanoramaFrame(capturedFrame));
        }

        if (panoramaMode) {
            return rgbaFrame;
        }

        if (useCppKernel) {
            processFrame(rgbaFrame.getNativeObjAddr());
        } else {
            processFrameInJava(rgbaFrame);
        }

        return rgbaFrame;
    }

    private native void processFrame(long rgbaMatAddr);

    private native long createPanorama(long[] rgbaMatAddrs);

    private void processFrameInJava(Mat rgba) {
        Imgproc.cvtColor(rgba, grayFrame, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.Canny(grayFrame, edgeFrame, 80, 160);
        rgba.setTo(new Scalar(0, 255, 0, 255), edgeFrame);
        Imgproc.putText(
                rgba,
                "Java OpenCV",
                new Point(24, 64),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                1.0,
                new Scalar(255, 255, 255, 255),
                2
        );
    }

    private void toggleProcessingMode() {
        useCppKernel = !useCppKernel;
        updateModeButtonText();
    }

    private void updateModeButtonText() {
        binding.modeButton.setText(useCppKernel ? R.string.mode_cpp : R.string.mode_java);
    }

    private void handlePanoramaButtonClick() {
        if (!panoramaMode) {
            startPanoramaCapture();
            return;
        }

        requestPanoramaCapture();
    }

    private void startPanoramaCapture() {
        panoramaMode = true;
        panoramaCaptureCount = 0;
        captureRequested = false;
        releasePanoramaFrame();
        binding.resultOverlay.setVisibility(View.GONE);
        binding.resultImage.setImageDrawable(null);
        binding.panoramaButton.setText(R.string.capture_first);
        binding.panoramaButton.setEnabled(true);
    }

    private void requestPanoramaCapture() {
        if (captureRequested) {
            return;
        }

        captureRequested = true;
        showCaptureFlash();
        binding.panoramaButton.setEnabled(false);
        binding.panoramaButton.setText(R.string.capture_wait);
    }

    private void showCaptureFlash() {
        binding.captureFlash.setAlpha(1.0f);
        binding.captureFlash.setVisibility(View.VISIBLE);
        binding.captureFlash.animate()
                .alpha(0.0f)
                .setDuration(180)
                .withEndAction(() -> binding.captureFlash.setVisibility(View.GONE))
                .start();
    }

    private void handleCapturedPanoramaFrame(Mat capturedFrame) {
        if (!panoramaMode) {
            capturedFrame.release();
            return;
        }

        if (panoramaCaptureCount == 0) {
            releasePanoramaFrame();
            firstPanoramaFrame = capturedFrame;
            panoramaCaptureCount = 1;
            binding.panoramaButton.setText(R.string.capture_second);
            binding.panoramaButton.setEnabled(true);
            return;
        }

        showPanoramaResult(capturedFrame);
        capturedFrame.release();
    }

    private void showPanoramaResult(Mat secondFrame) {
        if (firstPanoramaFrame == null || firstPanoramaFrame.empty() || secondFrame.empty()) {
            resetPanoramaCapture();
            return;
        }

        long panoramaAddress = createPanorama(new long[]{
                firstPanoramaFrame.getNativeObjAddr(),
                secondFrame.getNativeObjAddr()
        });
        if (panoramaAddress == 0) {
            resetPanoramaCapture();
            return;
        }

        Mat panorama = new Mat(panoramaAddress);

        if (panoramaBitmap != null) {
            panoramaBitmap.recycle();
        }
        panoramaBitmap = Bitmap.createBitmap(panorama.cols(), panorama.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(panorama, panoramaBitmap);
        panorama.release();

        binding.resultImage.setImageBitmap(panoramaBitmap);
        binding.resultOverlay.setVisibility(View.VISIBLE);
        binding.panoramaButton.setVisibility(View.GONE);
        binding.modeButton.setVisibility(View.GONE);
        resetPanoramaCapture();
    }

    private void hidePanoramaResult() {
        binding.resultOverlay.setVisibility(View.GONE);
        binding.resultImage.setImageDrawable(null);
        binding.panoramaButton.setVisibility(View.VISIBLE);
        binding.modeButton.setVisibility(View.VISIBLE);
        binding.panoramaButton.setText(R.string.panorama);
        binding.panoramaButton.setEnabled(true);
    }

    private void resetPanoramaCapture() {
        panoramaMode = false;
        panoramaCaptureCount = 0;
        captureRequested = false;
        releasePanoramaFrame();
        binding.panoramaButton.setText(R.string.panorama);
        binding.panoramaButton.setEnabled(true);
    }

    private void releasePanoramaFrame() {
        if (firstPanoramaFrame != null) {
            firstPanoramaFrame.release();
            firstPanoramaFrame = null;
        }
    }

    private void requestCameraIfNeeded() {
        if (hasCameraPermission()) {
            binding.cameraView.setCameraPermissionGranted();
            binding.cameraView.enableView();
            return;
        }

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
        );
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            binding.cameraView.setCameraPermissionGranted();
            binding.cameraView.enableView();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
        }
    }
}
