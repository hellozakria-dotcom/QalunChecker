package com.qalun.checker.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Colours and hand-drawn Islamic ornaments (no image assets needed). */
public final class Theme {
    private Theme() {}

    public static final int GREEN_DARK = Color.parseColor("#0B3D2E");
    public static final int GREEN = Color.parseColor("#14532D");
    public static final int GREEN_LIGHT = Color.parseColor("#E7F3EC");
    public static final int GOLD = Color.parseColor("#C9A227");
    public static final int GOLD_LIGHT = Color.parseColor("#E9D79A");
    public static final int PARCHMENT = Color.parseColor("#FBF6E9");
    public static final int PARCHMENT_DARK = Color.parseColor("#F3EAD3");
    public static final int INK = Color.parseColor("#1F1A12");
    public static final int CORRECT = Color.parseColor("#1B7F3B");
    public static final int WRONG = Color.parseColor("#B3261E");
    public static final int WRONG_BG = Color.parseColor("#F9D7D3");
    public static final int MISSED = Color.parseColor("#C2570C");
    public static final int MISSED_BG = Color.parseColor("#FBE3C8");
    public static final int CURRENT_BG = Color.parseColor("#F4E3A1");

    /** Green gradient header background. */
    public static Drawable headerBackground() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{GREEN_DARK, GREEN});
        return g;
    }

    public static GradientDrawable rounded(int fill, int stroke, float radius, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /** Mushaf page: parchment with a double gold frame and corner ornaments. */
    public static final class PageFrame extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                orn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;

        public PageFrame(float density) {
            d = density;
            line.setStyle(Paint.Style.STROKE);
            line.setColor(GOLD);
            orn.setColor(GOLD);
        }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, PARCHMENT, PARCHMENT_DARK, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            float m1 = 6 * d, m2 = 11 * d;
            line.setStrokeWidth(2.5f * d);
            c.drawRect(r.left + m1, r.top + m1, r.right - m1, r.bottom - m1, line);
            line.setStrokeWidth(1f * d);
            c.drawRect(r.left + m2, r.top + m2, r.right - m2, r.bottom - m2, line);
            float s = 7 * d;
            star(c, r.left + m1, r.top + m1, s);
            star(c, r.right - m1, r.top + m1, s);
            star(c, r.left + m1, r.bottom - m1, s);
            star(c, r.right - m1, r.bottom - m1, s);
        }

        private void star(Canvas c, float cx, float cy, float s) { drawStar(c, cx, cy, s, orn); }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** Eight-pointed star (two overlapping squares), the classic Islamic motif. */
    public static void drawStar(Canvas c, float cx, float cy, float r, Paint p) {
        Path path = new Path();
        for (int k = 0; k < 16; k++) {
            double a = Math.PI / 8 * k - Math.PI / 2;
            float rr = (k % 2 == 0) ? r : r * 0.72f;
            float x = cx + (float) (rr * Math.cos(a)), y = cy + (float) (rr * Math.sin(a));
            if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, p);
    }

    /** Eight-pointed star outline with a number inside (surah list). */
    public static final class StarNumber extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "";

        public StarNumber(float density) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * density);
            p.setColor(GOLD);
            t.setColor(GREEN_DARK);
            t.setTextAlign(Paint.Align.CENTER);
            t.setFakeBoldText(true);
        }

        public void setText(String s) { text = s; invalidateSelf(); }

        @Override
        public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            float cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) / 2 - p.getStrokeWidth();
            drawStar(c, cx, cy, rad, p);
            t.setTextSize(rad * (text.length() > 2 ? 0.62f : 0.75f));
            c.drawText(text, cx, cy - (t.descent() + t.ascent()) / 2, t);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
