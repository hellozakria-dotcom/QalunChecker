package com.qalun.checker.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

/** Draws an ornamental surah title box (full line) in place of its text. */
public final class SurahBannerSpan extends ReplacementSpan {
    private final String title;
    private final Typeface font;
    private final float density;
    private int lastWidth;

    public SurahBannerSpan(String title, Typeface font, float density) {
        this.title = title;
        this.font = font;
        this.density = density;
    }

    /** The span fills the TextView line; the activity tells it the available width. */
    public void setLineWidth(int w) { lastWidth = w; }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            int h = (int) (52 * density);
            fm.ascent = fm.top = -h * 2 / 3;
            fm.descent = fm.bottom = h / 3;
        }
        return lastWidth > 0 ? lastWidth : (int) (300 * density);
    }

    @Override
    public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        float w = lastWidth > 0 ? lastWidth : 300 * density;
        float h = 44 * density;
        float cy = (top + bottom) / 2f;
        RectF box = new RectF(x + 4 * density, cy - h / 2, x + w - 4 * density, cy + h / 2);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Theme.GREEN_LIGHT);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Theme.GOLD);
        stroke.setStrokeWidth(2 * density);

        // body with pointed (arch-like) ends
        float tip = 16 * density;
        Path p = new Path();
        p.moveTo(box.left, cy);
        p.lineTo(box.left + tip, box.top);
        p.lineTo(box.right - tip, box.top);
        p.lineTo(box.right, cy);
        p.lineTo(box.right - tip, box.bottom);
        p.lineTo(box.left + tip, box.bottom);
        p.close();
        c.drawPath(p, fill);
        c.drawPath(p, stroke);
        stroke.setStrokeWidth(1 * density);
        RectF inner = new RectF(box.left + tip + 6 * density, box.top + 5 * density, box.right - tip - 6 * density, box.bottom - 5 * density);
        c.drawRect(inner, stroke);
        Paint gold = new Paint(Paint.ANTI_ALIAS_FLAG);
        gold.setColor(Theme.GOLD);
        Theme.drawStar(c, box.left + tip * 0.9f, cy, 6 * density, gold);
        Theme.drawStar(c, box.right - tip * 0.9f, cy, 6 * density, gold);

        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(font);
        t.setColor(Theme.GREEN_DARK);
        t.setTextSize(24 * density);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(title, box.centerX(), cy - (t.descent() + t.ascent()) / 2, t);
    }
}
