package dev.notune.transcribe;

import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages ML Kit offline translation from source languages to Chinese.
 * Downloads translation models on first use, caches Translator instances.
 */
public class TranslationManager {
    private static final String TAG = "TranslationManager";

    private final Map<String, Translator> translators = new HashMap<>();
    private final Map<String, Boolean> modelReady = new HashMap<>();

    public interface TranslateCallback {
        void onResult(String translatedText);
        void onError(String error);
    }

    public interface DownloadCallback {
        void onSuccess();
        void onError(String error);
    }

    /**
     * Map SenseVoice language codes to ML Kit TranslateLanguage codes.
     */
    private String toMlKitLang(String senseVoiceLang) {
        if (senseVoiceLang == null) return null;
        String lang = senseVoiceLang.replace("<|", "").replace("|>", "").trim();
        switch (lang) {
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            case "en": return TranslateLanguage.ENGLISH;
            case "zh": return TranslateLanguage.CHINESE;
            case "yue": return TranslateLanguage.CHINESE;
            default: return null;
        }
    }

    /**
     * Pre-download a translation model for a specific source language → Chinese.
     * Call this before starting subtitles to avoid delay during playback.
     */
    public void downloadModel(String senseVoiceLang, DownloadCallback callback) {
        String mlKitLang = toMlKitLang(senseVoiceLang);
        if (mlKitLang == null) {
            callback.onError("Unsupported language: " + senseVoiceLang);
            return;
        }
        if (TranslateLanguage.CHINESE.equals(mlKitLang)) {
            modelReady.put(senseVoiceLang, true);
            callback.onSuccess();
            return;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLang)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();

        Translator translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        Log.i(TAG, "Downloading translation model: " + senseVoiceLang + " → zh");
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> {
                    Log.i(TAG, "Translation model ready: " + senseVoiceLang + " → zh");
                    translators.put(senseVoiceLang, translator);
                    modelReady.put(senseVoiceLang, true);
                    callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to download translation model: " + senseVoiceLang, e);
                    callback.onError("Download failed: " + e.getMessage());
                });
    }

    /**
     * Download model synchronously (blocking). For use in background threads.
     * Returns true if model is ready, false on failure.
     */
    public boolean downloadModelSync(String senseVoiceLang, long timeoutMs) {
        if (isModelReady(senseVoiceLang)) return true;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        downloadModel(senseVoiceLang, new DownloadCallback() {
            @Override
            public void onSuccess() {
                success.set(true);
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        return success.get();
    }

    public boolean isModelReady(String senseVoiceLang) {
        String lang = senseVoiceLang.replace("<|", "").replace("|>", "").trim();
        if ("zh".equals(lang) || "yue".equals(lang)) return true;
        return Boolean.TRUE.equals(modelReady.get(senseVoiceLang))
                || Boolean.TRUE.equals(modelReady.get(lang));
    }

    /**
     * Translate text from detected language to Chinese.
     * If source is already Chinese, returns the original text.
     */
    public void translate(String text, String senseVoiceLang, TranslateCallback callback) {
        String lang = senseVoiceLang.replace("<|", "").replace("|>", "").trim();

        if ("zh".equals(lang) || "yue".equals(lang)) {
            callback.onResult(text);
            return;
        }

        Translator translator = translators.get(senseVoiceLang);
        if (translator == null) translator = translators.get(lang);

        if (translator == null) {
            // Try to create on-the-fly
            String mlKitLang = toMlKitLang(lang);
            if (mlKitLang == null) {
                callback.onError("Unsupported language: " + lang);
                return;
            }
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(mlKitLang)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build();
            translator = Translation.getClient(options);
            translators.put(lang, translator);
        }

        translator.translate(text)
                .addOnSuccessListener(callback::onResult)
                .addOnFailureListener(e -> callback.onError("Translation failed: " + e.getMessage()));
    }

    /**
     * Translate synchronously (blocking). For use in background threads.
     */
    public String translateSync(String text, String senseVoiceLang, long timeoutMs) {
        String lang = senseVoiceLang.replace("<|", "").replace("|>", "").trim();
        if ("zh".equals(lang) || "yue".equals(lang)) return text;

        CountDownLatch latch = new CountDownLatch(1);
        String[] result = {text}; // fallback to original

        translate(text, lang, new TranslateCallback() {
            @Override
            public void onResult(String translatedText) {
                result[0] = translatedText;
                latch.countDown();
            }

            @Override
            public void onError(String error) {
                Log.w(TAG, "Translation error, using original: " + error);
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        return result[0];
    }

    public void release() {
        for (Translator translator : translators.values()) {
            translator.close();
        }
        translators.clear();
        modelReady.clear();
    }
}
