package com.qalun.checker;

import android.content.Context;
import android.graphics.Typeface;

import com.qalun.checker.core.QuranText;

import java.io.IOException;

/** Loads the Qalun text and font once per process. */
final class Data {
    private static QuranText text;
    private static Typeface font;

    private Data() {}

    static synchronized QuranText text(Context ctx) {
        if (text == null) {
            try {
                text = QuranText.load(ctx.getAssets().open("qalun.tsv"), ctx.getAssets().open("suras.tsv"));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read Quran text from assets", e);
            }
        }
        return text;
    }

    static synchronized Typeface font(Context ctx) {
        if (font == null) {
            try {
                font = Typeface.createFromAsset(ctx.getAssets(), "qaloon.ttf");
            } catch (RuntimeException e) {
                font = Typeface.DEFAULT;
            }
        }
        return font;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
