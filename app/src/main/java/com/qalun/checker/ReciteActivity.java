package com.qalun.checker;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qalun.checker.core.ArabicNormalizer;
import com.qalun.checker.core.QuranText;
import com.qalun.checker.core.RecitationTracker;
import com.qalun.checker.core.RecitationTracker.Mistake;
import com.qalun.checker.core.RecitationTracker.State;
import com.qalun.checker.core.RecitationTracker.Word;
import com.qalun.checker.speech.GoogleSpeechSource;
import com.qalun.checker.speech.SpeechSource;
import com.qalun.checker.speech.WhisperSpeechSource;
import com.qalun.checker.ui.Sounds;
import com.qalun.checker.ui.SurahBannerSpan;
import com.qalun.checker.ui.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mushaf page view (604 pages) that listens and marks each word as you recite. */
public class ReciteActivity extends Activity {

    public static final String EXTRA_SURA = "sura";
    public static final String EXTRA_PAGE = "page";
    private static final int REQ_MIC = 7, PAGES = 604;
    private static final String BASMALA = "بِسْمِ اِ۬للَّهِ اِ۬لرَّحْمَٰنِ اِ۬لرَّحِيمِ";

    private QuranText text;
    private List<QuranText.Ayah> all;
    private RecitationTracker tracker;
    private int[] wordPage;                 // page of each word
    private int[] pageFirstWord = new int[PAGES + 2];
    private final Map<Integer, List<QuranText.Ayah>> pageAyat = new HashMap<>();
    private final Map<Integer, String> suraNames = new HashMap<>();
    private int[] wordStart, wordEnd;       // offsets on the current page (-1 = not shown)
    private Object[][] wordSpans;

    private int page = 1;
    private int sessionStart = 0, sessionEnd;   // the surah being recited
    private int committedPos = 0, shownPos = 0;
    private boolean summaryShown;

    private ScrollView scroll;
    private TextView quranView, pageNumber, statusView, bannerView;
    private View micRing;
    private TextView micButton;
    private ObjectAnimator pulse;

    private SpeechSource source;
    private boolean listening, useOffline = true, hideMode = false;
    private Sounds sounds;
    private SharedPreferences prefs;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        useOffline = prefs.getBoolean("offline", true);
        hideMode = prefs.getBoolean("hide", false);
        sounds = new Sounds(this);
        sounds.enabled = prefs.getBoolean("sound", true);

        text = Data.text(this);
        all = text.all();
        for (QuranText.Sura s : text.suras()) suraNames.put(s.number, s.nameAr);
        tracker = new RecitationTracker(all);
        indexPages();

        int sura = getIntent().getIntExtra(EXTRA_SURA, 0);
        int startWord;
        if (sura > 0) {
            startWord = firstWordOfSura(sura);
            sessionEnd = firstWordOfSura(sura + 1);
        } else {
            int p = Math.max(1, Math.min(PAGES, getIntent().getIntExtra(EXTRA_PAGE, 1)));
            startWord = pageFirstWord[p];
            Word w = tracker.words().get(startWord);
            sessionEnd = firstWordOfSura(w.sura + 1);
        }
        sessionStart = startWord;
        tracker.restartAt(startWord);
        committedPos = shownPos = startWord;
        page = wordPage[startWord];

        if (getActionBar() != null) getActionBar().setBackgroundDrawable(Theme.headerBackground());
        buildUi();
        showPage(page, 0);
    }

    private void indexPages() {
        List<Word> words = tracker.words();
        wordPage = new int[words.size()];
        java.util.Arrays.fill(pageFirstWord, -1);
        Map<String, Integer> ayahPage = new HashMap<>();
        for (QuranText.Ayah a : all) {
            int p = QuranText.pageOf(a);
            ayahPage.put(a.sura + ":" + a.aya, p);
            List<QuranText.Ayah> l = pageAyat.get(p);
            if (l == null) { l = new ArrayList<>(); pageAyat.put(p, l); }
            l.add(a);
        }
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            Integer p = ayahPage.get(w.sura + ":" + w.aya);
            wordPage[i] = p == null ? 1 : p;
            if (pageFirstWord[wordPage[i]] < 0) pageFirstWord[wordPage[i]] = i;
        }
        pageFirstWord[PAGES + 1] = words.size();
        for (int p = PAGES; p >= 1; p--) if (pageFirstWord[p] < 0) pageFirstWord[p] = pageFirstWord[p + 1];
        wordStart = new int[words.size()];
        wordEnd = new int[words.size()];
        java.util.Arrays.fill(wordStart, -1);
        wordSpans = new Object[words.size()][];
    }

    private int firstWordOfSura(int sura) {
        if (sura > 114) return tracker.words().size();
        List<Word> words = tracker.words();
        int lo = 0, hi = words.size();
        while (lo < hi) { // words are in mushaf order
            int mid = (lo + hi) >>> 1;
            if (words.get(mid).sura < sura) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening) stopListening(false);
        prefs.edit().putInt("last_page", page).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (source != null) { source.destroy(); source = null; }
        sounds.release();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Theme.PARCHMENT_DARK);

        // the mushaf page
        FrameLayout pageBox = new FrameLayout(this);
        pageBox.setBackground(new Theme.PageFrame(d));
        int m = Data.dp(this, 8);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        pbl.setMargins(m, m, m, 0);
        root.addView(pageBox, pbl);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        quranView = new TextView(this);
        quranView.setTypeface(Data.font(this));
        quranView.setTextSize(26);
        quranView.setTextColor(Theme.INK);
        quranView.setLineSpacing(Data.dp(this, 9), 1f);
        quranView.setTextDirection(View.TEXT_DIRECTION_RTL);
        quranView.setHighlightColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 26) quranView.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        int pad = Data.dp(this, 22);
        quranView.setPadding(pad, Data.dp(this, 20), pad, Data.dp(this, 8));
        quranView.setMovementMethod(LinkMovementMethod.getInstance());
        scroll.addView(quranView);
        inner.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pageNumber = new TextView(this);
        pageNumber.setGravity(Gravity.CENTER);
        pageNumber.setTextColor(Theme.GREEN_DARK);
        pageNumber.setTextSize(15);
        pageNumber.setTypeface(Data.font(this));
        pageNumber.setPadding(0, 0, 0, Data.dp(this, 16));
        inner.addView(pageNumber);
        pageBox.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5 && Math.abs(vx) > 700) {
                    // Arabic books: the next page is on the left, so swipe to the right
                    if (vx > 0) showPage(page + 1, +1); else showPage(page - 1, -1);
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener swipe = (v, ev) -> { gd.onTouchEvent(ev); return false; };
        quranView.setOnTouchListener(swipe);
        scroll.setOnTouchListener(swipe);

        // mistake banner
        bannerView = new TextView(this);
        bannerView.setBackground(Theme.rounded(Theme.WRONG_BG, Theme.WRONG, Data.dp(this, 14), Data.dp(this, 1)));
        bannerView.setTextColor(Theme.WRONG);
        bannerView.setTextSize(15);
        int bp = Data.dp(this, 12);
        bannerView.setPadding(bp, Data.dp(this, 8), bp, Data.dp(this, 8));
        bannerView.setVisibility(View.GONE);
        bannerView.setOnClickListener(v -> showMistakeList());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(m, m, m, 0);
        root.addView(bannerView, bl);

        // bottom bar: stats + round mic button
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Theme.headerBackground());
        bar.setPadding(Data.dp(this, 16), Data.dp(this, 10), Data.dp(this, 16), Data.dp(this, 10));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15);
        bar.addView(statusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout micBox = new FrameLayout(this);
        int size = Data.dp(this, 64);
        micRing = new View(this);
        micRing.setBackground(Theme.rounded(Color.TRANSPARENT, Theme.GOLD_LIGHT, size, Data.dp(this, 3)));
        micBox.addView(micRing, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        micButton = new TextView(this);
        micButton.setGravity(Gravity.CENTER);
        micButton.setTextSize(24);
        micButton.setOnClickListener(v -> { if (listening) stopListening(true); else startListening(); });
        int inner2 = Data.dp(this, 54);
        micBox.addView(micButton, new FrameLayout.LayoutParams(inner2, inner2, Gravity.CENTER));
        bar.addView(micBox, new LinearLayout.LayoutParams(Data.dp(this, 72), Data.dp(this, 72)));
        root.addView(bar);

        pulse = ObjectAnimator.ofPropertyValuesHolder(micRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);

        setContentView(root);
        updateMicButton();
        updateStatus();
    }

    private void updateMicButton() {
        micButton.setText(listening ? "■" : "🎙");
        micButton.setTextColor(Color.WHITE);
        micButton.setBackground(Theme.rounded(listening ? Theme.WRONG : Theme.GOLD, Theme.GOLD_LIGHT,
                Data.dp(this, 27), Data.dp(this, 2)));
        if (listening) pulse.start(); else { pulse.cancel(); micRing.setScaleX(1f); micRing.setScaleY(1f); micRing.setAlpha(1f); }
    }

    private void updateStatus() {
        int ok = 0, bad = 0, miss = 0;
        List<Word> words = tracker.words();
        for (int i = sessionStart; i < Math.min(sessionEnd, tracker.position()); i++) {
            State s = words.get(i).state;
            if (s == State.CORRECT) ok++; else if (s == State.WRONG) bad++; else if (s == State.MISSED) miss++;
        }
        statusView.setText("✓ " + ok + "    ✗ " + bad + "    ⊘ " + miss);
    }

    // ---------------------------------------------------------------- pages

    /** @param dir +1 next, -1 previous, 0 no animation */
    private void showPage(int p, int dir) {
        if (p < 1 || p > PAGES) return;
        if (dir == 0) { renderPage(p); return; }
        float w = quranView.getWidth();
        quranView.animate().translationX(dir > 0 ? w : -w).alpha(0f).setDuration(160).withEndAction(() -> {
            renderPage(p);
            quranView.setTranslationX(dir > 0 ? -w : w);
            quranView.animate().translationX(0).alpha(1f).setDuration(200).start();
        }).start();
    }

    private void renderPage(int p) {
        // forget offsets of the old page
        for (int i = pageFirstWord[page]; i < pageFirstWord[page + 1]; i++) { wordStart[i] = -1; wordSpans[i] = null; }
        page = p;
        float d = getResources().getDisplayMetrics().density;
        int lineWidth = getResources().getDisplayMetrics().widthPixels - Data.dp(this, 16) - Data.dp(this, 44);
        Map<String, Integer> idx = new HashMap<>();
        List<Word> words = tracker.words();
        for (int i = pageFirstWord[p]; i < pageFirstWord[p + 1]; i++) {
            Word w = words.get(i);
            String k = w.sura + ":" + w.aya;
            if (!idx.containsKey(k)) idx.put(k, i);
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<QuranText.Ayah> ayat = pageAyat.get(p);
        if (ayat == null) ayat = new ArrayList<>();
        int wi = -1;
        for (QuranText.Ayah a : ayat) {
            if (a.aya == 1) {
                if (sb.length() > 0) sb.append('\n');
                int bs = sb.length();
                sb.append("￼");
                SurahBannerSpan banner = new SurahBannerSpan("سُورَةُ " + suraNames.get(a.sura), Data.font(this), d);
                banner.setLineWidth(lineWidth);
                sb.setSpan(banner, bs, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append('\n');
                if (a.sura != 9) {
                    int s0 = sb.length();
                    sb.append(BASMALA);
                    sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Theme.GREEN_DARK), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append('\n');
                }
            }
            Integer first = idx.get(a.sura + ":" + a.aya);
            wi = first == null ? -1 : first;
            for (String raw : ArabicNormalizer.splitWords(a.text)) {
                if (ArabicNormalizer.normalize(raw).isEmpty() || wi < 0) {
                    sb.append(raw).append(' ');
                    continue;
                }
                int start = sb.length();
                sb.append(raw);
                wordStart[wi] = start;
                wordEnd[wi] = sb.length();
                final int index = wi;
                sb.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) { onWordTapped(index); }
                    @Override public void updateDrawState(TextPaint ds) { }
                }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                wi++;
                sb.append(' ');
            }
            int n0 = sb.length();
            sb.append(arabicDigits(a.aya));
            sb.setSpan(new ForegroundColorSpan(Theme.GOLD), n0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.9f), n0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(' ');
        }
        quranView.setText(sb, TextView.BufferType.SPANNABLE);
        pageNumber.setText("﴿ " + arabicDigits(p) + " ﴾");
        Word firstWord = words.get(Math.min(pageFirstWord[p], words.size() - 1));
        setTitle("سورة " + plain(suraNames.get(firstWord.sura)) + "  ·  صفحة " + p);
        refreshWords(pageFirstWord[p], pageFirstWord[p + 1]);
        scroll.scrollTo(0, 0);
    }

    private static String plain(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (c < 0x064B || c > 0x065F) b.append(c);
        return b.toString();
    }

    private static String arabicDigits(int n) {
        StringBuilder b = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) b.append((char) ('٠' + (c - '0')));
        return b.toString();
    }

    /** Re-colour words [from, to) that are on the current page. */
    private void refreshWords(int from, int to) {
        CharSequence cs = quranView.getText();
        if (!(cs instanceof Spannable)) return;
        Spannable sp = (Spannable) cs;
        List<Word> words = tracker.words();
        int cur = tracker.position();
        from = Math.max(from, pageFirstWord[page]);
        to = Math.min(to, pageFirstWord[page + 1]);
        for (int i = from; i < to; i++) {
            if (wordStart[i] < 0) continue;
            if (wordSpans[i] != null) for (Object o : wordSpans[i]) sp.removeSpan(o);
            Word w = words.get(i);
            // while a phrase is still being recited only progress (green) is shown;
            // mistakes appear once the phrase is final
            State shown = (i >= committedPos && w.state != State.CORRECT) ? State.PENDING : w.state;
            List<Object> spans = new ArrayList<>();
            int fg;
            switch (shown) {
                case CORRECT: fg = Theme.CORRECT; break;
                case WRONG: fg = Theme.WRONG; spans.add(new BackgroundColorSpan(Theme.WRONG_BG)); break;
                case MISSED: fg = Theme.MISSED; spans.add(new BackgroundColorSpan(Theme.MISSED_BG)); spans.add(new StrikethroughSpan()); break;
                default: fg = (hideMode && i >= cur) ? Color.TRANSPARENT : Theme.INK;
            }
            if (i == cur && listening) spans.add(new BackgroundColorSpan(Theme.CURRENT_BG));
            spans.add(new ForegroundColorSpan(fg));
            for (Object o : spans) sp.setSpan(o, wordStart[i], wordEnd[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wordSpans[i] = spans.toArray();
        }
    }

    private void scrollToCurrent() {
        Layout layout = quranView.getLayout();
        int cur = tracker.position();
        if (layout == null || cur >= tracker.words().size() || wordPage[cur] != page || wordStart[cur] < 0) return;
        int line = layout.getLineForOffset(wordStart[cur]);
        int y = layout.getLineTop(line) - scroll.getHeight() / 3;
        scroll.smoothScrollTo(0, Math.max(0, y));
    }

    // -------------------------------------------------------------- tracking

    private void applyResult(String said, boolean isFinal) {
        int before = shownPos, oldCommitted = committedPos;
        List<Mistake> mistakes = tracker.onResult(said, isFinal);
        int after = tracker.position();
        if (isFinal) committedPos = after;
        shownPos = after;
        int next = Math.min(after, tracker.words().size() - 1);
        if (isFinal && wordPage[next] != page && after > oldCommitted) {
            refreshWords(Math.min(oldCommitted, before), Math.max(before, after) + 1);
            showPage(wordPage[next], wordPage[next] > page ? +1 : -1); // reciter moved on: turn the page
        } else {
            refreshWords(Math.min(oldCommitted, before), Math.max(before, after) + 1);
            if (after != before) scrollToCurrent();
        }
        updateStatus();
        if (!mistakes.isEmpty()) alertMistakes(mistakes);
        else if (isFinal && after > oldCommitted && completedCleanAyah(oldCommitted, after)) sounds.ayahDone();

        if (isFinal && !summaryShown && after >= sessionEnd) {
            summaryShown = true;
            stopListening(false);
            sounds.success();
            showSummary();
            quranView.postDelayed(sounds::hasbuk, 900);
        }
    }

    /** True if the newly committed words finish an ayah and that ayah has no mistakes. */
    private boolean completedCleanAyah(int from, int to) {
        List<Word> words = tracker.words();
        for (int k = from; k < to; k++) {
            boolean last = k + 1 >= words.size() || words.get(k + 1).aya != words.get(k).aya || words.get(k + 1).sura != words.get(k).sura;
            if (!last) continue;
            int s = k;
            while (s > 0 && words.get(s - 1).aya == words.get(k).aya && words.get(s - 1).sura == words.get(k).sura) s--;
            boolean clean = true;
            for (int j = s; j <= k; j++) if (words.get(j).state != State.CORRECT) { clean = false; break; }
            if (clean) return true;
        }
        return false;
    }

    private void alertMistakes(List<Mistake> mistakes) {
        StringBuilder sb = new StringBuilder();
        for (Mistake m : mistakes) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(m.describe());
        }
        bannerView.setText(sb.toString());
        bannerView.setVisibility(View.VISIBLE);
        bannerView.setAlpha(0f);
        bannerView.animate().alpha(1f).setDuration(250).start();
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            else vib.vibrate(120);
        }
        sounds.mistake();
    }

    private void onWordTapped(int idx) {
        Word w = tracker.words().get(idx);
        StringBuilder msg = new StringBuilder("«" + w.display + "» — " + plain(suraNames.get(w.sura)) + " " + w.aya + "\n\n");
        if (w.state == State.WRONG && idx < committedPos) {
            msg.append("خطأ في هذه الكلمة");
            if (w.heard != null) msg.append("\nسُمِع: ").append(w.heard);
            for (String n : w.letterNotes) msg.append("\n • ").append(n);
        } else if (w.state == State.MISSED && idx < committedPos) {
            msg.append("هذه الكلمة تُركت.");
        } else if (w.state == State.CORRECT) {
            msg.append("قُرئت صحيحة ✓");
        } else {
            msg.append("لم تُقرأ بعد.");
        }
        new AlertDialog.Builder(this)
                .setMessage(msg.toString())
                .setPositiveButton("ابدأ التسميع من هنا", (dlg, which) -> restartAt(idx))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void restartAt(int idx) {
        boolean was = listening;
        if (was) stopListening(false);
        tracker.restartAt(idx);
        committedPos = shownPos = idx;
        sessionStart = idx;
        sessionEnd = firstWordOfSura(tracker.words().get(idx).sura + 1);
        summaryShown = false;
        bannerView.setVisibility(View.GONE);
        if (wordPage[idx] != page) showPage(wordPage[idx], 0);
        else refreshWords(pageFirstWord[page], pageFirstWord[page + 1]);
        updateStatus();
        if (was) startListening();
    }

    private void showMistakeList() {
        final List<Word> bad = new ArrayList<>();
        List<Word> words = tracker.words();
        for (int i = sessionStart; i < Math.min(committedPos, words.size()); i++) {
            Word w = words.get(i);
            if (w.state == State.WRONG || w.state == State.MISSED) bad.add(w);
        }
        if (bad.isEmpty()) {
            Toast.makeText(this, "لا توجد أخطاء — بارك الله فيك", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[bad.size()];
        for (int i = 0; i < bad.size(); i++) {
            Word w = bad.get(i);
            items[i] = "آية " + w.aya + ": " + w.display + (w.state == State.MISSED ? " — متروكة" : " — خطأ" + (w.heard != null ? " (سُمِع: " + w.heard + ")" : ""));
        }
        new AlertDialog.Builder(this)
                .setTitle("الأخطاء (" + bad.size() + ")")
                .setItems(items, (dlg, which) -> {
                    Word w = bad.get(which);
                    if (wordPage[w.index] != page) showPage(wordPage[w.index], 0);
                    onWordTapped(w.index);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showSummary() {
        int ok = 0, bad = 0, miss = 0;
        List<Word> words = tracker.words();
        for (int i = sessionStart; i < sessionEnd; i++) {
            State s = words.get(i).state;
            if (s == State.CORRECT) ok++; else if (s == State.WRONG) bad++; else if (s == State.MISSED) miss++;
        }
        int total = sessionEnd - sessionStart;
        int pct = total == 0 ? 0 : Math.round(100f * ok / total);
        new AlertDialog.Builder(this)
                .setTitle("خُتمت السورة — " + pct + "٪")
                .setMessage("صحيحة: " + ok + " من " + total + "\nأخطاء: " + bad + "\nمتروكة: " + miss)
                .setPositiveButton("عرض الأخطاء", (dlg, w) -> showMistakeList())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ---------------------------------------------------------- speech input

    private final SpeechSource.Listener speechListener = new SpeechSource.Listener() {
        @Override public void onPartial(String t) { applyResult(t, false); }
        @Override public void onFinal(String t) { if (t != null && !t.trim().isEmpty()) applyResult(t, true); }
        @Override public void onStatus(String status) { if (status.isEmpty()) updateStatus(); else statusView.setText(status); }
        @Override public void onError(String message) {
            Toast.makeText(ReciteActivity.this, message, Toast.LENGTH_LONG).show();
            stopListening(false);
        }
    };

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        boolean offline = useOffline && WhisperSpeechSource.available(this);
        if (!offline && !GoogleSpeechSource.available(this)) {
            Toast.makeText(this, "التعرّف على الكلام غير متوفر على هذا الجهاز", Toast.LENGTH_LONG).show();
            return;
        }
        if (summaryShown) restartAt(sessionStart);
        source = offline ? new WhisperSpeechSource(this, speechListener) : new GoogleSpeechSource(this, speechListener);
        listening = true;
        updateMicButton();
        refreshWords(tracker.position(), tracker.position() + 1);
        source.start();
    }

    /** @param sayHasbuk the teacher's «حسبك» when the reciter stops */
    private void stopListening(boolean sayHasbuk) {
        boolean was = listening;
        listening = false;
        if (source != null) { source.stop(); source = null; }
        updateMicButton();
        refreshWords(tracker.position(), tracker.position() + 1);
        if (was && sayHasbuk) sounds.hasbuk();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening();
        else Toast.makeText(this, "لا يمكن التسميع بدون الميكروفون", Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ menu

    private static final int M_MISTAKES = 1, M_HIDE = 2, M_RESTART = 3, M_SOUND = 4, M_ENGINE = 5, M_GOTO = 6;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_MISTAKES, 0, "قائمة الأخطاء").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, M_GOTO, 1, "انتقال إلى صفحة…");
        menu.add(0, M_HIDE, 2, "إخفاء الآيات (اختبار الحفظ)").setCheckable(true).setChecked(hideMode);
        menu.add(0, M_SOUND, 3, "الأصوات").setCheckable(true).setChecked(sounds.enabled);
        menu.add(0, M_ENGINE, 4, "الاستماع بالنموذج القرآني (بدون إنترنت)").setCheckable(true).setChecked(useOffline);
        menu.add(0, M_RESTART, 5, "إعادة التسميع من أول السورة");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_MISTAKES: showMistakeList(); return true;
            case M_GOTO: askPage(); return true;
            case M_HIDE:
                hideMode = !hideMode;
                item.setChecked(hideMode);
                prefs.edit().putBoolean("hide", hideMode).apply();
                refreshWords(pageFirstWord[page], pageFirstWord[page + 1]);
                return true;
            case M_SOUND:
                sounds.enabled = !sounds.enabled;
                item.setChecked(sounds.enabled);
                prefs.edit().putBoolean("sound", sounds.enabled).apply();
                return true;
            case M_ENGINE:
                useOffline = !useOffline;
                item.setChecked(useOffline);
                prefs.edit().putBoolean("offline", useOffline).apply();
                if (listening) { stopListening(false); startListening(); }
                Toast.makeText(this, useOffline ? "النموذج القرآني على الجهاز" : "Google (يحتاج إنترنت)", Toast.LENGTH_SHORT).show();
                return true;
            case M_RESTART: restartAt(firstWordOfSura(tracker.words().get(sessionStart).sura)); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void askPage() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setHint("1 – 604");
        new AlertDialog.Builder(this)
                .setTitle("رقم الصفحة")
                .setView(in)
                .setPositiveButton("انتقال", (dlg, w) -> {
                    try {
                        int p = Integer.parseInt(in.getText().toString().trim());
                        if (p >= 1 && p <= PAGES) restartAt(pageFirstWord[p]);
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }
}
