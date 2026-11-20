package com.qalun.checker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.qalun.checker.core.QuranText;
import com.qalun.checker.ui.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home: title, "continue" card, search, and the 114 surahs. */
public class MainActivity extends Activity {

    private final List<QuranText.Sura> shown = new ArrayList<>();
    private List<QuranText.Sura> all;
    private final int[] firstPage = new int[115];
    private SuraAdapter adapter;
    private TextView continueCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        QuranText text = Data.text(this);
        all = text.suras();
        shown.addAll(all);
        for (QuranText.Sura s : all) {
            List<QuranText.Ayah> a = text.ayat(s.number);
            if (!a.isEmpty()) firstPage[s.number] = QuranText.pageOf(a.get(0));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Theme.PARCHMENT);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setBackground(Theme.headerBackground());
        header.setPadding(0, Data.dp(this, 28), 0, Data.dp(this, 18));
        TextView title = new TextView(this);
        title.setText("مُدَقِّقُ قَالُونَ");
        title.setTypeface(Data.font(this));
        title.setTextSize(38);
        title.setTextColor(Theme.GOLD_LIGHT);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText("تسميع القرآن الكريم برواية قالون عن نافع");
        sub.setTextColor(Color.WHITE);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        header.addView(sub);
        View goldLine = new View(this);
        goldLine.setBackgroundColor(Theme.GOLD);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Data.dp(this, 120), Data.dp(this, 2));
        gl.gravity = Gravity.CENTER;
        gl.topMargin = Data.dp(this, 10);
        header.addView(goldLine, gl);
        root.addView(header);

        // continue card
        continueCard = new TextView(this);
        continueCard.setTextSize(17);
        continueCard.setTextColor(Theme.GREEN_DARK);
        continueCard.setGravity(Gravity.CENTER);
        continueCard.setBackground(Theme.rounded(Theme.GREEN_LIGHT, Theme.GOLD, Data.dp(this, 16), Data.dp(this, 1)));
        int cp = Data.dp(this, 14);
        continueCard.setPadding(cp, cp, cp, cp);
        continueCard.setOnClickListener(v -> {
            Intent i = new Intent(this, ReciteActivity.class);
            i.putExtra(ReciteActivity.EXTRA_PAGE, getSharedPreferences("settings", MODE_PRIVATE).getInt("last_page", 1));
            startActivity(i);
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Data.dp(this, 14);
        cl.setMargins(m, m, m, 0);
        root.addView(continueCard, cl);

        EditText search = new EditText(this);
        search.setHint("ابحث عن سورة بالاسم أو الرقم");
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setBackground(Theme.rounded(Color.WHITE, Theme.GOLD_LIGHT, Data.dp(this, 24), Data.dp(this, 1)));
        int sp = Data.dp(this, 12);
        search.setPadding(Data.dp(this, 18), sp, Data.dp(this, 18), sp);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMargins(m, m, m, Data.dp(this, 6));
        root.addView(search, sl);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new SuraAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent i = new Intent(this, ReciteActivity.class);
            i.putExtra(ReciteActivity.EXTRA_SURA, shown.get(position).number);
            startActivity(i);
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString().trim()); }
        });
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int last = getSharedPreferences("settings", MODE_PRIVATE).getInt("last_page", 0);
        if (last > 0) {
            continueCard.setText("📖  متابعة التسميع — الصفحة " + last);
            continueCard.setVisibility(View.VISIBLE);
        } else {
            continueCard.setText("📖  ابدأ من أول المصحف");
            continueCard.setVisibility(View.VISIBLE);
        }
    }

    private void filter(String q) {
        shown.clear();
        String plainQ = stripMarks(q).toLowerCase(Locale.ROOT);
        for (QuranText.Sura s : all) {
            if (q.isEmpty() || String.valueOf(s.number).equals(q)
                    || stripMarks(s.nameAr).contains(plainQ)
                    || s.nameEn.toLowerCase(Locale.ROOT).contains(plainQ)) shown.add(s);
        }
        adapter.notifyDataSetChanged();
    }

    private static String stripMarks(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (c < 0x064B || c > 0x065F) b.append(c);
        return b.toString();
    }

    private class SuraAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).number; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                float d = getResources().getDisplayMetrics().density;
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                int p = Data.dp(MainActivity.this, 12);
                row.setPadding(p, p, p, p);
                row.setBackground(Theme.rounded(Color.WHITE, Theme.PARCHMENT_DARK, Data.dp(MainActivity.this, 14), Data.dp(MainActivity.this, 1)));
                View star = new View(MainActivity.this);
                star.setBackground(new Theme.StarNumber(d));
                int sz = Data.dp(MainActivity.this, 46);
                row.addView(star, new LinearLayout.LayoutParams(sz, sz));
                TextView name = new TextView(MainActivity.this);
                name.setTypeface(Data.font(MainActivity.this));
                name.setTextSize(24);
                name.setTextColor(Theme.GREEN_DARK);
                name.setPadding(Data.dp(MainActivity.this, 14), 0, Data.dp(MainActivity.this, 14), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView info = new TextView(MainActivity.this);
                info.setTextSize(12);
                info.setTextColor(Color.parseColor("#7A6A45"));
                info.setGravity(Gravity.END);
                row.addView(info);
                row.setTag(new Object[]{star, name, info});
                LinearLayout wrap = new LinearLayout(MainActivity.this);
                int mm = Data.dp(MainActivity.this, 5);
                wrap.setPadding(Data.dp(MainActivity.this, 14), mm, Data.dp(MainActivity.this, 14), mm);
                wrap.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wrap.setTag(row);
                convert = wrap;
            }
            row = (LinearLayout) convert.getTag();
            Object[] v = (Object[]) row.getTag();
            QuranText.Sura s = shown.get(position);
            ((Theme.StarNumber) ((View) v[0]).getBackground()).setText(String.valueOf(s.number));
            ((TextView) v[1]).setText(s.nameAr);
            ((TextView) v[2]).setText(s.nameEn + "\n" + s.ayahCount + " آية · ص " + firstPage[s.number]);
            return convert;
        }
    }
}
