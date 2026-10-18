package com.mkr.player;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static class V {
        long id, dur, size;
        String name, folder, data;
        Uri uri;
    }

    static class H {
        FrameLayout th;
        ImageView im;
        U.FolderIcon fi;
        TextView dur, a, b, chev;
        ProgressBar pb;
        LinearLayout card;
        long id = -1;
    }

    final List<V> all = new ArrayList<>();
    final TreeMap<String, List<V>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    final List<Object> items = new ArrayList<>();
    final LinkedHashSet<Object> sel = new LinkedHashSet<>();
    String cur, q = "";
    boolean dark, quiet;
    SharedPreferences sp;
    ListView lv;
    TextView empty, sub, renBtn, fab;
    View backTv;
    ImageView logo;
    EditText sb;
    LinearLayout abar;
    LruCache<Long, Bitmap> cache;
    final ExecutorService ex = Executors.newFixedThreadPool(2);
    BaseAdapter ad;
    Runnable pend;
    int tab;
    GrabPanel panel;
    View si;
    TextView[] tabT = new TextView[2];
    View[] tabU = new View[2];

    int d(int v) { return U.dp(this, v); }

    void tt(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    String perm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    boolean has() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = getSharedPreferences("s", 0);
        dark = sp.getBoolean("dark", true);
        cache = new LruCache<Long, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
            @Override
            protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
        };
        ad = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) { return bind(i, v); }
        };
        Grab.attach(this);
        build();
        if (!has() && Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{perm()}, 1);
        shared(getIntent());
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        shared(in);
    }

    // kisi app se "Share" -> MKR Player: link milte hi Grabber tab + quality popup
    void shared(Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return;
        String u = GrabPanel.findUrl(in.getStringExtra(Intent.EXTRA_TEXT));
        in.setAction(null);
        if (u == null) {
            tt("Link nahi mila");
            return;
        }
        setTab(1);
        panel.go(u);
    }

    void setTab(int t) {
        tab = t;
        if (t == 1) {
            sel.clear();
            if (sb != null) closeSearch();
        }
        for (int i = 0; i < 2; i++) {
            tabT[i].setTextColor(i == t ? U.fg(dark) : U.fg2(dark));
            tabU[i].setBackgroundColor(i == t ? U.BUTTER_D : 0);
        }
        lv.setVisibility(t == 0 ? View.VISIBLE : View.GONE);
        panel.setVisibility(t == 1 ? View.VISIBLE : View.GONE);
        if (t == 1) panel.sync();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { load(); }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onActivityResult(int rq, int rs, android.content.Intent data) {
        if (rq == 7 && rs == RESULT_OK && pend != null) pend.run();
        pend = null;
    }

    void load() {
        all.clear();
        folders.clear();
        if (has()) {
            String[] p = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATA};
            try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, p, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext()) {
                    V v = new V();
                    v.id = c.getLong(0);
                    v.name = c.getString(1);
                    v.dur = c.getLong(2);
                    v.size = c.getLong(3);
                    String f = c.getString(4);
                    if (f == null) f = "Internal";
                    v.folder = f;
                    v.data = c.getString(5);
                    v.uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(v.id));
                    all.add(v);
                    List<V> l = folders.get(f);
                    if (l == null) folders.put(f, l = new ArrayList<>());
                    l.add(v);
                }
            } catch (Exception ignored) {
            }
        }
        if (cur != null && !folders.containsKey(cur)) cur = null;
        sel.clear();
        refresh();
    }

    void refresh() {
        items.clear();
        String ql = q.toLowerCase();
        if (ql.isEmpty()) {
            if (cur == null) items.addAll(folders.keySet());
            else items.addAll(folders.get(cur));
        } else {
            if (cur == null)
                for (String f : folders.keySet()) if (f.toLowerCase().contains(ql)) items.add(f);
            List<V> src = cur == null ? all : folders.get(cur);
            for (V v : src) if (v.name != null && v.name.toLowerCase().contains(ql)) items.add(v);
        }
        boolean s = !sel.isEmpty(), g = tab == 1;
        sub.setText(g ? panel.summary() : s ? sel.size() + " selected" : cur == null ? folders.size() + " folders  \u2022  " + all.size() + " videos" : cur);
        backTv.setVisibility(!g && (cur != null || s) ? View.VISIBLE : View.GONE);
        logo.setVisibility(!g && (cur != null || s) ? View.GONE : View.VISIBLE);
        si.setVisibility(g ? View.GONE : View.VISIBLE);
        abar.setVisibility(s && !g ? View.VISIBLE : View.GONE);
        renBtn.setVisibility(sel.size() == 1 ? View.VISIBLE : View.GONE);
        fab.setVisibility(s || g ? View.GONE : View.VISIBLE);
        empty.setText(has() ? "Koi video nahi mili" : "Permission do (tap karo)");
        empty.setVisibility(!g && items.isEmpty() ? View.VISIBLE : View.GONE);
        ad.notifyDataSetChanged();
    }

    void toggle(Object o) {
        if (!sel.remove(o)) sel.add(o);
        quiet = true;
        refresh();
        lv.post(() -> quiet = false);
    }

    void build() {
        int fg = U.fg(dark), head = dark ? U.STONE : 0xFFFFFFFF;
        getWindow().setStatusBarColor(head);
        getWindow().setNavigationBarColor(dark ? U.STONE : 0xFFFFFFFF);
        int fl0 = 0;
        if (!dark && Build.VERSION.SDK_INT >= 23) fl0 |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && Build.VERSION.SDK_INT >= 26) fl0 |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(fl0);

        FrameLayout fr = new FrameLayout(this);
        fr.addView(new U.LiquidBg(this, dark), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        fr.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ----- header (flat, no rounded corners) -----
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.VERTICAL);
        hd.setPadding(d(14), d(12), d(14), d(14));
        hd.setBackgroundColor(head);
        
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        backTv = new U.BackBtn(this, fg);
        backTv.setOnClickListener(v -> onBackPressed());
        LinearLayout.LayoutParams bkl = new LinearLayout.LayoutParams(-2, -2);
        bkl.rightMargin = d(4);
        row.addView(backTv, bkl);
        logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        row.addView(logo, new LinearLayout.LayoutParams(d(46), d(46)));
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = d(10);
        TextView name = new TextView(this);
        name.setText("MKR");
        name.setTextColor(fg);
        name.setTextSize(21);
        name.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        name.setLetterSpacing(0.06f);
        sub = new TextView(this);
        sub.setTextColor(U.fg2(dark));
        sub.setTextSize(12);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        tb.addView(name);
        tb.addView(sub);
        row.addView(tb, tl);
        U.SearchIcon si = new U.SearchIcon(this, fg);
        this.si = si;
        row.addView(si);
        U.ThemeSwitch sw = new U.ThemeSwitch(this, dark);
        sw.cb = () -> {
            dark = !dark;
            sp.edit().putBoolean("dark", dark).apply();
            build();
            refresh();
        };
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(-2, -2);
        swl.leftMargin = d(6);
        row.addView(sw, swl);
        hd.addView(row, new LinearLayout.LayoutParams(-1, -2));

        sb = new EditText(this);
        sb.setHint("Search...");
        sb.setHintTextColor(U.fg2(dark));
        sb.setTextColor(fg);
        sb.setTextSize(15);
        sb.setSingleLine();
        sb.setPadding(d(18), d(10), d(18), d(10));
        sb.setBackground(U.rr(dark ? 0x22F2B705 : 0xAAFFFFFF, d(12)));
        sb.setText(q);
        sb.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        sb.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                q = s.toString().trim();
                refresh();
            }
            public void afterTextChanged(Editable s) { }
        });
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(-1, -2);
        sbl.topMargin = d(12);
        hd.addView(sb, sbl);
        si.setOnClickListener(v -> {
            if (sb.getVisibility() == View.VISIBLE) {
                closeSearch();
            } else {
                sb.setVisibility(View.VISIBLE);
                sb.requestFocus();
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(sb, 0);
            }
        });
        col.addView(hd, new LinearLayout.LayoutParams(-1, -2));
        View hl = new View(this);
        hl.setBackgroundColor(U.BUTTER);
        col.addView(hl, new LinearLayout.LayoutParams(-1, d(2)));

        // ----- tabs: VIDEOS | GRABBER -----
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(head);
        String[] tn = {"VIDEOS", "GRABBER"};
        for (int i = 0; i < 2; i++) {
            LinearLayout tc = new LinearLayout(this);
            tc.setOrientation(LinearLayout.VERTICAL);
            TextView tx = new TextView(this);
            tx.setText(tn[i]);
            tx.setTextSize(13);
            tx.setTypeface(Typeface.DEFAULT_BOLD);
            tx.setLetterSpacing(0.08f);
            tx.setGravity(Gravity.CENTER);
            tx.setPadding(0, d(11), 0, d(9));
            View ul = new View(this);
            tabT[i] = tx;
            tabU[i] = ul;
            tc.addView(tx, new LinearLayout.LayoutParams(-1, -2));
            tc.addView(ul, new LinearLayout.LayoutParams(-1, d(3)));
            final int k = i;
            tc.setOnClickListener(v -> setTab(k));
            tabs.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        }
        col.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        // ----- list -----
        FrameLayout body = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setPadding(d(10), d(12), d(10), d(110));
        lv.setClipToPadding(false);
        lv.setSelector(android.R.color.transparent);
        lv.setAdapter(ad);
        lv.setOnItemClickListener((pa, vw, i, id) -> {
            Object o = items.get(i);
            if (!sel.isEmpty()) {
                toggle(o);
                return;
            }
            if (o instanceof String) {
                cur = (String) o;
                closeSearch();
                refresh();
                lv.setSelection(0);
            } else play(i);
        });
        lv.setOnItemLongClickListener((pa, vw, i, id) -> {
            toggle(items.get(i));
            return true;
        });
        body.addView(lv, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setTextColor(U.fg2(dark));
        empty.setTextSize(16);
        empty.setOnClickListener(vw -> {
            if (!has() && Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{perm()}, 1);
        });
        body.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        panel = new GrabPanel(this);
        body.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // ----- play / resume button -----
        fab = new TextView(this);
        fab.setText("\u25B6");
        fab.setTextColor(U.onAcc(dark));
        fab.setTextSize(24);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable fg2 = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{U.BUTTER, U.BUTTER_D});
        fg2.setShape(GradientDrawable.OVAL);
        fg2.setStroke(d(2), 0xFFFFFFFF);
        fab.setBackground(fg2);
        fab.setElevation(d(8));
        fab.setOnClickListener(v -> resume());
        U.bounce(fab);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(d(62), d(62), Gravity.BOTTOM | Gravity.END);
        fl.setMargins(0, 0, d(20), d(24));
        fr.addView(fab, fl);

        // ----- selection action bar -----
        abar = new LinearLayout(this);
        abar.setGravity(Gravity.CENTER);
        abar.setPadding(d(8), d(6), d(8), d(6));
        abar.setBackground(U.pglass(this, dark, d(28)));
        abar.setElevation(d(8));
        String[] ln = {"MOVE", "SHARE", "RENAME", "\u2715"};
        for (int i = 0; i < 4; i++) {
            TextView t = new TextView(this);
            t.setText(ln[i]);
            t.setTextColor(U.BROWN);
            t.setTextSize(13);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setPadding(d(8), d(12), d(8), d(12));
            U.bounce(t);
            final int k = i;
            t.setOnClickListener(v -> {
                if (k == 0) move();
                else if (k == 1) share();
                else if (k == 2) rename();
                else {
                    sel.clear();
                    refresh();
                }
            });
            if (i == 2) renBtn = t;
            abar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        abar.setVisibility(View.GONE);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        al.setMargins(d(14), 0, d(14), d(16));
        fr.addView(abar, al);
        setContentView(fr);
        setTab(tab);
    }

    void closeSearch() {
        q = "";
        sb.setText("");
        sb.setVisibility(View.GONE);
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(sb.getWindowToken(), 0);
    }

    void resume() {
        long id = sp.getLong("lastId", -1);
        for (V v : all)
            if (v.id == id) {
                List<V> l = folders.get(v.folder);
                startPlay(l, l.indexOf(v));
                return;
            }
        if (!all.isEmpty()) startPlay(folders.get(all.get(0).folder), 0);
        else tt("Koi video nahi mili");
    }

    void play(int pos) {
        List<V> l = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof V) {
                if (i == pos) at = l.size();
                l.add((V) o);
            }
        }
        startPlay(l, at);
    }

    void startPlay(List<V> l, int pos) {
        ArrayList<String> u = new ArrayList<>(), n = new ArrayList<>();
        long[] ids = new long[l.size()];
        for (int i = 0; i < l.size(); i++) {
            V v = l.get(i);
            u.add(v.uri.toString());
            n.add(v.name);
            ids[i] = v.id;
        }
        Intent in = new Intent(this, PlayerActivity.class);
        in.putStringArrayListExtra("uris", u);
        in.putStringArrayListExtra("names", n);
        in.putExtra("ids", ids);
        in.putExtra("i", Math.max(0, pos));
        startActivity(in);
    }

    // ================= select actions: share / move / rename =================
    List<V> selVideos() {
        LinkedHashSet<V> s = new LinkedHashSet<>();
        for (Object o : sel) {
            if (o instanceof V) s.add((V) o);
            else s.addAll(folders.get((String) o));
        }
        return new ArrayList<>(s);
    }

    List<Uri> uris(List<V> l) {
        List<Uri> u = new ArrayList<>();
        for (V v : l) u.add(v.uri);
        return u;
    }

    // ---------- glass bottom sheet (same UI for share / move / rename) ----------
    Dialog sheet(String title, View content) {
        final Dialog dg = new Dialog(this);
        dg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(d(12), d(12), d(12), d(16));
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(d(16), d(16), d(16), d(14));
        p.setBackground(U.pglass(this, dark, d(12)));
        p.setElevation(d(10));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(U.BROWN);
        t.setTextSize(19);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        t.setPadding(d(4), 0, 0, d(12));
        p.addView(t);
        p.addView(content);
        wrap.addView(p, new FrameLayout.LayoutParams(-1, -2));
        dg.setContentView(wrap);
        Window w = dg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setLayout(-1, -2);
        w.setGravity(Gravity.BOTTOM);
        p.setTranslationY(d(70));
        p.setAlpha(0f);
        p.animate().translationY(0).alpha(1f).setDuration(340).setInterpolator(new OvershootInterpolator(1.6f)).start();
        dg.show();
        return dg;
    }

    TextView btn(String s, boolean main) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(14);
        t.setPadding(d(22), d(11), d(22), d(11));
        if (main) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(U.grad(U.BUTTER, U.BUTTER_D, d(12)));
        } else {
            t.setTextColor(U.BROWN);
            t.setBackground(U.glass(this, false, d(12), false));
        }
        U.bounce(t);
        return t;
    }

    ScrollView limited() {
        final int mh = getResources().getDisplayMetrics().heightPixels * 55 / 100;
        return new ScrollView(this) {
            @Override
            protected void onMeasure(int wm, int hm) {
                super.onMeasure(wm, View.MeasureSpec.makeMeasureSpec(mh, View.MeasureSpec.AT_MOST));
            }
        };
    }

    void share() {
        List<V> l = selVideos();
        if (l.isEmpty()) return;
        ArrayList<Uri> u = new ArrayList<>(uris(l));
        final Intent in = new Intent(u.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        in.setType("video/*");
        if (u.size() == 1) in.putExtra(Intent.EXTRA_STREAM, u.get(0));
        else in.putParcelableArrayListExtra(Intent.EXTRA_STREAM, u);
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final android.content.pm.PackageManager pm = getPackageManager();
        final List<ResolveInfo> ra = pm.queryIntentActivities(in, 0);
        if (ra.isEmpty()) {
            startActivity(Intent.createChooser(in, "Share"));
            return;
        }
        final Dialog[] dg = new Dialog[1];
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < ra.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final ResolveInfo ri = ra.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(d(2), d(8), d(2), d(8));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ri.loadIcon(pm));
            cell.addView(iv, new LinearLayout.LayoutParams(d(48), d(48)));
            TextView tx = new TextView(this);
            tx.setText(ri.loadLabel(pm));
            tx.setTextColor(U.BROWN);
            tx.setTextSize(11);
            tx.setGravity(Gravity.CENTER);
            tx.setSingleLine();
            tx.setEllipsize(TextUtils.TruncateAt.END);
            tx.setPadding(0, d(4), 0, 0);
            cell.addView(tx, new LinearLayout.LayoutParams(-1, -2));
            U.bounce(cell);
            cell.setOnClickListener(v -> {
                dg[0].dismiss();
                Intent go = new Intent(in);
                go.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                try {
                    startActivity(go);
                } catch (Exception e) {
                    tt("Share nahi hua");
                }
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        while (ra.size() % cols != 0 && row != null && row.getChildCount() < cols)
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ScrollView sv = limited();
        sv.addView(grid);
        dg[0] = sheet("Share", sv);
    }

    void ask(String title, String init, final U.Str cb) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final EditText e = new EditText(this);
        e.setText(init);
        e.setSingleLine();
        e.setSelectAllOnFocus(true);
        e.setTextColor(U.BROWN);
        e.setTextSize(16);
        e.setPadding(d(16), d(12), d(16), d(12));
        GradientDrawable eb = U.rr(0xFFFFFFFF, d(12));
        eb.setStroke(d(2), U.BUTTER);
        e.setBackground(eb);
        c.addView(e, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.END);
        final Dialog[] dg = new Dialog[1];
        TextView no = btn("Cancel", false), ok = btn("OK", true);
        no.setOnClickListener(v -> dg[0].dismiss());
        ok.setOnClickListener(v -> {
            String s = e.getText().toString().trim();
            dg[0].dismiss();
            if (!s.isEmpty()) cb.on(s);
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.leftMargin = d(10);
        r.addView(no);
        r.addView(ok, bl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = d(14);
        c.addView(r, rl);
        dg[0] = sheet(title, c);
        dg[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        e.requestFocus();
    }

    void move() {
        final List<V> l = selVideos();
        if (l.isEmpty()) return;
        final List<String> names = new ArrayList<>(folders.keySet());
        final Dialog[] dg = new Dialog[1];
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = -1; i < names.size(); i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(d(10), d(8), d(12), d(8));
            row.setBackground(U.glass(this, false, d(12), false));
            U.bounce(row);
            View ic;
            String nm, sb2;
            if (i < 0) {
                TextView plus = new TextView(this);
                plus.setText("+");
                plus.setTextSize(30);
                plus.setTypeface(Typeface.DEFAULT_BOLD);
                plus.setTextColor(U.BUTTER_D);
                plus.setGravity(Gravity.CENTER);
                ic = plus;
                nm = "New folder";
                sb2 = "Nayi folder banao";
            } else {
                ic = new U.FolderIcon(this, false);
                nm = names.get(i);
                sb2 = folders.get(nm).size() + " videos";
            }
            row.addView(ic, new LinearLayout.LayoutParams(d(44), d(38)));
            LinearLayout tc = new LinearLayout(this);
            tc.setOrientation(LinearLayout.VERTICAL);
            TextView a1 = new TextView(this);
            a1.setText(nm);
            a1.setTextColor(U.BROWN);
            a1.setTextSize(14);
            a1.setTypeface(Typeface.DEFAULT_BOLD);
            a1.setSingleLine();
            a1.setEllipsize(TextUtils.TruncateAt.END);
            TextView b1 = new TextView(this);
            b1.setText(sb2);
            b1.setTextColor(0xFF8A6D00);
            b1.setTextSize(12);
            tc.addView(a1);
            tc.addView(b1);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = d(12);
            row.addView(tc, tp);
            row.setOnClickListener(v -> {
                dg[0].dismiss();
                if (k < 0) {
                    ask("New folder name", "", s -> moveTo(l, Environment.getExternalStorageDirectory().getPath() + "/Movies/" + s));
                } else {
                    List<V> fl = folders.get(names.get(k));
                    File dir = new File(fl.get(0).data).getParentFile();
                    moveTo(l, dir.getPath());
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.bottomMargin = d(8);
            list.addView(row, rp);
        }
        ScrollView sv = limited();
        sv.addView(list);
        dg[0] = sheet("Move to", sv);
    }

    void moveTo(final List<V> l, final String dir) {
        write(uris(l), () -> {
            int ok = 0;
            for (V v : l) if (mv(v, dir)) ok++;
            tt(ok + " / " + l.size() + " moved");
            sel.clear();
            load();
        });
    }

    boolean mv(V v, String dir) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String root = Environment.getExternalStorageDirectory().getPath();
                if (!dir.startsWith(root + "/")) return false;
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir.substring(root.length() + 1) + "/");
                return getContentResolver().update(v.uri, cv, null, null) > 0;
            }
            File dst = new File(dir);
            dst.mkdirs();
            File to = new File(dst, v.name);
            if (new File(v.data).renameTo(to)) {
                MediaScannerConnection.scanFile(this, new String[]{v.data, to.getPath()}, null, null);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    boolean ren(V v, String nn) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nn);
                return getContentResolver().update(v.uri, cv, null, null) > 0;
            }
            File f = new File(v.data), t = new File(f.getParentFile(), nn);
            if (f.renameTo(t)) {
                MediaScannerConnection.scanFile(this, new String[]{f.getPath(), t.getPath()}, null, null);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    void rename() {
        if (sel.size() != 1) return;
        Object o = sel.iterator().next();
        if (o instanceof V) {
            final V v = (V) o;
            ask("Rename video", v.name, s -> {
                int dot = v.name.lastIndexOf('.');
                final String nn = s.contains(".") || dot < 0 ? s : s + v.name.substring(dot);
                List<V> one = new ArrayList<>();
                one.add(v);
                write(uris(one), () -> {
                    tt(ren(v, nn) ? "Renamed" : "Rename nahi hua");
                    sel.clear();
                    load();
                });
            });
        } else {
            final String f = (String) o;
            ask("Rename folder", f, s -> {
                List<V> l = new ArrayList<>(folders.get(f));
                File dir = new File(l.get(0).data).getParentFile();
                moveTo(l, new File(dir.getParentFile(), s).getPath());
            });
        }
    }

    // Android 11+: system permission popup for editing media files
    void write(List<Uri> us, Runnable r) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                pend = r;
                startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(), us).getIntentSender(), 7, null, 0, 0, 0);
            } catch (Exception e) {
                pend = null;
                tt("Permission nahi mili");
            }
        } else r.run();
    }

    // ================= list rows =================
    View bind(int i, View v) {
        H h;
        if (v == null) {
            LinearLayout w = new LinearLayout(this);
            w.setPadding(d(4), d(3), d(4), d(3));
            h = new H();
            LinearLayout c = new LinearLayout(this);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(d(8), d(6), d(10), d(6));
            h.card = c;
            U.bounce(c);
            w.addView(c, new LinearLayout.LayoutParams(-1, -2));
            h.th = new FrameLayout(this);
            h.th.setClipToOutline(true);
            h.im = new ImageView(this);
            h.im.setScaleType(ImageView.ScaleType.CENTER_CROP);
            h.fi = new U.FolderIcon(this, dark);
            h.dur = new TextView(this);
            h.dur.setTextColor(Color.WHITE);
            h.dur.setTextSize(10);
            h.dur.setTypeface(Typeface.DEFAULT_BOLD);
            h.dur.setPadding(d(5), d(1), d(5), d(1));
            h.dur.setBackground(U.rr(0xB3000000, d(6)));
            h.pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            h.pb.setMax(1000);
            h.th.addView(h.im, new FrameLayout.LayoutParams(-1, -1));
            h.th.addView(h.fi, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            dl.setMargins(0, 0, d(5), d(7));
            h.th.addView(h.dur, dl);
            h.th.addView(h.pb, new FrameLayout.LayoutParams(-1, d(3), Gravity.BOTTOM));
            c.addView(h.th, new LinearLayout.LayoutParams(d(96), d(58)));
            LinearLayout cl = new LinearLayout(this);
            cl.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            cp.leftMargin = d(10);
            h.a = new TextView(this);
            h.a.setTextSize(13);
            h.a.setTypeface(Typeface.DEFAULT_BOLD);
            h.a.setMaxLines(2);
            h.a.setEllipsize(TextUtils.TruncateAt.END);
            h.b = new TextView(this);
            h.b.setTextSize(11);
            h.b.setPadding(0, d(3), 0, 0);
            cl.addView(h.a);
            cl.addView(h.b);
            c.addView(cl, cp);
            h.chev = new TextView(this);
            h.chev.setTextSize(22);
            c.addView(h.chev);
            w.setTag(h);
            v = w;
        } else h = (H) v.getTag();

        Object o = items.get(i);
        boolean isSel = sel.contains(o), selMode = !sel.isEmpty();
        h.card.setBackground(U.glass(this, dark, d(12), isSel));
        h.a.setTextColor(U.fg(dark));
        h.b.setTextColor(U.fg2(dark));
        h.chev.setTextColor(U.acc(dark));
        h.pb.setProgressTintList(ColorStateList.valueOf(U.acc(dark)));
        h.pb.setProgressBackgroundTintList(ColorStateList.valueOf(0x66000000));
        if (!quiet) {   // fluffy pop-in
            h.card.setAlpha(.4f);
            h.card.setScaleX(.9f);
            h.card.setScaleY(.9f);
            h.card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(2.2f)).start();
        }
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) h.th.getLayoutParams();
        if (o instanceof String) {
            String f = (String) o;
            h.id = -1;
            tp.width = d(54);
            tp.height = d(44);
            h.th.setBackground(null);
            h.fi.setVisibility(View.VISIBLE);
            h.im.setVisibility(View.GONE);
            h.dur.setVisibility(View.GONE);
            h.pb.setVisibility(View.GONE);
            h.chev.setVisibility(View.VISIBLE);
            h.chev.setText(selMode ? (isSel ? "\u2714" : "\u25CB") : "\u203A");
            h.a.setText(f);
            h.b.setText(folders.get(f).size() + " videos");
        } else {
            V vd = (V) o;
            tp.width = d(96);
            tp.height = d(58);
            h.th.setBackground(U.rr(dark ? U.STONE2 : 0xFFFFF3B8, d(12)));
            h.fi.setVisibility(View.GONE);
            h.im.setVisibility(View.VISIBLE);
            h.chev.setVisibility(selMode ? View.VISIBLE : View.GONE);
            h.chev.setText(isSel ? "\u2714" : "\u25CB");
            h.dur.setVisibility(View.VISIBLE);
            h.dur.setText(U.t(vd.dur));
            h.a.setText(vd.name);
            h.b.setText(U.sz(vd.size));
            long p = sp.getLong("p" + vd.id, 0);
            if (p > 0 && vd.dur > 0) {
                h.pb.setVisibility(View.VISIBLE);
                h.pb.setProgress((int) (p * 1000 / vd.dur));
            } else h.pb.setVisibility(View.GONE);
            thumb(h, vd);
        }
        h.th.setLayoutParams(tp);
        return v;
    }

    void thumb(final H h, final V v) {
        h.id = v.id;
        Bitmap b = cache.get(v.id);
        if (b != null) {
            h.im.setImageBitmap(b);
            return;
        }
        h.im.setImageBitmap(null);
        ex.execute(() -> {
            Bitmap t = null;
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    t = getContentResolver().loadThumbnail(v.uri, new Size(240, 150), null);
                else
                    t = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), v.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
            } catch (Exception ignored) {
            }
            if (t != null) {
                cache.put(v.id, t);
                final Bitmap f = t;
                runOnUiThread(() -> {
                    if (h.id == v.id) h.im.setImageBitmap(f);
                });
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (tab == 1) {
            setTab(0);
        } else if (sb != null && sb.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else if (!sel.isEmpty()) {
            sel.clear();
            refresh();
        } else if (cur != null) {
            cur = null;
            refresh();
            lv.setSelection(0);
        } else super.onBackPressed();
    }
}
