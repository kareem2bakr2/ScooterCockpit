package com.scooter.cockpit;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DashcamRecorder implements SurfaceHolder.Callback, MediaRecorder.OnInfoListener, Camera.ErrorCallback {

    private static final String TAG = "DashcamRecorder";
    private static final int SEGMENT_DURATION_MS = 3 * 60 * 1000;
    private static final long MIN_FREE_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_SURFACE_WAITS = 5;

    private final Context context;
    private final SurfaceView previewView;               // NEW
    private final SurfaceHolder surfaceHolder;
    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper()); // NEW

    private Camera camera;
    private MediaRecorder mediaRecorder;
    private SurfaceTexture dummyTexture;                 // NEW: fallback preview target
    private volatile boolean usingTexture = false;       // NEW
    private volatile boolean isSurfaceReady = false;
    private volatile boolean isRecording = false;
    private volatile boolean shouldRecord = false;
    private int surfaceWaits = 0;                        // NEW
    private File currentOutputFile;

    private final int[] videoCodecs = {
            MediaRecorder.VideoEncoder.MPEG_4_SP,
            MediaRecorder.VideoEncoder.H264,
            MediaRecorder.VideoEncoder.H263
    };
    private int codecIndex = 0;

    public DashcamRecorder(Context context, SurfaceView previewSurface) {
        this.context = context.getApplicationContext();
        this.previewView = previewSurface;
        this.workerThread = new HandlerThread("DashcamWorker");
        this.workerThread.start();
        this.workerHandler = new Handler(workerThread.getLooper());

        this.surfaceHolder = previewSurface.getHolder();
        this.surfaceHolder.addCallback(this);
        this.surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void startLoopRecording() {
        shouldRecord = true;
        surfaceWaits = 0;
        scheduleStart(1000L);
    }

    public void stopLoopRecording() {
        shouldRecord = false;
        workerHandler.removeCallbacksAndMessages(null);
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                stopCurrentSegmentInternal(false);
                releaseCameraInternal();
            }
        });
    }

    private void scheduleStart(long delayMs) {
        workerHandler.removeCallbacks(startSegmentRunnable);
        workerHandler.postDelayed(startSegmentRunnable, delayMs);
    }

    private final Runnable startSegmentRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldRecord || isRecording) return;

            // Give the SurfaceView a few seconds to appear (for a live preview).
            // After that, record anyway using the dummy texture.
            if (!isSurfaceReady && surfaceWaits < MAX_SURFACE_WAITS) {
                surfaceWaits++;
                scheduleStart(1000L);
                return;
            }
            beginNewSegmentInternal();
        }
    };

    private File getVideoStorageDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File sdDir = new File(Environment.getExternalStorageDirectory(), "ScooterDashcam");
            if ((sdDir.exists() || sdDir.mkdirs()) && sdDir.canWrite()) {
                return sdDir;
            }
        }
        File internalDir = new File(context.getFilesDir(), "ScooterDashcam");
        if (!internalDir.exists()) {
            internalDir.mkdirs();
        }
        return internalDir;
    }

    @SuppressWarnings("deprecation")
    private long getAvailableBytes(File dir) {
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Exception e) {
            return dir.getUsableSpace();
        }
    }

    private void ensureFreeSpace(File dir) {
        while (getAvailableBytes(dir) < MIN_FREE_BYTES) {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) break;

            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
                }
            });

            boolean deletedAny = false;
            for (File oldest : files) {
                if (oldest.isFile() && oldest.getName().endsWith(".mp4")) {
                    if (oldest.delete()) {
                        deletedAny = true;
                        break;
                    }
                }
            }
            if (!deletedAny) break;
        }
    }

    // ---------------------------------------------------------------- camera

    private boolean openCameraIfNeeded() {
        if (camera != null) return true;

        int count = Camera.getNumberOfCameras();
        for (int id = 0; id < count; id++) {
            if (tryOpenCamera(id)) return true;
        }
        if (tryOpenCamera(-1)) return true;   // default camera

        if (shouldRecord) {
            scheduleStart(3000L);
        }
        return false;
    }

    private boolean tryOpenCamera(int id) {
        try {
            Camera cam = (id >= 0) ? Camera.open(id) : Camera.open();
            if (cam == null) return false;
            camera = cam;
            cam.setErrorCallback(this);
            attachPreview(cam);
            cam.startPreview();
            Log.i(TAG, "Camera " + (id >= 0 ? id : "default") + " opened, preview="
                    + (usingTexture ? "dummy texture" : "surface"));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Camera open(" + id + ") failed: " + e.getMessage());
            releaseCameraInternal();
            return false;
        }
    }

    /** Real surface when it works, dummy SurfaceTexture when it is stale or rejected. */
    private void attachPreview(Camera cam) throws Exception {
        usingTexture = false;
        if (isSurfaceReady) {
            try {
                cam.setPreviewDisplay(surfaceHolder);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Preview surface rejected (" + e.getMessage() + "), using dummy texture");
            }
        }
        if (dummyTexture == null) {
            dummyTexture = new SurfaceTexture(10);
        }
        cam.setPreviewTexture(dummyTexture);
        usingTexture = true;
    }

    @Override
    public void onError(int error, Camera cam) {
        Log.w(TAG, "Camera error " + error + " - recovering");
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                stopCurrentSegmentInternal(false);
                releaseCameraInternal();
                if (shouldRecord) {
                    surfaceWaits = MAX_SURFACE_WAITS; // don't wait for the surface, texture fallback is fine
                    scheduleStart(3000L);             // let mediaserver respawn first
                    refreshPreviewSurface();          // get a fresh surface for the next segment
                }
            }
        });
    }

    /** Hiding and showing a SurfaceView destroys and recreates its Surface. */
    private void refreshPreviewSurface() {
        isSurfaceReady = false;
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                previewView.setVisibility(View.INVISIBLE);
            }
        });
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                previewView.setVisibility(View.VISIBLE);
            }
        }, 600L);
    }

    private Camera.Size getCompatibleSize(Camera.Parameters params) {
        List<Camera.Size> sizes = params.getSupportedVideoSizes();
        if (sizes == null || sizes.isEmpty()) {
            sizes = params.getSupportedPreviewSizes();
        }
        Camera.Size chosen = sizes.get(0);
        for (Camera.Size s : sizes) {
            if ((s.width == 320 && s.height == 240) || (s.width == 352 && s.height == 288) || (s.width == 640 && s.height == 480)) {
                chosen = s;
                if (s.width <= 352) break;
            }
        }
        return chosen;
    }

    private void beginNewSegmentInternal() {
        if (!shouldRecord) return;

        File dir = getVideoStorageDir();
        ensureFreeSpace(dir);

        if (!openCameraIfNeeded()) return;

        try {
            Camera.Parameters params = camera.getParameters();
            Camera.Size size = getCompatibleSize(params);
            params.setPreviewSize(size.width, size.height);
            camera.setParameters(params);

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentOutputFile = new File(dir, "VID_" + timestamp + ".mp4");

            camera.unlock();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setCamera(camera);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(videoCodecs[codecIndex]);
            mediaRecorder.setVideoSize(size.width, size.height);
            mediaRecorder.setVideoFrameRate(15);
            mediaRecorder.setVideoEncodingBitRate(1000000);
            mediaRecorder.setOutputFile(currentOutputFile.getAbsolutePath());
            mediaRecorder.setMaxDuration(SEGMENT_DURATION_MS);
            mediaRecorder.setOnInfoListener(this);

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            Log.i(TAG, "Recording started (" + size.width + "x" + size.height + ", codec=" + videoCodecs[codecIndex] + ")");
        } catch (Exception e) {
            Log.e(TAG, "Start segment failed: " + e, e);
            stopCurrentSegmentInternal(false);
            releaseCameraInternal();

            if (shouldRecord) {
                if (codecIndex + 1 < videoCodecs.length) {
                    codecIndex++;
                    scheduleStart(1500L);
                } else {
                    codecIndex = 0;          // all codecs failed: back off, then try again
                    scheduleStart(10000L);
                }
            }
        }
    }

    private void stopCurrentSegmentInternal(boolean startNext) {
        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                }
            } catch (RuntimeException e) {
                if (currentOutputFile != null && currentOutputFile.exists()) {
                    currentOutputFile.delete();
                }
            } finally {
                // after a media server crash these can throw, never let that escape
                try { mediaRecorder.reset(); } catch (Exception ignored) {}
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
                isRecording = false;
            }
        }
        if (camera != null) {
            try {
                camera.lock();
            } catch (Exception ignored) {
            }
        }
        if (startNext && shouldRecord) {
            // If we fell back to the dummy texture earlier, re-bind the real preview now
            if (usingTexture && isSurfaceReady) {
                releaseCameraInternal();
            }
            beginNewSegmentInternal();
        }
    }

    private void releaseCameraInternal() {
        if (camera != null) {
            try {
                camera.setErrorCallback(null);
            } catch (Exception ignored) {
            }
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.setPreviewDisplay(null);
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
        usingTexture = false;
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopCurrentSegmentInternal(true);
                }
            });
        }
    }

    // --------------------------------------------------------------- surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isSurfaceReady = true;
        surfaceWaits = 0;
        if (shouldRecord && !isRecording) {
            scheduleStart(500L);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isSurfaceReady = false;

        // Recording on the dummy texture does not depend on this surface, keep going.
        if (usingTexture) return;

        // The camera must stop using the surface BEFORE it is gone, so wait for the worker.
        final CountDownLatch latch = new CountDownLatch(1);
        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    stopCurrentSegmentInternal(false);
                    releaseCameraInternal();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Do not cancel pending work. Resume recording (texture fallback if no surface returns).
        if (shouldRecord) {
            scheduleStart(1000L);
        }
    }
}