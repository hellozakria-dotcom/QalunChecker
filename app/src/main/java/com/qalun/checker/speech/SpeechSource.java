package com.qalun.checker.speech;

/** A source of recognised speech: Google's recogniser or the offline Quran model. */
public interface SpeechSource {

    interface Listener {
        /** Text so far for the current utterance (replaces the previous partial). */
        void onPartial(String text);
        /** Final text for an utterance. */
        void onFinal(String text);
        /** Short status for the user, e.g. "loading model". Empty string clears it. */
        void onStatus(String status);
        /** Fatal problem; the source has stopped. */
        void onError(String message);
    }

    void start();

    /** Stops listening; any speech already captured is still delivered via onFinal. */
    void stop();

    void destroy();
}
