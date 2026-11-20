package com.qalun.checker.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;

/** Gentle synthesized chimes plus the recorded «حسبك». */
public final class Sounds {
    private static final int SR = 22050;
    private final Context ctx;
    private MediaPlayer player;
    public boolean enabled = true;

    public Sounds(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Soft low two-note tone for a mistake. */
    public void mistake() { if (enabled) play(bell(new double[]{392, 330}, 0.16, 0.35)); }

    /** Light bell when an ayah is completed without mistakes. */
    public void ayahDone() { if (enabled) play(bell(new double[]{784}, 0.25, 0.18)); }

    /** Rising three-note chime for finishing. */
    public void success() { if (enabled) play(bell(new double[]{523, 659, 784}, 0.22, 0.3)); }

    public void hasbuk() {
        try {
            release();
            player = new MediaPlayer();
            AssetFileDescriptor fd = ctx.getAssets().openFd("sounds/hasbuk.m4a");
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            player.prepare();
            player.start();
        } catch (Exception ignored) { }
    }

    public void release() {
        if (player != null) { player.release(); player = null; }
    }

    /** Notes played one after another, each a bell-like sine with harmonics and decay. */
    private static short[] bell(double[] freqs, double noteSec, double volume) {
        int per = (int) (noteSec * SR);
        int tail = (int) (0.35 * SR);
        short[] out = new short[per * freqs.length + tail];
        for (int k = 0; k < freqs.length; k++) {
            double f = freqs[k];
            int len = per + tail;
            for (int i = 0; i < len && k * per + i < out.length; i++) {
                double t = i / (double) SR;
                double env = Math.exp(-t * 6) * Math.min(1, i / (0.005 * SR));
                double v = Math.sin(2 * Math.PI * f * t) + 0.35 * Math.sin(2 * Math.PI * 2 * f * t) + 0.12 * Math.sin(2 * Math.PI * 3 * f * t);
                int idx = k * per + i;
                int s = out[idx] + (int) (v * env * volume * 0.6 * Short.MAX_VALUE);
                out[idx] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
            }
        }
        return out;
    }

    private static void play(short[] pcm) {
        new Thread(() -> {
            try {
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                t.write(pcm, 0, pcm.length);
                t.play();
                Thread.sleep(pcm.length * 1000L / SR + 100);
                t.release();
            } catch (Throwable ignored) { }
        }).start();
    }
}
