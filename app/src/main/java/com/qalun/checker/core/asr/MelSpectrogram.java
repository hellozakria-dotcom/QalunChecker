package com.qalun.checker.core.asr;

/**
 * Whisper log-mel features (80 mels x 3000 frames, 16 kHz, 30 s window), matching
 * HuggingFace WhisperFeatureExtractor: periodic Hann window, n_fft 400, hop 160,
 * centred reflect-padded STFT, power spectrum, Slaney mel filters, log10,
 * clamp to (max - 8), then (x + 4) / 4.
 */
public final class MelSpectrogram {
    public static final int SAMPLE_RATE = 16000, N_FFT = 400, HOP = 160, N_MELS = 80;
    public static final int N_SAMPLES = 30 * SAMPLE_RATE, N_FRAMES = 3000, N_FREQ = N_FFT / 2 + 1;

    private final float[] filters; // [N_MELS][N_FREQ] row-major
    private final double[] window = new double[N_FFT];
    private final double[] cos = new double[N_FREQ * N_FFT], sin = new double[N_FREQ * N_FFT];

    public MelSpectrogram(float[] melFilters) {
        if (melFilters.length != N_MELS * N_FREQ) throw new IllegalArgumentException("mel filters must be 80x201");
        this.filters = melFilters;
        for (int n = 0; n < N_FFT; n++) window[n] = 0.5 - 0.5 * Math.cos(2 * Math.PI * n / N_FFT);
        for (int k = 0; k < N_FREQ; k++)
            for (int n = 0; n < N_FFT; n++) {
                double a = 2 * Math.PI * k * n / N_FFT;
                cos[k * N_FFT + n] = Math.cos(a);
                sin[k * N_FFT + n] = Math.sin(a);
            }
    }

    /** Returns features as [N_MELS * N_FRAMES] (mel-major), ready for input_features [1,80,3000]. */
    public float[] compute(float[] audio) {
        int len = Math.min(audio.length, N_SAMPLES);
        int half = N_FFT / 2;
        // padded signal value at position p (0..N_SAMPLES-1), with reflect padding at both ends
        double[] logMel = new double[N_MELS * N_FRAMES];
        // frames that touch real audio; the rest see only zeros
        int activeFrames = Math.min(N_FRAMES, (len + half) / HOP + 2);
        double[] frame = new double[N_FFT];
        double[] power = new double[N_FREQ];
        double maxVal = -Double.MAX_VALUE;
        for (int t = 0; t < N_FRAMES; t++) {
            if (t < activeFrames || t >= N_FRAMES - 3) {
                int start = t * HOP - half;
                for (int n = 0; n < N_FFT; n++) {
                    int p = start + n;
                    if (p < 0) p = -p;                                   // reflect at start
                    else if (p >= N_SAMPLES) p = 2 * (N_SAMPLES - 1) - p; // reflect at end
                    double v = p < len ? audio[p] : 0.0;
                    frame[n] = v * window[n];
                }
                for (int k = 0; k < N_FREQ; k++) {
                    double re = 0, im = 0;
                    int base = k * N_FFT;
                    for (int n = 0; n < N_FFT; n++) {
                        double f = frame[n];
                        if (f == 0) continue;
                        re += f * cos[base + n];
                        im -= f * sin[base + n];
                    }
                    power[k] = re * re + im * im;
                }
            } else {
                java.util.Arrays.fill(power, 0.0);
            }
            for (int m = 0; m < N_MELS; m++) {
                double s = 0;
                int base = m * N_FREQ;
                for (int k = 0; k < N_FREQ; k++) s += filters[base + k] * power[k];
                double l = Math.log10(Math.max(s, 1e-10));
                logMel[m * N_FRAMES + t] = l;
                if (l > maxVal) maxVal = l;
            }
        }
        float[] out = new float[N_MELS * N_FRAMES];
        double floor = maxVal - 8.0;
        for (int i = 0; i < out.length; i++) out[i] = (float) ((Math.max(logMel[i], floor) + 4.0) / 4.0);
        return out;
    }
}
