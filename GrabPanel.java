package com.mkr.player;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// MKR ka "GRABBER" tab: link paste / share -> qualities popup -> download
final class GrabPanel extends LinearLayout {
    static final Pattern URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);

    static class Row {
        LinearLayout card;
        TextView t, i, act, x;
        ProgressBar pb;
    }

    MainActivity a;
    boolean dark;
    final Handler h = new Handler(Looper.getMainLooper());
    final Map<Grab.Job, Row> rows = new HashMap<>();
    EditText et;
    TextView st;
    ProgressBar spin;
    LinearLayout list;
    String busyUrl = "";
    boolean setting;

    final Runnable tick = new Runnable() {
        public void run() {
            if (getVisibility() == VISIBLE) {
                sync();
                a.sub.setText(summary());
            }
            h.postDelayed(this, 700);
        }
    };

    static final String[] WAIT = {
            "Lo g, mein hun labban lagga, file download karan lai tusi zara sabar rakho \uD83D\uDE0E",
            "Ruk ja bai, link nu zara sungh ke vekh lain dey \uD83D\uDC36",
            "Oye hoye! Server nu chah pila ke manaa reha aan \u2615",
            "Sabar da fal mitha hunda ae, mein ohnu labb reha aan \uD83D\uDD0D"};

    static String wait() { return WAIT[(int) (Math.random() * WAIT.length)]; }

    static String findUrl(String s) {
        if (s == null) return null;
        Matcher m = URL.matcher(s);
        return m.find() ? m.group() : null;
    }

    GrabPanel(MainActivity act) {
        super(act);
        a = act;
        dark = act.dark;
        setOrientation(VERTICAL);
        setPadding(a.d(10), a.d(12), a.d(10), 0);
        int fg = U.fg(dark);

        // ----- link box -----
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(VERTICAL);
        box.setPadding(a.d(14), a.d(12), a.d(14), a.d(12));
        box.setBackground(U.glass(a, dark, a.d(12), false));
        TextView lb = new TextView(a);
        lb.setText("VIDEO GRABBER");
        lb.setTextColor(fg);
        lb.setTextSize(15);
        lb.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        TextView hint = new TextView(a);
        hint.setText("Link paste karo, ya kisi app se Share \u2192 MKR Player");
        hint.setTextColor(U.fg2(dark));
        hint.setTextSize(12);
        hint.setPadding(0, a.d(2), 0, a.d(10));
        box.addView(lb);
        box.addView(hint);

        et = new EditText(a);
        et.setHint("https://...");
        et.setHintTextColor(U.fg2(dark));
        et.setTextColor(fg);
        et.setTextSize(14);
        et.setSingleLine();
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setImeOptions(EditorInfo.IME_ACTION_GO);
        et.setPadding(a.d(16), a.d(10), a.d(16), a.d(10));
        et.setBackground(U.rr(dark ? 0x22F2B705 : 0xAAFFFFFF, a.d(12)));
        et.setOnEditorActionListener((v, id, ev) -> {
            go(et.getText().toString());
            return true;
        });
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // paste (ek saath bahut saare akshar) => foran popup
                if (!setting && count - before > 8) go(s.toString());
            }
            public void afterTextChanged(Editable s) { }
        });
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout br = new LinearLayout(a);
        TextView paste = a.btn("PASTE", false), grab = a.btn("GRAB", true);
        paste.setOnClickListener(v -> pasteClip());
        grab.setOnClickListener(v -> go(et.getText().toString()));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1);
        bl.rightMargin = a.d(5);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, -2, 1);
        bl2.leftMargin = a.d(5);
        br.addView(paste, bl);
        br.addView(grab, bl2);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(-1, -2);
        brp.topMargin = a.d(10);
        box.addView(br, brp);

        LinearLayout sr = new LinearLayout(a);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        spin = new ProgressBar(a);
        spin.setVisibility(GONE);
        sr.addView(spin, new LinearLayout.LayoutParams(a.d(18), a.d(18)));
        st = new TextView(a);
        st.setTextSize(12);
        st.setTextColor(U.fg2(dark));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.leftMargin = a.d(6);
        sr.addView(st, stp);
        sr.setVisibility(GONE);
        sr.setTag("sr");
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = a.d(8);
        box.addView(sr, srp);
        addView(box, new LinearLayout.LayoutParams(-1, -2));

        // ----- downloads list -----
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(a);
        list.setOrientation(VERTICAL);
        list.setPadding(0, a.d(10), 0, a.d(30));
        sv.addView(list);
        addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sync();
        h.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    String summary() {
        int n = Grab.active(), d = Grab.done();
        if (n == 0 && d == 0) return "Link paa de, baaki mein vekh lainda aan";
        return (n > 0 ? n + " downloading" : "") + (n > 0 && d > 0 ? "  \u2022  " : "") + (d > 0 ? d + " done" : "");
    }

    void status(String s, boolean busy) {
        View sr = findViewWithTag("sr");
        spin.setVisibility(busy ? VISIBLE : GONE);
        st.setText(s);
        sr.setVisibility(s.isEmpty() ? GONE : VISIBLE);
    }

    void pasteClip() {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        String s = null;
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).coerceToText(a) != null)
                s = cd.getItemAt(0).coerceToText(a).toString();
        }
        if (findUrl(s) == null) {
            status("Oye, clipboard taan khali ae! Pehlan link copy kar \uD83D\uDE05", false);
            return;
        }
        go(s);
    }

    // ================= link -> popup =================
    void go(String raw) {
        String u = findUrl(raw);
        if (u == null) {
            status("Oye! Eh link taan nahi lagda, theek kar ke pao \uD83E\uDD14", false);
            return;
        }
        if (!busyUrl.isEmpty()) return;
        busyUrl = u;
        setting = true;
        et.setText(u);
        setting = false;
        status(wait() + "  (pehli vaar zara time lagda ae)", true);
        Grab.fetch(u,
                info -> a.runOnUiThread(() -> {
                    busyUrl = "";
                    status("", false);
                    quality(u, info);
                }),
                err -> a.runOnUiThread(() -> {
                    busyUrl = "";
                    status("Oye hoye! Kuj gadbad ho gayi: " + err, false);
                }));
    }

    void quality(final String url, final Grab.Info info) {
        final Dialog[] dg = new Dialog[1];
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(VERTICAL);
        TextView tt = new TextView(a);
        tt.setText(info.title);
        tt.setTextColor(U.BROWN);
        tt.setTextSize(13);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        tt.setMaxLines(2);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setPadding(a.d(4), 0, a.d(4), a.d(10));
        c.addView(tt);
        for (final Grab.Tier t : info.tiers) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(a.d(10), a.d(8), a.d(12), a.d(8));
            row.setBackground(U.glass(a, false, a.d(12), false));
            U.bounce(row);
            TextView chip = new TextView(a);
            chip.setText(t.chip);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(U.grad(U.BUTTER, U.BUTTER_D, a.d(10)));
            row.addView(chip, new LinearLayout.LayoutParams(a.d(52), a.d(32)));
            LinearLayout tc = new LinearLayout(a);
            tc.setOrientation(VERTICAL);
            TextView n1 = new TextView(a);
            n1.setText(t.height > 0 ? t.height + "p" : t.height == 0 ? "Best quality" : "Sirf audio");
            n1.setTextColor(U.BROWN);
            n1.setTextSize(15);
            n1.setTypeface(Typeface.DEFAULT_BOLD);
            TextView n2 = new TextView(a);
            n2.setText(t.height >= 0 ? "MP4  \u2022  video + audio" : "M4A  \u2022  music");
            n2.setTextColor(0xFF8A6D00);
            n2.setTextSize(12);
            tc.addView(n1);
            tc.addView(n2);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = a.d(12);
            row.addView(tc, tp);
            TextView sz = new TextView(a);
            sz.setText(t.size > 0 ? "~" + U.sz(t.size) : "");
            sz.setTextColor(U.BROWN);
            sz.setTextSize(13);
            sz.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(sz);
            row.setOnClickListener(v -> {
                dg[0].dismiss();
                begin(url, info, t);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = a.d(8);
            c.addView(row, rp);
        }
        ScrollView sv = a.limited();
        sv.addView(c);
        dg[0] = a.sheet("Ey ley, labb gayi aa! Karley hun download \uD83C\uDF89", sv);
    }

    void begin(String url, Grab.Info info, Grab.Tier t) {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        Grab.start(url, t.height, t.size, info.title);
        setting = true;
        et.setText("");
        setting = false;
        status("Lo g, download chal pya aa... tusi chah-shah pi lao \u2615", false);
        sync();
        a.sub.setText(summary());
    }

    // ================= downloads list =================
    void sync() {
        List<Grab.Job> js = Grab.jobs();
        for (Grab.Job j : js) {
            Row r = rows.get(j);
            if (r == null) {
                r = make(j);
                rows.put(j, r);
                list.addView(r.card, 0);
            }
            upd(j, r);
        }
        for (Grab.Job j : new ArrayList<>(rows.keySet()))
            if (!js.contains(j)) {
                list.removeView(rows.get(j).card);
                rows.remove(j);
            }
    }

    Row make(final Grab.Job j) {
        final Row r = new Row();
        LinearLayout w = new LinearLayout(a);
        w.setPadding(a.d(4), a.d(3), a.d(4), a.d(3));
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(VERTICAL);
        c.setPadding(a.d(12), a.d(10), a.d(12), a.d(10));
        c.setBackground(U.glass(a, dark, a.d(12), false));
        w.addView(c, new LinearLayout.LayoutParams(-1, -2));
        r.card = w;
        r.t = new TextView(a);
        r.t.setTextColor(U.fg(dark));
        r.t.setTextSize(13);
        r.t.setTypeface(Typeface.DEFAULT_BOLD);
        r.t.setMaxLines(2);
        r.t.setEllipsize(TextUtils.TruncateAt.END);
        r.i = new TextView(a);
        r.i.setTextColor(U.fg2(dark));
        r.i.setTextSize(11);
        r.i.setPadding(0, a.d(3), 0, a.d(6));
        r.pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        r.pb.setMax(1000);
        r.pb.setProgressTintList(android.content.res.ColorStateList.valueOf(U.acc(dark)));
        r.pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66000000));
        LinearLayout br = new LinearLayout(a);
        br.setGravity(Gravity.END);
        r.act = a.btn("", false);
        r.act.setPadding(a.d(16), a.d(7), a.d(16), a.d(7));
        r.act.setTextSize(12);
        r.x = a.btn("\u2715", false);
        r.x.setPadding(a.d(14), a.d(7), a.d(14), a.d(7));
        r.x.setTextSize(12);
        r.act.setOnClickListener(v -> {
            if (j.status.equals("running")) Grab.cancel(j);
            else if (j.status.equals("done")) play(j);
            else Grab.retry(j);
            sync();
        });
        r.x.setOnClickListener(v -> {
            Grab.remove(j);
            sync();
        });
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-2, -2);
        xp.leftMargin = a.d(8);
        br.addView(r.act);
        br.addView(r.x, xp);
        c.addView(r.t);
        c.addView(r.i);
        c.addView(r.pb, new LinearLayout.LayoutParams(-1, a.d(4)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = a.d(8);
        c.addView(br, bp);
        return r;
    }

    void upd(Grab.Job j, Row r) {
        r.t.setText(j.title == null || j.title.isEmpty() ? j.url : j.title);
        String q = j.height > 0 ? j.height + "p" : j.height < 0 ? "Audio" : "Best";
        boolean run = j.status.equals("running");
        String s;
        switch (j.status) {
            case "running":
                s = j.merging ? q + "  \u2022  Jod-tod chal rahi aa, bas hon ho gaya..."
                        : q + "  \u2022  " + (int) j.pct + "%  \u2022  " + String.format("%.1f MB/s", j.speed / 1e6)
                        + (j.eta > 0 ? "  \u2022  " + U.t((long) (j.eta * 1000)) : "");
                break;
            case "done":
                s = "Oey oey! Hogayi aa download \u2714  \u2022  " + j.where;
                break;
            case "error":
                s = "Oye hoye! \u26A0 " + j.err;
                break;
            default:
                s = "Cancel kar ditta, koi gal nahi \uD83D\uDE0C";
        }
        r.i.setText(s);
        r.pb.setVisibility(run || j.status.equals("done") ? VISIBLE : GONE);
        r.pb.setProgress((int) (j.pct * 10));
        boolean canPlay = j.status.equals("done") && !j.uri.isEmpty();
        r.act.setText(run ? "CANCEL" : j.status.equals("done") ? "PLAY" : "RETRY");
        r.act.setVisibility(j.status.equals("done") && !canPlay ? GONE : VISIBLE);
        r.x.setVisibility(run ? GONE : VISIBLE);
    }

    void play(Grab.Job j) {
        if (j.uri.isEmpty()) return;
        Uri u = Uri.parse(j.uri);
        long id;
        try {
            id = ContentUris.parseId(u);
        } catch (Exception e) {
            id = j.uri.hashCode();
        }
        ArrayList<String> us = new ArrayList<>(), ns = new ArrayList<>();
        us.add(j.uri);
        ns.add(j.title);
        Intent in = new Intent(a, PlayerActivity.class);
        in.putStringArrayListExtra("uris", us);
        in.putStringArrayListExtra("names", ns);
        in.putExtra("ids", new long[]{id});
        in.putExtra("i", 0);
        a.startActivity(in);
    }
}
