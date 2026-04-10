package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automated test for live subtitle ASR + ML Kit translation pipeline.
 * Runs on device, reads a test WAV file, runs subtitle ASR, then ML Kit en→zh.
 * Results printed to logcat with tag "PipelineTest".
 *
 * Run with: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.notune.transcribe.SubtitlePipelineTest
 */
@RunWith(AndroidJUnit4.class)
public class SubtitlePipelineTest {
    private static final String TAG = "PipelineTest";

    @Test
    public void testSubtitlePipelineAndMlKit() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Step 1: Load test audio
        Log.i(TAG, "=== Loading test audio ===");
        float[] samples = loadWav(context, "test_ja.wav");
        Log.i(TAG, "Audio loaded: " + samples.length + " samples (" + String.format("%.1f", samples.length / 16000.0f) + "s)");

        // Step 2: Initialize subtitle ASR
        Log.i(TAG, "=== Initializing Subtitle ASR ===");
        SubtitleEngine engine = new SubtitleEngine();
        engine.init(context, status -> Log.i(TAG, "ASR status: " + status));
        assert engine.isInitialized() : "SubtitleEngine failed to initialize";
        Log.i(TAG, "ASR ready. lang=" + engine.getSourceLanguage());

        // Step 3: Run ASR on full audio (feed all at once, then flush)
        Log.i(TAG, "=== Running Subtitle ASR ===");
        long t0 = System.currentTimeMillis();

        // Feed audio in 512-sample chunks (like real-time)
        int chunkSize = 512;
        for (int offset = 0; offset < samples.length; offset += chunkSize) {
            int len = Math.min(chunkSize, samples.length - offset);
            float[] chunk = new float[len];
            System.arraycopy(samples, offset, chunk, 0, len);

            SubtitleEngine.SubtitleUpdate update = engine.processAudio(chunk);
            if (update.previewResult != null && update.previewResult.text != null
                    && !update.previewResult.text.isEmpty()) {
                Log.i(TAG, "Preview: lang=" + update.previewResult.lang
                        + " text=\"" + update.previewResult.text + "\"");
            }
            for (SubtitleEngine.AsrResult r : update.committedResults) {
                Log.i(TAG, "ASR result: lang=" + r.lang + " text=\"" + r.text + "\"");
            }
        }

        // Flush remaining
        List<SubtitleEngine.AsrResult> flushed = engine.flush();
        for (SubtitleEngine.AsrResult r : flushed) {
            Log.i(TAG, "ASR result (flush): lang=" + r.lang + " text=\"" + r.text + "\"");
        }

        long asrTime = System.currentTimeMillis() - t0;
        Log.i(TAG, "ASR total time: " + asrTime + "ms");

        // Step 4: Initialize ML Kit translation (en→zh)
        Log.i(TAG, "=== Initializing ML Kit Translation ===");
        TranslationManager translator = new TranslationManager();
        boolean modelReady = translator.downloadModelSync("en", 30000);
        Log.i(TAG, "Translation model ready: " + modelReady);

        // Step 5: Translate each ASR result
        Log.i(TAG, "=== Running Translation ===");
        String[] testSentences = {
                "She's a human child.",
                "Is your brother not human?",
                "I'm listening to you.",
                "In this forest.",
                "I'm sorry.",
                "This can be used as a human."
        };

        for (String en : testSentences) {
            CountDownLatch latch = new CountDownLatch(1);
            final String[] zhResult = {""};

            translator.translate(en, "en", new TranslationManager.TranslateCallback() {
                @Override
                public void onResult(String translated) {
                    zhResult[0] = translated;
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    zhResult[0] = "ERROR: " + error;
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            Log.i(TAG, "Translate: \"" + en + "\" → \"" + zhResult[0] + "\"");
        }

        // Cleanup
        engine.release();
        translator.release();
        Log.i(TAG, "=== Test Complete ===");
    }

    /**
     * Load a 16-bit mono PCM WAV file from assets, return float samples.
     */
    private float[] loadWav(Context context, String assetName) throws Exception {
        try (InputStream is = context.getAssets().open(assetName)) {
            byte[] allBytes = readAllBytes(is);
            // Skip 44-byte WAV header
            int dataStart = 44;
            int numSamples = (allBytes.length - dataStart) / 2;
            float[] samples = new float[numSamples];
            ByteBuffer buf = ByteBuffer.wrap(allBytes, dataStart, allBytes.length - dataStart).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numSamples; i++) {
                samples[i] = buf.getShort() / 32768.0f;
            }
            return samples;
        }
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }
}
