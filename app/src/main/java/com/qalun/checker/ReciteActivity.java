package com.qalun.checker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

import java.util.ArrayList;
import java.util.List;

/** Shows a surah in the Qalun mushaf script, listens, and marks mistakes live. */
public class ReciteActivity extends Activity {

    public static final String EXTRA_SURA = "sura";
    private static final int REQ_MIC = 7;

    private static final int C_PENDING = Color.parseColor("#212121");
    private static final int C_CORRECT = Color.parseColor("#2E7D32");
    private static final int C_WRONG = Color.parseColor("#C62828");
    private static final int C_WRONG_BG = Color.parseColor("#FFCDD2");
    private static final int C_MISSED = Color.parseColor("#E65100");
    private static final int C_MISSED_BG = Color.parseColor("#FFE0B2");
    private static final int C_CURRENT_BG = Color.parseColor("#FFF59D");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private QuranText.Sura sura;
    private RecitationTracker tracker;
    private int[] wordStart, wordEnd;
    private Object[][] wordSpans;

    private ScrollView scroll;
    private TextView quranView, statusView, bannerView;
    private Button micButton;

    private SpeechRecognizer recognizer;
    private boolean listening;
    private String lastPartial = "";
    private int committedPos = 0;
    private int shownPos = 0;
    private int networkRetries = 0;

    private boolean hideMode = false;
    private boolean soundAlert = true;
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int number = getIntent().getIntExtra(EXTRA_SURA, 1);
        QuranText text = Data.text(this);
        for (QuranText.Sura s : text.suras()) if (s.number == number) sura = s;
        if (sura == null) { finish(); return; }
        setTitle(number + ". " + sura.nameEn);

        tracker = new RecitationTracker(text.ayat(number));
        buildUi();
        renderText(text.ayat(number));
        refreshWords(0, tracker.words().size());
        updateStatus();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText(sura.nameAr);
        title.setTypeface(Data.font(this));
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.parseColor("#1B5E20"));
        title.setPadding(0, Data.dp(this, 6), 0, 0);
        root.addView(title);

        scroll = new ScrollView(this);
        quranView = new TextView(this);
        quranView.setTypeface(Data.font(this));
        quranView.setTextSize(27);
        quranView.setLineSpacing(Data.dp(this, 10), 1f);
        quranView.setTextDirection(View.TEXT_DIRECTION_RTL);
        quranView.setGravity(Gravity.START);
        quranView.setTextColor(C_PENDING);
        quranView.setHighlightColor(Color.TRANSPARENT);
        int p = Data.dp(this, 16);
        quranView.setPadding(p, p, p, Data.dp(this, 120));
        quranView.setMovementMethod(LinkMovementMethod.getInstance());
        scroll.addView(quranView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bannerView = new TextView(this);
        bannerView.setBackgroundColor(C_WRONG_BG);
        bannerView.setTextColor(Color.parseColor("#B71C1C"));
        bannerView.setTextSize(16);
        bannerView.setPadding(p, Data.dp(this, 8), p, Data.dp(this, 8));
        bannerView.setVisibility(View.GONE);
        bannerView.setOnClickListener(v -> showMistakeList());
        root.addView(bannerView);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(p, Data.dp(this, 8), p, Data.dp(this, 8));
        bar.setBackgroundColor(Color.parseColor("#F1F8E9"));

        statusView = new TextView(this);
        statusView.setTextSize(15);
        bar.addView(statusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        micButton = new Button(this);
        micButton.setTextSize(18);
        micButton.setOnClickListener(v -> { if (listening) stopListening(); else startListening(); });
        bar.addView(micButton);
        root.addView(bar);

        setContentView(root);
        updateMicButton();
    }

    private void renderText(List<QuranText.Ayah> ayat) {
        List<Word> words = tracker.words();
        wordStart = new int[words.size()];
        wordEnd = new int[words.size()];
        wordSpans = new Object[words.size()][];
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int wi = 0;
        for (QuranText.Ayah a : ayat) {
            for (String raw : ArabicNormalizer.splitWords(a.text)) {
                if (ArabicNormalizer.normalize(raw).isEmpty()) {
                    sb.append(raw).append(' ');
                    continue;
                }
                int start = sb.length();
                sb.append(raw);
                if (wi < words.size()) {
                    wordStart[wi] = start;
                    wordEnd[wi] = sb.length();
                    final int idx = wi;
                    sb.setSpan(new ClickableSpan() {
                        @Override public void onClick(View widget) { onWordTapped(idx); }
                        @Override public void updateDrawState(TextPaint ds) { /* keep our own colours */ }
                    }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    wi++;
                }
                sb.append(' ');
            }
            sb.append(' ').append(arabicDigits(a.aya)).append("  ");
        }
        quranView.setText(sb, TextView.BufferType.SPANNABLE);
    }

    private static String arabicDigits(int n) {
        String s = String.valueOf(n);
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append((char) ('٠' + (c - '0')));
        return b.toString();
    }

    /** Re-colour words [from, to). */
    private void refreshWords(int from, int to) {
        CharSequence cs = quranView.getText();
        if (!(cs instanceof Spannable)) return;
        Spannable sp = (Spannable) cs;
        List<Word> words = tracker.words();
        int cur = tracker.position();
        from = Math.max(0, from);
        to = Math.min(words.size(), to);
        for (int i = from; i < to; i++) {
            if (wordSpans[i] != null) for (Object o : wordSpans[i]) sp.removeSpan(o);
            Word w = words.get(i);
            List<Object> spans = new ArrayList<>();
            int fg;
            switch (w.state) {
                case CORRECT: fg = C_CORRECT; break;
                case WRONG: fg = C_WRONG; spans.add(new BackgroundColorSpan(C_WRONG_BG)); break;
                case MISSED: fg = C_MISSED; spans.add(new BackgroundColorSpan(C_MISSED_BG)); spans.add(new StrikethroughSpan()); break;
                default: fg = hideMode ? Color.TRANSPARENT : C_PENDING;
            }
            if (i == cur && w.state == State.PENDING) spans.add(new BackgroundColorSpan(C_CURRENT_BG));
            spans.add(new ForegroundColorSpan(fg));
            for (Object o : spans) sp.setSpan(o, wordStart[i], wordEnd[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wordSpans[i] = spans.toArray();
        }
    }

    private void updateStatus() {
        int ok = tracker.count(State.CORRECT), bad = tracker.count(State.WRONG), miss = tracker.count(State.MISSED);
        int total = tracker.words().size();
        statusView.setText("✓ " + ok + "   ✗ " + bad + "   ⊘ " + miss + "\n" + tracker.position() + " / " + total + " كلمة");
    }

    private void updateMicButton() {
        micButton.setText(listening ? "■ إيقاف" : "🎤 ابدأ التلاوة");
        micButton.setBackgroundColor(listening ? Color.parseColor("#C62828") : Color.parseColor("#2E7D32"));
        micButton.setTextColor(Color.WHITE);
    }

    private void scrollToCurrent() {
        Layout layout = quranView.getLayout();
        int cur = Math.min(tracker.position(), tracker.words().size() - 1);
        if (layout == null || cur < 0) return;
        int line = layout.getLineForOffset(wordStart[cur]);
        int y = quranView.getTop() + layout.getLineTop(line) - scroll.getHeight() / 3;
        scroll.smoothScrollTo(0, Math.max(0, y));
    }

    // -------------------------------------------------------------- tracking

    private void applyResult(String text, boolean isFinal) {
        int before = shownPos;
        List<Mistake> mistakes = tracker.onResult(text, isFinal);
        int after = tracker.position();
        refreshWords(committedPos, Math.max(before, after) + 1);
        shownPos = after;
        if (isFinal) committedPos = after;
        updateStatus();
        if (after != before) scrollToCurrent();
        if (!mistakes.isEmpty()) alertMistakes(mistakes);
        if (isFinal && tracker.finished()) {
            stopListening();
            showSummary();
        }
    }

    private void alertMistakes(List<Mistake> mistakes) {
        StringBuilder sb = new StringBuilder();
        for (Mistake m : mistakes) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(m.describe());
        }
        bannerView.setText(sb.toString());
        bannerView.setVisibility(View.VISIBLE);

        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE));
            else vib.vibrate(250);
        }
        if (soundAlert) {
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tone.startTone(ToneGenerator.TONE_PROP_NACK, 200);
            } catch (RuntimeException ignored) { }
        }
    }

    private void onWordTapped(int idx) {
        Word w = tracker.words().get(idx);
        StringBuilder msg = new StringBuilder("«" + w.display + "» — آية " + w.aya + "\n\n");
        if (w.state == State.WRONG) {
            msg.append("خطأ في هذه الكلمة");
            if (w.heard != null) msg.append("\nسُمِع: ").append(w.heard);
            for (String n : w.letterNotes) msg.append("\n • ").append(n);
        } else if (w.state == State.MISSED) {
            msg.append("هذه الكلمة تُركت.");
        } else if (w.state == State.CORRECT) {
            msg.append("قُرئت صحيحة ✓");
        } else {
            msg.append("لم تُقرأ بعد.");
        }
        new AlertDialog.Builder(this)
                .setMessage(msg.toString())
                .setPositiveButton("ابدأ التدقيق من هنا", (d, which) -> restartAt(idx))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void restartAt(int idx) {
        boolean wasListening = listening;
        if (wasListening) stopListening();
        tracker.restartAt(idx);
        committedPos = idx;
        shownPos = idx;
        lastPartial = "";
        refreshWords(idx, tracker.words().size());
        updateStatus();
        bannerView.setVisibility(View.GONE);
        scrollToCurrent();
        if (wasListening) startListening();
    }

    private void showMistakeList() {
        final List<Word> bad = new ArrayList<>();
        for (Word w : tracker.words()) if (w.state == State.WRONG || w.state == State.MISSED) bad.add(w);
        if (bad.isEmpty()) {
            Toast.makeText(this, "لا توجد أخطاء حتى الآن", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[bad.size()];
        for (int i = 0; i < bad.size(); i++) {
            Word w = bad.get(i);
            items[i] = "آية " + w.aya + ": " + w.display + (w.state == State.MISSED ? " — متروكة" : " — خطأ" + (w.heard != null ? " (سُمِع: " + w.heard + ")" : ""));
        }
        new AlertDialog.Builder(this)
                .setTitle("الأخطاء (" + bad.size() + ")")
                .setItems(items, (d, which) -> onWordTapped(bad.get(which).index))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showSummary() {
        int total = tracker.words().size();
        int ok = tracker.count(State.CORRECT), bad = tracker.count(State.WRONG), miss = tracker.count(State.MISSED);
        new AlertDialog.Builder(this)
                .setTitle("انتهت السورة")
                .setMessage("صحيحة: " + ok + " من " + total + "\nأخطاء: " + bad + "\nمتروكة: " + miss)
                .setPositiveButton("عرض الأخطاء", (d, w) -> showMistakeList())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ---------------------------------------------------------- speech input

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("التعرّف على الكلام غير متوفر")
                    .setMessage("ثبّت تطبيق Google (أو «خدمات التعرّف على الكلام من Google») وفعّل اللغة العربية، ثم حاول مجدداً.")
                    .setPositiveButton("حسناً", null)
                    .show();
            return;
        }
        if (tracker.finished()) restartAt(0);
        listening = true;
        networkRetries = 0;
        updateMicButton();
        listenOnce();
    }

    private void stopListening() {
        listening = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        if (!lastPartial.isEmpty()) { String p = lastPartial; lastPartial = ""; applyResult(p, true); }
        updateMicButton();
    }

    private void listenOnce() {
        if (!listening) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        lastPartial = "";
        recognizer.startListening(i);
    }

    private void listenAgain(long delayMs) {
        if (!listening) return;
        handler.postDelayed(this::listenOnce, delayMs);
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty()) ? null : r.get(0);
    }

    private final RecognitionListener listener = new RecognitionListener() {
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
            applyResult(t, false);
        }

        @Override
        public void onResults(Bundle results) {
            String t = first(results);
            if (t == null || t.trim().isEmpty()) t = lastPartial;
            lastPartial = "";
            networkRetries = 0;
            if (t != null && !t.trim().isEmpty()) applyResult(t, true);
            listenAgain(50);
        }

        @Override
        public void onError(int error) {
            // keep what was already heard in this utterance
            if (!lastPartial.isEmpty()) { String p = lastPartial; lastPartial = ""; applyResult(p, true); }
            if (!listening) return;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopListening();
                    Toast.makeText(ReciteActivity.this, "يلزم إذن الميكروفون", Toast.LENGTH_LONG).show();
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    if (++networkRetries > 3) {
                        stopListening();
                        Toast.makeText(ReciteActivity.this, "تحقق من اتصال الإنترنت", Toast.LENGTH_LONG).show();
                    } else listenAgain(1500);
                    break;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
                case 13: // ERROR_LANGUAGE_UNAVAILABLE (API 31)
                    stopListening();
                    Toast.makeText(ReciteActivity.this, "العربية غير متوفرة في خدمة التعرّف على الكلام على هذا الجهاز", Toast.LENGTH_LONG).show();
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    if (recognizer != null) { recognizer.destroy(); recognizer = null; }
                    listenAgain(400);
                    break;
                default: // no match / speech timeout: just keep listening
                    listenAgain(100);
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else if (requestCode == REQ_MIC) {
            Toast.makeText(this, "لا يمكن التدقيق بدون الميكروفون", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ menu

    private static final int M_MISTAKES = 1, M_HIDE = 2, M_RESTART = 3, M_SOUND = 4;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_MISTAKES, 0, "قائمة الأخطاء").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, M_HIDE, 1, "إخفاء النص (اختبار الحفظ)").setCheckable(true).setChecked(hideMode);
        menu.add(0, M_SOUND, 2, "صوت عند الخطأ").setCheckable(true).setChecked(soundAlert);
        menu.add(0, M_RESTART, 3, "البدء من أول السورة");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_MISTAKES: showMistakeList(); return true;
            case M_HIDE:
                hideMode = !hideMode;
                item.setChecked(hideMode);
                refreshWords(0, tracker.words().size());
                return true;
            case M_SOUND:
                soundAlert = !soundAlert;
                item.setChecked(soundAlert);
                return true;
            case M_RESTART: restartAt(0); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening) stopListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tone != null) { tone.release(); tone = null; }
    }
}
