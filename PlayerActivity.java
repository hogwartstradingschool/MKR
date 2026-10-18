package com.mkr.player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity implements
        GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    static final String[] ASP = {"FIT", "STRETCH", "CROP"};
    ExoPlayer player;
    SurfaceView sv;
    FrameLayout root;
    LinearLayout top, bar;
    TextView titleTv, curTv, durTv, hud, playBtn, boostBtn, lockBtn, aspBtn, audBtn;
    U.ScrubView scrub;
    boolean dark, wasPlaying;
    int fg, acc, holdRate;
    float boostMax = 1f;
    long scrubStart;
    volatile MediaMetadataRetriever mmr;
    volatile long want = -1;
    volatile boolean busy;
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    SeekBar sb;
    ArrayList<String> uris, names;
    long[] ids;
    int idx, vw, vh, mode, aspect;
    long startPos, targetPos;
    boolean ready, oriSet, land, locked, external, inPip, dragging, showUi = true;
    float vf = 1f, startVf, startBr;
    SharedPreferences sp;
    AudioManager am;
    LoudnessEnhancer le;
    GestureDetector gd;
    final Handler h = new Handler();

    int d(int v) { return U.dp(this, v); }

    TextView tv(String s, float sz) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setShadowLayer(d(4), 0, d(1), 0xBF000000);
        t.setTextSize(sz);
        t.setGravity(Gravity.CENTER);
        U.bounce(t);
        t.setPadding(d(12), d(8), d(12), d(8));
        return t;
    }

    TextView lab(String s) {
        TextView t = tv(s, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(d(10), d(6), d(10), d(6));
        return t;
    }

    boolean parse(Intent in) {
        ArrayList<String> u = in.getStringArrayListExtra("uris");
        if (u == null) {
            Uri x = in.getData();
            if (x == null) return false;
            uris = new ArrayList<>();
            uris.add(x.toString());
            names = new ArrayList<>();
            String n = x.getLastPathSegment();
            names.add(n == null ? "Video" : n);
            ids = new long[]{x.toString().hashCode()};
            idx = 0;
            external = true;
        } else {
            uris = u;
            names = in.getStringArrayListExtra("names");
            ids = in.getLongArrayExtra("ids");
            idx = in.getIntExtra("i", 0);
            external = false;
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Crash.install(this);
        sp = getSharedPreferences("s", 0);
        dark = sp.getBoolean("dark", true);
        fg = Color.WHITE;
        acc = U.acc(dark);
        boostMax = sp.getFloat("bm", 1f);
        aspect = sp.getInt("asp", 0);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (!parse(getIntent())) {
            finish();
            return;
        }
        int mx = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        vf = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) mx;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        hud = tv("", 22);
        hud.setTypeface(Typeface.DEFAULT_BOLD);
        hud.setTextColor(U.BROWN);
        hud.setShadowLayer(0, 0, 0, 0);
        hud.setPadding(d(22), d(12), d(22), d(12));
        hud.setBackground(U.pglass(this, dark, d(30)));
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        scrub = new U.ScrubView(this, dark);
        scrub.setVisibility(View.GONE);
        root.addView(scrub, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ----- top glass bar -----
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(d(6), d(4), d(8), d(4));
        
        U.BackBtn back = new U.BackBtn(this, fg);
        back.setOnClickListener(v -> finish());
        titleTv = tv("", 15);
        titleTv.setGravity(Gravity.CENTER_VERTICAL);
        titleTv.setSingleLine();
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        audBtn = lab("AUDIO");
        audBtn.setOnClickListener(v -> audioDialog());
        boostBtn = lab("BOOST");
        boostBtn.setOnClickListener(v -> boostDialog());
        top.addView(back, new LinearLayout.LayoutParams(-2, -2));
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(audBtn);
        top.addView(boostBtn);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tlp.setMargins(d(12), d(12), d(12), 0);
        root.addView(top, tlp);

        // ----- bottom glass panel -----
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(d(14), d(10), d(14), d(8));
        
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        curTv = tv("0:00", 13);
        durTv = tv("0:00", 13);
        sb = new SeekBar(this);
        sb.setProgressTintList(ColorStateList.valueOf(acc));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        sb.setThumbTintList(ColorStateList.valueOf(acc));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) {
                if (u) {
                    curTv.setText(U.t(p));
                    scrubShow(scrubStart, p);
                }
            }
            public void onStartTrackingTouch(SeekBar b) {
                dragging = true;
                scrubStart = ready ? player.getCurrentPosition() : 0;
            }
            public void onStopTrackingTouch(SeekBar b) {
                dragging = false;
                if (ready) player.seekTo(b.getProgress());
                h.removeCallbacks(hideHud);
                h.postDelayed(hideHud, 600);
            }
        });
        r1.addView(curTv);
        r1.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
        r1.addView(durTv);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        lockBtn = lab("LOCK");
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            applyOri();
            paintBtns();
            say(locked ? "Rotation locked" : "Auto rotation");
        });
        TextView rotBtn = tv("\u27F3", 22);
        rotBtn.setOnClickListener(v -> {
            land = !land;
            locked = false;
            applyOri();
            paintBtns();
        });
        TextView prev = tv("|\u25C0", 20), next = tv("\u25B6|", 20);
        prev.setOnClickListener(v -> step(-1));
        next.setOnClickListener(v -> step(1));
        playBtn = tv("\u275A\u275A", 22);
        
        playBtn.setTextColor(Color.WHITE);
        playBtn.setOnClickListener(v -> toggle());
        aspBtn = lab(ASP[aspect]);
        aspBtn.setOnClickListener(v -> {
            aspect = (aspect + 1) % 3;
            sp.edit().putInt("asp", aspect).apply();
            aspBtn.setText(ASP[aspect]);
            fit();
            say(ASP[aspect]);
        });
        LinearLayout pc = new LinearLayout(this);
        pc.setGravity(Gravity.CENTER);
        pc.addView(playBtn, new LinearLayout.LayoutParams(d(60), d(60)));
        row(r2, lockBtn);
        row(r2, rotBtn);
        row(r2, prev);
        r2.addView(pc, new LinearLayout.LayoutParams(0, -2, 1.3f));
        row(r2, next);
        row(r2, aspBtn);
        bar.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(r2, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        blp.setMargins(d(12), 0, d(12), d(14));
        root.addView(bar, blp);
        paintBtns();

        gd = new GestureDetector(this, this);
        gd.setOnDoubleTapListener(this);
        root.setOnTouchListener((v, e) -> {
            if (inPip) return false;
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endGesture();
            return true;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (r - l != orr - ol || b - t != ob - ot) fit();
        });
        setContentView(root);
        imm();

        // Pichli playback beech mein hi mar gayi thi (native crash) => safe mode: sirf phone ke apne decoders
        if (sp.getBoolean("playing", false)) sp.edit().putBoolean("safe", true).apply();
        sp.edit().putBoolean("playing", true).apply();
        boolean safe = sp.getBoolean("safe", false);
        if (safe) Toast.makeText(this, "Safe mode: phone ke decoder", Toast.LENGTH_SHORT).show();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(safe ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, rf).build();
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(sv);
        player.addListener(listener);
        h.post(tick);
        open(idx);
    }

    void row(LinearLayout r, View v) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER);
        c.addView(v);
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
    }

    void paintBtns() {
        boolean b = boostMax > 1f;
        boostBtn.setText(b ? ((int) boostMax) + "X" : "BOOST");
        boostBtn.setBackground(b ? U.rr(acc, d(14)) : null);
        boostBtn.setTextColor(b ? U.onAcc(dark) : fg);
        lockBtn.setBackground(locked ? U.rr(acc, d(14)) : null);
        lockBtn.setTextColor(locked ? U.onAcc(dark) : fg);
    }

    void applyOri() {
        if (locked) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        else setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (player != null && parse(in)) {
            setIntent(in);
            open(idx);
        }
    }

    final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int st) {
            if (st == Player.STATE_READY && !ready) {
                ready = true;
                long du = dur();
                sb.setMax((int) du);
                durTv.setText(U.t(du));
                setupLE(player.getAudioSessionId());
                applyVol();
            }
            if (st == Player.STATE_ENDED) {
                sp.edit().remove("p" + ids[idx]).apply();
                if (idx + 1 < uris.size()) open(idx + 1);
                else finish();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean p) {
            playBtn.setText(p ? "\u275A\u275A" : "\u25B6");
            if (p) autoHide();
            else if (!inPip && holdRate == 0) showUi(true);
        }

        @Override
        public void onVideoSizeChanged(VideoSize v) {
            if (v.width == 0 || v.height == 0) return;
            vw = (int) (v.width * v.pixelWidthHeightRatio);
            vh = v.height;
            fit();
            if (!oriSet) {
                oriSet = true;
                land = vw >= vh;   // landscape video -> landscape, portrait video -> portrait
                applyOri();
            }
        }

        @Override
        public void onAudioSessionIdChanged(int id) { setupLE(id); }

        @Override
        public void onPlayerError(PlaybackException e) {
            Toast.makeText(PlayerActivity.this, "Video play nahi hui (" + e.getErrorCodeName() + ")", Toast.LENGTH_LONG).show();
            Throwable c = e.getCause();
            Crash.save(PlayerActivity.this, "PLAYER ERROR: " + e.getErrorCodeName() + "\n" + e.getMessage()
                    + (c != null ? "\nCause: " + c : "") + "\nFile: " + (idx < names.size() ? names.get(idx) : "?"));
            if (idx + 1 < uris.size()) open(idx + 1);
            else finish();
        }
    };

    long dur() {
        long du = player.getDuration();
        return du == C.TIME_UNSET || du < 0 ? 0 : du;
    }

    void open(int i) {
        savePos();
        idx = i;
        ready = false;
        oriSet = false;
        titleTv.setText(names.get(i));
        sb.setProgress(0);
        if (!external) sp.edit().putLong("lastId", ids[i]).apply();
        long saved = sp.getLong("p" + ids[i], 0);
        final Uri cu = Uri.parse(uris.get(i));
        ex.execute(() -> {
            try {
                MediaMetadataRetriever m = new MediaMetadataRetriever();
                m.setDataSource(PlayerActivity.this, cu);
                MediaMetadataRetriever old = mmr;
                mmr = m;
                if (old != null) old.release();
            } catch (Exception ignored) {
            }
        });
        player.setMediaItem(MediaItem.fromUri(cu), saved > 5000 ? saved : 0);
        player.prepare();
        player.play();
    }

    final Runnable tick = new Runnable() {
        public void run() {
            try {
                if (ready && player != null) {
                    long c = player.getCurrentPosition();
                    if (!dragging) {
                        sb.setProgress((int) c);
                        curTv.setText(U.t(c));
                    }
                }
            } catch (Exception ignored) {
            }
            h.postDelayed(this, 500);
        }
    };

    void toggle() {
        if (!ready) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    void step(int dir) {
        int n = idx + dir;
        if (n >= 0 && n < uris.size()) open(n);
        else if (ready) player.seekTo(0);
    }

    void seekBy(int ms) {
        if (!ready) return;
        long mx = dur() > 0 ? dur() : Long.MAX_VALUE;
        player.seekTo(Math.max(0, Math.min(mx, player.getCurrentPosition() + ms)));
        say((ms > 0 ? "+" : "-") + Math.abs(ms / 1000) + "s");
    }

    void savePos() {
        try {
            if (ready && player != null && ids != null) {
                long c = player.getCurrentPosition();
                if (c > dur() - 5000) sp.edit().remove("p" + ids[idx]).apply();
                else sp.edit().putLong("p" + ids[idx], c).apply();
            }
        } catch (Exception ignored) {
        }
    }

    void fit() {
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0 || vw == 0 || vh == 0) return;
        int w, hh;
        if (aspect == 1) {
            w = W;
            hh = H;
        } else {
            float sx = (float) W / vw, sy = (float) H / vh;
            float s = aspect == 0 ? Math.min(sx, sy) : Math.max(sx, sy);
            w = (int) (vw * s);
            hh = (int) (vh * s);
        }
        sv.setLayoutParams(new FrameLayout.LayoutParams(w, hh, Gravity.CENTER));
    }

    // ---------- popup (picture-in-picture) ----------
    boolean goPip() {
        if (Build.VERSION.SDK_INT < 26 || !ready || player == null || !player.isPlaying()) return false;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false;
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (vw > 0 && vh > 0) {
                float r = Math.max(0.45f, Math.min(2.3f, (float) vw / vh));
                b.setAspectRatio(new Rational((int) (r * 1000), 1000));
            }
            inPip = true;
            enterPictureInPictureMode(b.build());
            return true;
        } catch (Exception e) {
            inPip = false;
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        if (!goPip()) super.onBackPressed();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        goPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration cfg) {
        super.onPictureInPictureModeChanged(in, cfg);
        inPip = in;
        if (in) {
            top.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
            hud.setVisibility(View.GONE);
        } else showUi(true);
    }

    // ---------- audio / volume ----------
    void audioDialog() {
        if (!ready) return;
        final List<TrackSelectionOverride> ov = new ArrayList<>();
        List<String> nm = new ArrayList<>();
        int sel = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO || g.length == 0) continue;
            Format f = g.getTrackFormat(0);
            String s = "Audio " + (ov.size() + 1);
            if (f.language != null && !f.language.equals("und")) s += " (" + f.language + ")";
            if (f.sampleMimeType != null) s += "  " + f.sampleMimeType.substring(f.sampleMimeType.indexOf('/') + 1);
            if (f.channelCount > 0) s += "  " + f.channelCount + "ch";
            if (g.isSelected()) sel = ov.size();
            nm.add(s);
            ov.add(new TrackSelectionOverride(g.getMediaTrackGroup(), 0));
        }
        if (ov.isEmpty()) {
            Toast.makeText(this, "Audio track nahi mila", Toast.LENGTH_SHORT).show();
            return;
        }
        U.dropdown(this, audBtn, dark, nm.toArray(new String[0]), sel, w ->
                player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                        .buildUpon().setOverrideForType(ov.get(w)).build()));
    }

    static final String[] BL = {"500X", "400X", "300X", "200X", "OFF"};
    static final float[] BV = {5f, 4f, 3f, 2f, 1f};

    void boostDialog() {
        int sel = BL.length - 1;
        for (int i = 0; i < BV.length; i++) if (BV[i] == boostMax) sel = i;
        U.dropdown(this, boostBtn, dark, BL, sel, i -> setBoost(BV[i]));
    }

    void setBoost(float m) {
        boostMax = m;
        sp.edit().putFloat("bm", m).apply();
        if (vf > m) vf = m;
        applyVol();
        paintBtns();
        say(m > 1f ? "Boost " + (int) m + "X" : "Boost OFF");
    }

    void setupLE(int session) {
        try {
            if (le != null) le.release();
        } catch (Exception ignored) {
        }
        le = null;
        if (session == 0) return;
        try {
            le = new LoudnessEnhancer(session);
            le.setEnabled(true);
            applyVol();
        } catch (Exception e) {
            le = null;
        }
    }

    void applyVol() {
        try {
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.min(vf, 1f) * mx), 0);
        } catch (Exception ignored) {
        }
        try {
            if (le != null) le.setTargetGain(vf > 1f ? (int) (2000 * Math.log10(vf)) : 0);
        } catch (Exception ignored) {
        }
    }

    float br() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b < 0) {
            try {
                b = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
            } catch (Exception e) {
                b = .5f;
            }
        }
        return b;
    }

    // ---------- gestures ----------
    @Override
    public boolean onDown(MotionEvent e) {
        mode = 0;
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
        if (e1 == null || !ready) return false;
        int W = root.getWidth(), H = root.getHeight();
        if (mode == 0) {
            if (Math.abs(e2.getX() - e1.getX()) > Math.abs(e2.getY() - e1.getY())) {
                mode = 1;
                startPos = player.getCurrentPosition();
            } else if (e1.getX() < W / 2f) {
                mode = 2;
                startBr = br();
            } else {
                mode = 3;
                if (vf <= 1f) {
                    int mx = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                    vf = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) mx;
                }
                startVf = vf;
            }
        }
        float up = (e1.getY() - e2.getY()) / H * 1.2f;
        if (mode == 1) {
            long delta = (long) ((e2.getX() - e1.getX()) / W * 120000);
            long mx = dur() > 0 ? dur() : Long.MAX_VALUE;
            targetPos = Math.max(0, Math.min(mx, startPos + delta));
            scrubShow(startPos, targetPos);
        } else if (mode == 2) {
            float b = Math.max(0.02f, Math.min(1f, startBr + up));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = b;
            getWindow().setAttributes(lp);
            say("\u2600 " + Math.round(b * 100) + "%");
        } else if (mode == 3) {
            float mxv = boostMax;
            vf = Math.max(0f, Math.min(mxv, startVf + up * mxv));
            applyVol();
            say("\uD83D\uDD0A " + Math.round(vf * 100) + "%");
        }
        return true;
    }

    void endGesture() {
        if (holdRate != 0) {
            holdRate = 0;
            h.removeCallbacks(rw);
            player.setPlaybackParameters(PlaybackParameters.DEFAULT);
            if (wasPlaying) player.play();
            else player.pause();
        } else if (mode == 1 && ready) player.seekTo(targetPos);
        mode = 0;
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 600);
    }

    final Runnable hideHud = () -> {
        hud.setVisibility(View.GONE);
        scrub.setVisibility(View.GONE);
    };

    // hold on 4 invisible zones: L1 = 4x back, L2 = 2x back, R3 = 2x forward, R4 = 4x forward
    final Runnable rw = new Runnable() {
        public void run() {
            if (holdRate >= 0 || player == null) return;
            long p = Math.max(0, player.getCurrentPosition() + holdRate * 150L);
            player.seekTo(p);
            if (p > 0) h.postDelayed(this, 150);
        }
    };

    void scrubShow(long st, long tg) {
        scrub.set(st, tg, dur());
        scrub.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideHud);
        thumbAt(tg);
    }

    void thumbAt(long ms) {
        want = ms;
        if (busy) return;
        busy = true;
        ex.execute(() -> {
            long w;
            while ((w = want) >= 0) {
                want = -1;
                Bitmap b = null;
                try {
                    MediaMetadataRetriever m = mmr;
                    if (m != null) b = m.getFrameAtTime(w * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b != null && b.getWidth() > 360)
                        b = Bitmap.createScaledBitmap(b, 360, Math.max(1, b.getHeight() * 360 / b.getWidth()), true);
                } catch (Exception ignored) {
                }
                final Bitmap f = b;
                if (f != null) runOnUiThread(() -> {
                    scrub.bmp = f;
                    scrub.invalidate();
                });
            }
            busy = false;
        });
    }

    void say(String s) {
        hud.setText(s);
        hud.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideHud);
        h.postDelayed(hideHud, 800);
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        seekBy(e.getX() > root.getWidth() / 2f ? 30000 : -30000);
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        showUi(!showUi);
        return true;
    }

    public boolean onDoubleTapEvent(MotionEvent e) { return false; }
    public void onShowPress(MotionEvent e) { }
    public boolean onSingleTapUp(MotionEvent e) { return false; }
    public void onLongPress(MotionEvent e) {
        if (!ready || inPip || mode != 0) return;
        int z = Math.max(0, Math.min(3, (int) (e.getX() * 4 / root.getWidth())));
        int[] rt = {-4, -2, 2, 4};
        holdRate = rt[z];
        mode = 9;
        wasPlaying = player.isPlaying();
        hud.setText((holdRate < 0 ? "\u25C0\u25C0  " : "") + Math.abs(holdRate) + "x" + (holdRate > 0 ? "  \u25B6\u25B6" : ""));
        hud.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideHud);
        if (holdRate > 0) {
            player.setPlaybackParameters(new PlaybackParameters(holdRate));
            player.play();
        } else {
            player.pause();
            h.post(rw);
        }
    }
    public boolean onFling(MotionEvent a, MotionEvent b, float x, float y) { return false; }

    // ---------- UI ----------
    final Runnable hideUi = () -> {
        if (player != null && player.isPlaying()) showUi(false);
    };

    void showUi(boolean s) {
        showUi = s;
        top.setVisibility(s ? View.VISIBLE : View.GONE);
        bar.setVisibility(s ? View.VISIBLE : View.GONE);
        if (s) autoHide();
    }

    void autoHide() {
        h.removeCallbacks(hideUi);
        h.postDelayed(hideUi, 3500);
    }

    void imm() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) imm();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePos();
        if (player != null && !inPip) player.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (inPip) {   // popup window band kar di gayi
            if (player != null) player.pause();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sp.edit().putBoolean("playing", false).apply();
        h.removeCallbacksAndMessages(null);
        try {
            if (le != null) le.release();
        } catch (Exception ignored) {
        }
        ex.execute(() -> {
            try {
                if (mmr != null) mmr.release();
            } catch (Exception ignored) {
            }
        });
        ex.shutdown();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
