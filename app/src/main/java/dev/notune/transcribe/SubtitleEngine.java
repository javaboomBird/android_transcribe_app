package dev.notune.transcribe;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.QnnConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates sherpa-onnx VAD + SenseVoice ASR for live subtitle recognition.
 * Receives audio samples, detects speech segments via Silero VAD,
 * then runs offline SenseVoice ASR on each segment.
 */
public class SubtitleEngine {
    private static final String TAG = "SubtitleEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int PREVIEW_MIN_SAMPLES = SAMPLE_RATE / 2;
    private static final int PREVIEW_UPDATE_INTERVAL_SAMPLES = SAMPLE_RATE / 2;
    private static final int PREVIEW_MAX_SAMPLES = SAMPLE_RATE * 8;

    private OfflineRecognizer recognizer;
    private Vad vad;
    private boolean initialized = false;
    private String sourceLanguage = "ja";
    private final ArrayDeque<float[]> previewChunks = new ArrayDeque<>();
    private int previewSampleCount = 0;
    private int previewSamplesSinceLastDecode = 0;
    private String lastPreviewText = "";
    private String lastPreviewLang = "";

    public interface StatusCallback {
        void onStatus(String status);
    }

    /**
     * Result from ASR: recognized text + detected language
     */
    public static class AsrResult {
        public final String text;
        public final String lang;

        public AsrResult(String text, String lang) {
            this.text = text;
            this.lang = lang;
        }
    }

    /**
     * Preview text for the currently active speech segment.
     */
    public static class PreviewResult {
        public final String text;
        public final String lang;

        public PreviewResult(String text, String lang) {
            this.text = text;
            this.lang = lang;
        }
    }

    /**
     * Combined engine update: newly committed segments plus optional preview text.
     */
    public static class SubtitleUpdate {
        public final List<AsrResult> committedResults;
        public final PreviewResult previewResult;

        public SubtitleUpdate(List<AsrResult> committedResults, PreviewResult previewResult) {
            this.committedResults = committedResults;
            this.previewResult = previewResult;
        }
    }

    /**
     * Initialize the ASR engine.
     * sherpa-onnx accepts AssetManager + relative asset paths to load models directly from APK assets.
     * Must be called from a background thread (blocks during model load).
     */
    public void init(Context context, StatusCallback callback) {
        try {
            AssetManager assetManager = context.getAssets();

            callback.onStatus("Loading VAD model...");
            // Paths are relative to assets/ directory in the APK
            SileroVadModelConfig sileroConfig = new SileroVadModelConfig(
                    "silero-vad/silero_vad.onnx",
                    0.4f,   // threshold
                    0.3f,   // minSilenceDuration
                    0.25f,  // minSpeechDuration
                    512,    // windowSize
                    3.0f    // maxSpeechDuration
            );

            TenVadModelConfig tenConfig = new TenVadModelConfig(
                    "", 0.5f, 0.25f, 0.5f, 512, 10.0f
            );

            VadModelConfig vadConfig = new VadModelConfig(
                    sileroConfig,
                    tenConfig,
                    SAMPLE_RATE,
                    1,          // numThreads
                    "cpu",      // provider
                    false       // debug
            );

            vad = new Vad(assetManager, vadConfig);

            callback.onStatus("Loading ASR model...");
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig(
                    "sense-voice/model.int8.onnx",
                    sourceLanguage,
                    true,   // useInverseTextNormalization: enable punctuation output
                    new QnnConfig("", "", "")
            );

            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens("sense-voice/tokens.txt");
            modelConfig.setNumThreads(4);
            modelConfig.setDebug(false);

            OfflineRecognizerConfig recognizerConfig = new OfflineRecognizerConfig();
            recognizerConfig.setModelConfig(modelConfig);
            recognizerConfig.setDecodingMethod("greedy_search");

            recognizer = new OfflineRecognizer(assetManager, recognizerConfig);
            initialized = true;
            callback.onStatus("Ready");
            Log.i(TAG, "SubtitleEngine initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SubtitleEngine", e);
            callback.onStatus("Error: " + e.getMessage());
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getSourceLanguage() {
        return sourceLanguage;
    }

    /**
     * Feed audio samples to VAD. If speech segments are detected,
     * run ASR and return recognized text results with detected language.
     */
    public SubtitleUpdate processAudio(float[] samples) {
        List<AsrResult> results = new ArrayList<>();
        if (!initialized) return new SubtitleUpdate(results, null);

        vad.acceptWaveform(samples);
        if (vad.isSpeechDetected()) {
            appendPreviewSamples(samples);
            previewSamplesSinceLastDecode += samples.length;
        }

        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            AsrResult result = decodeSamples(segment.getSamples(), "ASR");
            if (result != null) {
                results.add(result);
            }
            vad.pop();
        }

        PreviewResult previewResult = null;
        if (!results.isEmpty()) {
            previewResult = clearPreviewIfNeeded();
        } else if (previewSampleCount >= PREVIEW_MIN_SAMPLES
                && previewSamplesSinceLastDecode >= PREVIEW_UPDATE_INTERVAL_SAMPLES) {
            previewResult = decodePreview();
            previewSamplesSinceLastDecode = 0;
        }

        return new SubtitleUpdate(results, previewResult);
    }

    /**
     * Flush any remaining buffered audio in VAD and process it.
     */
    public List<AsrResult> flush() {
        List<AsrResult> results = new ArrayList<>();
        if (!initialized) return results;

        vad.flush();
        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            AsrResult result = decodeSamples(segment.getSamples(), "ASR(flush)");
            if (result != null) {
                results.add(result);
            }
            vad.pop();
        }

        clearPreviewState();
        return results;
    }

    public void release() {
        clearPreviewState();
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
        if (vad != null) {
            vad.release();
            vad = null;
        }
        initialized = false;
    }

    private AsrResult decodeSamples(float[] segmentSamples, String label) {
        if (segmentSamples == null || segmentSamples.length == 0) {
            return null;
        }

        long t0 = System.currentTimeMillis();
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(segmentSamples, SAMPLE_RATE);
            recognizer.decode(stream);

            OfflineRecognizerResult result = recognizer.getResult(stream);
            long elapsed = System.currentTimeMillis() - t0;
            String text = result.getText().trim();
            String lang = result.getLang();
            float durationSec = segmentSamples.length / (float) SAMPLE_RATE;
            Log.i(TAG, label + ": lang=" + lang
                    + " duration=" + String.format("%.1f", durationSec)
                    + "s inference=" + elapsed + "ms text=\"" + text + "\"");
            if (text.isEmpty()) {
                return null;
            }
            return new AsrResult(text, lang != null ? lang : "");
        } finally {
            stream.release();
        }
    }

    private void appendPreviewSamples(float[] samples) {
        float[] copy = new float[samples.length];
        System.arraycopy(samples, 0, copy, 0, samples.length);
        previewChunks.addLast(copy);
        previewSampleCount += copy.length;

        while (previewSampleCount > PREVIEW_MAX_SAMPLES && !previewChunks.isEmpty()) {
            float[] dropped = previewChunks.removeFirst();
            previewSampleCount -= dropped.length;
        }
    }

    private PreviewResult decodePreview() {
        float[] previewSamples = buildPreviewSamples();
        AsrResult preview = decodeSamples(previewSamples, "ASR(preview)");
        if (preview == null) {
            return null;
        }

        if (preview.text.equals(lastPreviewText) && preview.lang.equals(lastPreviewLang)) {
            return null;
        }

        lastPreviewText = preview.text;
        lastPreviewLang = preview.lang;
        return new PreviewResult(preview.text, preview.lang);
    }

    private float[] buildPreviewSamples() {
        float[] allSamples = new float[previewSampleCount];
        int offset = 0;
        for (float[] chunk : previewChunks) {
            System.arraycopy(chunk, 0, allSamples, offset, chunk.length);
            offset += chunk.length;
        }
        return allSamples;
    }

    private PreviewResult clearPreviewIfNeeded() {
        boolean hadPreview = !lastPreviewText.isEmpty();
        clearPreviewState();
        return hadPreview ? new PreviewResult("", "") : null;
    }

    private void clearPreviewState() {
        previewChunks.clear();
        previewSampleCount = 0;
        previewSamplesSinceLastDecode = 0;
        lastPreviewText = "";
        lastPreviewLang = "";
    }

}
