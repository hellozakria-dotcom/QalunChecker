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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** List of the 114 surahs (Qalun numbering of ayat). */
public class MainActivity extends Activity {

    private final List<QuranText.Sura> shown = new ArrayList<>();
    private List<QuranText.Sura> all;
    private SuraAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("مُدقّق قالون — اختر سورة");
        all = Data.text(this).suras();
        shown.addAll(all);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        EditText search = new EditText(this);
        search.setHint("بحث: اسم السورة أو رقمها");
        search.setSingleLine(true);
        int p = Data.dp(this, 12);
        search.setPadding(p, p, p, p);
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
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

    private void filter(String q) {
        shown.clear();
        String plainQ = stripMarks(q).toLowerCase(Locale.ROOT);
        for (QuranText.Sura s : all) {
            if (q.isEmpty()
                    || String.valueOf(s.number).equals(q)
                    || stripMarks(s.nameAr).contains(plainQ)
                    || s.nameEn.toLowerCase(Locale.ROOT).contains(plainQ)) {
                shown.add(s);
            }
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
            TextView num, name, info;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                int p = Data.dp(MainActivity.this, 14);
                row.setPadding(p, p, p, p);
                num = new TextView(MainActivity.this);
                num.setTextSize(16);
                num.setTextColor(Color.parseColor("#1B5E20"));
                num.setMinWidth(Data.dp(MainActivity.this, 40));
                name = new TextView(MainActivity.this);
                name.setTextSize(24);
                name.setTypeface(Data.font(MainActivity.this));
                name.setTextColor(Color.BLACK);
                info = new TextView(MainActivity.this);
                info.setTextSize(13);
                info.setGravity(Gravity.END);
                row.addView(num);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(info);
                row.setTag(new TextView[]{num, name, info});
            } else {
                row = (LinearLayout) convert;
            }
            TextView[] v = (TextView[]) row.getTag();
            QuranText.Sura s = shown.get(position);
            v[0].setText(String.valueOf(s.number));
            v[1].setText(s.nameAr);
            v[2].setText(s.nameEn + "\n" + s.ayahCount + " آية");
            return row;
        }
    }
}
