package com.qalun.checker.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/** Android/Google speech recogniser in Arabic, restarted after every utterance. Needs internet. */
public final class GoogleSpeechSource implements SpeechSource {
    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean listening;
    private String lastPartial = "";
    private int networkRetries;

    public GoogleSpeechSource(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public static boolean available(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    @Override
    public void start() {
        listening = true;
        networkRetries = 0;
        listenOnce();
    }

    @Override
    public void stop() {
        listening = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        flushPartial();
    }

    @Override
    public void destroy() { stop(); }

    private void flushPartial() {
        if (!lastPartial.isEmpty()) { String p = lastPartial; lastPartial = ""; listener.onFinal(p); }
    }

    private void listenOnce() {
        if (!listening) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(rl);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        lastPartial = "";
        recognizer.startListening(i);
    }

    private void again(long ms) {
        if (listening) handler.postDelayed(this::listenOnce, ms);
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty()) ? null : r.get(0);
    }

    private final RecognitionListener rl = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            String t = first(partial);
            if (t == null || t.trim().isEmpty()) return;
            lastPartial = t;
            listener.onPartial(t);
        }

        @Override
        public void onResults(Bundle results) {
            String t = first(results);
            if (t == null || t.trim().isEmpty()) t = lastPartial;
            lastPartial = "";
            networkRetries = 0;
            if (t != null && !t.trim().isEmpty()) listener.onFinal(t);
            again(50);
        }

        @Override
        public void onError(int error) {
            flushPartial();
            if (!listening) return;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    listening = false;
                    listener.onError("يلزم إذن الميكروفون");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    if (++networkRetries > 3) { listening = false; listener.onError("تحقق من اتصال الإنترنت"); }
                    else again(1500);
                    break;
                case 12:
                case 13:
                    listening = false;
                    listener.onError("العربية غير متوفرة في خدمة Google على هذا الجهاز");
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                    again(400);
                    break;
                default:
                    again(100);
            }
        }
    };
}
