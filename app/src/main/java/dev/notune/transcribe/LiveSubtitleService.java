package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class LiveSubtitleService extends Service {
    private static final String TAG = "LiveSubtitleService";
    public static final String ACTION_START = "dev.notune.transcribe.START_SUBTITLES";
    public static final String ACTION_STOP = "dev.notune.transcribe.STOP_SUBTITLES";
    private static final String CHANNEL_ID = "LiveSubtitlesChannel";
    private static final int NOTIFICATION_ID = 12345;
    private static final int MAX_COMMITTED_LINES = 2;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;
    private volatile boolean isRecording = false;

    private WindowManager mWindowManager;
    private View mOverlayView;
    private TextView mOriginalText;
    private TextView mTranslationText;
    private Handler mMainHandler;

    private SubtitleEngine mSubtitleEngine;
    private TranslationManager mTranslationManager;
    private final Object mSubtitleLock = new Object();
    private final ArrayDeque<SubtitleLine> mCommittedLines = new ArrayDeque<>();
    private long mNextSegmentId = 1;
    private long mPreviewRevision = 0;
    private String mPreviewOriginal = "";
    private String mPreviewTranslation = "";

    private static class SubtitleLine {
        final long id;
        final String original;
        String translated;

        SubtitleLine(long id, String original) {
            this.id = id;
            this.original = original;
            this.translated = original;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_START.equals(intent.getAction())) {
            Notification notification = createNotification();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground service", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");

            if (code != 0 && data != null) {
                startSubtitleSession(code, data);
            } else {
                Log.e(TAG, "Missing or invalid extras for media projection");
                stopSelf();
            }
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopSubtitleSession();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startSubtitleSession(int code, Intent data) {
        if (isRecording) return;

        mMediaProjection = mProjectionManager.getMediaProjection(code, data);
        if (mMediaProjection == null) {
            stopSelf();
            return;
        }

        setupOverlay();
        updateStatus("Loading models...", "Preparing live translation...");

        new Thread(() -> {
            // Initialize ASR engine
            mSubtitleEngine = new SubtitleEngine();
            mSubtitleEngine.init(this, status -> mMainHandler.post(
                    () -> updateStatus(status, "Preparing live translation...")));

            if (!mSubtitleEngine.isInitialized()) {
                mMainHandler.post(() -> updateStatus(
                        "Error: ASR model failed to load",
                        "Check model assets and restart"));
                return;
            }

            // Initialize translation and pre-download model for configured language
            mTranslationManager = new TranslationManager();
            String sourceLang = mSubtitleEngine.getSourceLanguage();
            mMainHandler.post(() -> updateStatus(
                    "Loading ASR complete",
                    "Downloading translation model..."));
            boolean downloaded = mTranslationManager.downloadModelSync(sourceLang, 60000);

            if (downloaded) {
                Log.i(TAG, "Translation model ready for: " + sourceLang);
            } else {
                Log.w(TAG, "Translation model download failed, will show original text");
            }

            mMainHandler.post(() -> {
                updateStatus("Waiting for audio...", "Translation will appear here");
                startAudioCapture();
            });
        }).start();
    }

    private void stopSubtitleSession() {
        isRecording = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mAudioThread = null;
        }
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (Exception ignored) {}
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        if (mSubtitleEngine != null) {
            mSubtitleEngine.release();
            mSubtitleEngine = null;
        }
        if (mTranslationManager != null) {
            mTranslationManager.release();
            mTranslationManager = null;
        }
        removeOverlay();
    }

    private void setupOverlay() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(this);
        mOverlayView = inflater.inflate(R.layout.service_subtitle, null);

        mOriginalText = mOverlayView.findViewById(R.id.subs_original_text);
        mTranslationText = mOverlayView.findViewById(R.id.subs_translation_text);
        updateStatus("Waiting for audio...", "Translation will appear here");

        View closeBtn = mOverlayView.findViewById(R.id.btn_close_subs);
        closeBtn.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, LiveSubtitleService.class);
            stopIntent.setAction(ACTION_STOP);
            startService(stopIntent);
        });

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM;
        params.y = 100;
        mWindowManager.addView(mOverlayView, params);
    }

    private void removeOverlay() {
        if (mOverlayView != null && mWindowManager != null) {
            mWindowManager.removeView(mOverlayView);
            mOverlayView = null;
        }
    }

    private void startAudioCapture() {
        if (mMediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return;
        }

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        int sampleRate = 16000;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        try {
            mAudioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBufferSize, 16000))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stopSubtitleSession();
                return;
            }

            mAudioRecord.startRecording();
            isRecording = true;
            Log.d(TAG, "AudioRecord started successfully");

            mAudioThread = new Thread(this::audioLoop);
            mAudioThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting AudioRecord", e);
            stopSubtitleSession();
        }
    }

    private void audioLoop() {
        Log.d(TAG, "Starting audio loop");
        int bufferSize = 512;
        short[] buffer = new short[bufferSize];
        float[] floatBuffer = new float[bufferSize];

        while (isRecording) {
            int read = mAudioRecord.read(buffer, 0, bufferSize);
            if (read > 0) {
                for (int i = 0; i < read; i++) {
                    floatBuffer[i] = buffer[i] / 32768.0f;
                }

                float[] samples = (read == bufferSize) ? floatBuffer : java.util.Arrays.copyOf(floatBuffer, read);
                SubtitleEngine.SubtitleUpdate update = mSubtitleEngine.processAudio(samples);
                handleEngineUpdate(update);
            } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                Log.e(TAG, "Audio read error: DEAD_OBJECT");
                isRecording = false;
            }
        }

        if (mSubtitleEngine != null && mSubtitleEngine.isInitialized()) {
            List<SubtitleEngine.AsrResult> remaining = mSubtitleEngine.flush();
            clearPreviewLine();
            for (SubtitleEngine.AsrResult asr : remaining) {
                handleCommittedResult(asr);
            }
        }
        Log.d(TAG, "Audio loop finished");
    }

    private void handleEngineUpdate(SubtitleEngine.SubtitleUpdate update) {
        if (update == null) {
            return;
        }
        if (update.previewResult != null) {
            handlePreviewResult(update.previewResult);
        }
        for (SubtitleEngine.AsrResult asr : update.committedResults) {
            handleCommittedResult(asr);
        }
    }

    private void handleCommittedResult(SubtitleEngine.AsrResult asr) {
        SubtitleLine line;
        synchronized (mSubtitleLock) {
            line = new SubtitleLine(mNextSegmentId++, asr.text);
            mCommittedLines.addLast(line);
            while (mCommittedLines.size() > MAX_COMMITTED_LINES) {
                mCommittedLines.removeFirst();
            }
        }
        renderSubtitleState();
        requestCommittedTranslation(line.id, asr.text, asr.lang);
    }

    private void handlePreviewResult(SubtitleEngine.PreviewResult preview) {
        if (preview.text == null || preview.text.trim().isEmpty()) {
            clearPreviewLine();
            return;
        }

        long revision;
        synchronized (mSubtitleLock) {
            if (preview.text.equals(mPreviewOriginal)) {
                return;
            }
            mPreviewOriginal = preview.text;
            mPreviewTranslation = isChinese(preview.lang) ? preview.text : "";
            revision = ++mPreviewRevision;
        }
        renderSubtitleState();
        requestPreviewTranslation(revision, preview.text, preview.lang);
    }

    private void clearPreviewLine() {
        synchronized (mSubtitleLock) {
            mPreviewOriginal = "";
            mPreviewTranslation = "";
            mPreviewRevision++;
        }
        renderSubtitleState();
    }

    private void requestCommittedTranslation(long lineId, String text, String lang) {
        if (mTranslationManager == null) {
            return;
        }
        mTranslationManager.translateWhenReady(text, lang, new TranslationManager.TranslateCallback() {
            @Override
            public void onResult(String translatedText) {
                synchronized (mSubtitleLock) {
                    for (SubtitleLine line : mCommittedLines) {
                        if (line.id == lineId) {
                            line.translated = translatedText;
                            break;
                        }
                    }
                }
                renderSubtitleState();
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Committed translation error: " + error);
            }
        });
    }

    private void requestPreviewTranslation(long revision, String text, String lang) {
        if (mTranslationManager == null || isChinese(lang)) {
            return;
        }

        mTranslationManager.translateWhenReady(text, lang, new TranslationManager.TranslateCallback() {
            @Override
            public void onResult(String translatedText) {
                synchronized (mSubtitleLock) {
                    if (revision != mPreviewRevision || !text.equals(mPreviewOriginal)) {
                        return;
                    }
                    mPreviewTranslation = translatedText;
                }
                renderSubtitleState();
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Preview translation error: " + error);
            }
        });
    }

    private void renderSubtitleState() {
        final String originalText;
        final String translationText;
        synchronized (mSubtitleLock) {
            originalText = buildDisplayText(false);
            translationText = buildDisplayText(true);
        }

        mMainHandler.post(() -> {
            if (mOriginalText != null) {
                mOriginalText.setText(originalText.isEmpty() ? "Waiting for audio..." : originalText);
            }
            if (mTranslationText != null) {
                mTranslationText.setText(translationText.isEmpty() ? "Translation will appear here" : translationText);
            }
        });
    }

    private String buildDisplayText(boolean translated) {
        List<String> lines = new ArrayList<>();
        for (SubtitleLine line : mCommittedLines) {
            lines.add(translated ? line.translated : line.original);
        }

        if (!mPreviewOriginal.isEmpty()) {
            if (translated) {
                lines.add(mPreviewTranslation.isEmpty() ? "..." : mPreviewTranslation);
            } else {
                lines.add(mPreviewOriginal);
            }
        }

        return TextUtils.join("\n", lines);
    }

    private boolean isChinese(String lang) {
        if (lang == null) {
            return false;
        }
        String normalized = lang.replace("<|", "").replace("|>", "").trim();
        return "zh".equals(normalized) || "yue".equals(normalized);
    }

    private void updateStatus(String originalText, String translationText) {
        if (mOriginalText != null) {
            mOriginalText.setText(originalText);
        }
        if (mTranslationText != null) {
            mTranslationText.setText(translationText);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Live Subtitles", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        createNotificationChannel();
        Intent stopIntent = new Intent(this, LiveSubtitleService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Live Subtitles Active")
                .setContentText("Recognizing & translating audio...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
