package com.mkr.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Matrix;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

final class U {
    // Light = white bg | Dark = stone black bg | accent butter yellow in both
    static final int BUTTER = 0xFFFFE27A, BUTTER_D = 0xFFF2B705, STONE = 0xFF1B1A19, STONE2 = 0xFF2B2927,
            PETAL = 0xFFF2B705, PETAL_D = 0xFFF2B705, BROWN = 0xFF4A3800;

    interface Pick { void on(int i); }
    interface Str { void on(String s); }

    static int acc(boolean d) { return BUTTER_D; }
    static int fg(boolean d) { return d ? 0xFFF5F1EC : BROWN; }
    static int fg2(boolean d) { return d ? 0xFFC9C2B8 : 0xFF8A6D00; }
    static int onAcc(boolean d) { return 0xFFFFFFFF; }

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + .5f); }

    static GradientDrawable rr(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    static GradientDrawable grad(int c1, int c2, int r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(r);
        return g;
    }

    // liquid-glass list card (folders / videos)
    static GradientDrawable glass(Context c, boolean dark, int r, boolean sel) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dark ? new int[]{0xF2343230, 0xF2262422, 0xF2343230} : new int[]{0xF7FFFFFF, 0xF2FFF4C8, 0xF7FFFFFF});
        g.setCornerRadius(r);
        if (sel) g.setStroke(dp(c, 3), BUTTER_D);
        else g.setStroke(dp(c, 1), dark ? 0x61FFFFFF : 0x8CFFCD46);
        return g;
    }

    // white frosted glass for bars / popups / sheets (same in light and dark)
    static GradientDrawable pglass(Context c, boolean dark, int r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xEBFFFFFF, 0xD6FFF3B8});
        g.setCornerRadius(dp(c, 12));
        g.setStroke(dp(c, 1), 0xFFFFFFFF);
        return g;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static String t(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = s % 3600 / 60;
        s %= 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    static String sz(long b) {
        return b >= (1L << 30) ? String.format("%.1f GB", b / 1073741824.0) : (b >> 20) + " MB";
    }

    // fluffy press bounce
    static void bounce(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == 0)
                x.animate().scaleX(.92f).scaleY(.92f).setDuration(90).setInterpolator(new DecelerateInterpolator()).start();
            else if (a == 1 || a == 3)
                x.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(3.5f)).start();
            return false;
        });
    }

    // drop-down list under a button
    static void dropdown(Context c, View anchor, boolean dark, String[] items, int sel, Pick cb) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 6);
        l.setPadding(p, p, p, p);
        l.setBackground(pglass(c, dark, dp(c, 18)));
        final PopupWindow pw = new PopupWindow(l, -2, -2, true);
        for (int i = 0; i < items.length; i++) {
            TextView t = new TextView(c);
            t.setText(items[i]);
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(i == sel ? 0xFFFFFFFF : BROWN);
            t.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
            if (i == sel) t.setBackground(rr(acc(dark), dp(c, 12)));
            final int k = i;
            t.setOnClickListener(v -> {
                pw.dismiss();
                cb.on(k);
            });
            bounce(t);
            l.addView(t);
        }
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(c, 10));
        l.setPivotY(0);
        l.setScaleY(.5f);
        l.setAlpha(0f);
        l.animate().scaleY(1f).alpha(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2f)).start();
        pw.showAsDropDown(anchor, 0, dp(c, 6));
    }

    // ---------- blurred media-shape background (play / film strip / music note) ----------
    static class LiquidBg extends View {
        final boolean dark;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path pa = new Path(), st = new Path();

        LiquidBg(Context c, boolean d) {
            super(c);
            dark = d;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            p.setMaskFilter(new BlurMaskFilter(Math.max(2f, w * 0.035f), BlurMaskFilter.Blur.NORMAL));
        }

        void shape(Canvas cv, int k, float cx, float cy, float s, float rot, int col) {
            float f = s / 100f;
            Matrix m = new Matrix();
            m.postTranslate(-50, -50);
            m.postScale(f, f);
            m.postRotate(rot);
            m.postTranslate(cx, cy);
            p.setColor(col);
            p.setStrokeJoin(Paint.Join.ROUND);
            pa.reset();
            if (k == 0) {
                pa.moveTo(24, 12);
                pa.lineTo(90, 50);
                pa.lineTo(24, 88);
                pa.close();
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                p.setStrokeWidth(12 * f);
                pa.transform(m);
                cv.drawPath(pa, p);
            } else if (k == 1) {
                pa.setFillType(Path.FillType.EVEN_ODD);
                pa.addRoundRect(8, 22, 92, 78, 12, 12, Path.Direction.CW);
                float[] hx = {15, 77};
                float[] hy = {31, 46, 61};
                for (float x : hx) for (float y : hy) pa.addRect(x, y, x + 8, y + 8, Path.Direction.CW);
                pa.addRect(34, 34, 66, 66, Path.Direction.CW);
                p.setStyle(Paint.Style.FILL);
                pa.transform(m);
                cv.drawPath(pa, p);
            } else {
                pa.addCircle(28, 78, 16, Path.Direction.CW);
                pa.addCircle(76, 66, 16, Path.Direction.CW);
                pa.moveTo(40, 20);
                pa.lineTo(92, 8);
                pa.lineTo(92, 30);
                pa.lineTo(40, 42);
                pa.close();
                p.setStyle(Paint.Style.FILL);
                pa.transform(m);
                cv.drawPath(pa, p);
                st.reset();
                st.moveTo(42, 78);
                st.lineTo(42, 22);
                st.moveTo(90, 66);
                st.lineTo(90, 12);
                st.transform(m);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(10 * f);
                cv.drawPath(st, p);
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            cv.drawColor(dark ? STONE : 0xFFFFFFFF);
            float w = getWidth(), h = getHeight();
            int a = 0xFFFFE27A, b = 0xFFFFD54A;
            shape(cv, 0, w * .10f, h * .36f, w * .38f, -12, a);
            shape(cv, 1, w * .84f, h * .55f, w * .40f, 10, b);
            shape(cv, 2, w * .16f, h * .66f, w * .32f, -8, a);
            shape(cv, 0, w * .60f, h * .72f, w * .26f, 14, b);
        }
    }

    // ---------- small sun | moon switch ----------
    static class ThemeSwitch extends View {
        boolean dark;
        float pos;
        Runnable cb;
        ValueAnimator va;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ThemeSwitch(Context c, boolean d) {
            super(c);
            dark = d;
            pos = d ? 1 : 0;
            setOnClickListener(v -> flip());
            bounce(this);
        }

        void flip() {
            if (va != null && va.isRunning()) return;
            final boolean nd = !dark;
            va = ValueAnimator.ofFloat(pos, nd ? 1 : 0);
            va.setDuration(320);
            va.setInterpolator(new OvershootInterpolator(1.6f));
            va.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    dark = nd;
                    if (cb != null) cb.run();
                }
            });
            va.start();
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(dp(getContext(), 46), dp(getContext(), 22));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2, u = h / 28f;
            float q = Math.max(0f, Math.min(1f, pos));
            RectF rc = new RectF(u, u, w - u, h - u);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mix(0xFFFFF3B8, STONE2, q));
            cv.drawRoundRect(rc, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * u);
            p.setColor(mix(BUTTER_D, PETAL_D, q));
            cv.drawRoundRect(rc, r, r, p);
            float kr = r - 3 * u, kx = r + pos * (w - 2 * r);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mix(BUTTER_D, PETAL, q));
            cv.drawCircle(kx, r, kr, p);
            int ic = mix(0xFFFFFFFF, STONE, q);
            // sun
            p.setColor(ic);
            p.setAlpha((int) (255 * (1 - q)));
            cv.drawCircle(kx, r, 3.6f * u, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * u);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                cv.drawLine(kx + cs * 5.4f * u, r + sn * 5.4f * u, kx + cs * 7.2f * u, r + sn * 7.2f * u, p);
            }
            // moon
            p.setStyle(Paint.Style.FILL);
            p.setColor(ic);
            p.setAlpha((int) (255 * q));
            Path a1 = new Path(), b1 = new Path();
            a1.addCircle(kx, r, 6f * u, Path.Direction.CW);
            b1.addCircle(kx + 3.2f * u, r - 2.6f * u, 5f * u, Path.Direction.CW);
            a1.op(b1, Path.Op.DIFFERENCE);
            cv.drawPath(a1, p);
        }
    }

    // plain back arrow (no circle)
    static class BackBtn extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackBtn(Context c, int col) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(col);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            bounce(this);
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(dp(getContext(), 40), dp(getContext(), 40));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            p.setStrokeWidth(w * 0.075f);
            Path c = new Path();
            c.moveTo(w * 0.78f, w * 0.5f);
            c.lineTo(w * 0.24f, w * 0.5f);
            c.moveTo(w * 0.46f, w * 0.28f);
            c.lineTo(w * 0.24f, w * 0.5f);
            c.lineTo(w * 0.46f, w * 0.72f);
            cv.drawPath(c, p);
        }
    }

    static class SearchIcon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchIcon(Context c, int col) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(col);
            p.setStrokeCap(Paint.Cap.ROUND);
            bounce(this);
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(dp(getContext(), 40), dp(getContext(), 40));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float u = getWidth() / 40f;
            p.setStrokeWidth(2.6f * u);
            cv.drawCircle(18 * u, 18 * u, 8 * u, p);
            cv.drawLine(24 * u, 24 * u, 30 * u, 30 * u, p);
        }
    }

    static class FolderIcon extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final boolean dark;
        LinearGradient body;

        FolderIcon(Context c, boolean d) {
            super(c);
            dark = d;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            body = new LinearGradient(0, h * 0.2f, 0, h, 0xFFFFEB90, BUTTER_D, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rd = h * 0.14f;
            p.setShader(null);
            p.setColor(0xFFFFD95A);
            r.set(0, h * 0.08f, w * 0.46f, h * 0.5f);
            cv.drawRoundRect(r, rd, rd, p);
            p.setShader(body);
            r.set(0, h * 0.22f, w, h * 0.96f);
            cv.drawRoundRect(r, rd, rd, p);
            p.setShader(null);
            p.setColor(0x88FFFFFF);
            r.set(w * 0.06f, h * 0.29f, w * 0.94f, h * 0.34f);
            cv.drawRoundRect(r, rd / 3, rd / 3, p);
        }
    }

    // scene popup: thumbnail + mini timeline (start mark, target mark, forward/back range)
    static class ScrubView extends View {
        final boolean dark;
        Bitmap bmp;
        long st, tg, du;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), dst = new RectF();
        final Path clip = new Path();

        ScrubView(Context c, boolean d) {
            super(c);
            dark = d;
            setBackground(pglass(c, d, dp(c, 20)));
        }

        void set(long s, long t, long d) {
            st = s;
            tg = t;
            du = d;
            invalidate();
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(dp(getContext(), 208), dp(getContext(), 172));
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float W = getWidth(), H = getHeight(), pad = dp(c, 10), rd = dp(c, 12);
            r.set(pad, pad, W - pad, pad + dp(c, 112));
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            cv.drawRoundRect(r, rd, rd, p);
            if (bmp != null) {
                cv.save();
                clip.reset();
                clip.addRoundRect(r, rd, rd, Path.Direction.CW);
                cv.clipPath(clip);
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                float s = Math.max(r.width() / bw, r.height() / bh);
                dst.set(r.centerX() - bw * s / 2, r.centerY() - bh * s / 2, r.centerX() + bw * s / 2, r.centerY() + bh * s / 2);
                cv.drawBitmap(bmp, null, dst, p);
                cv.restore();
            }
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(c, 14));
            p.setColor(BROWN);
            p.setTextAlign(Paint.Align.LEFT);
            float ty = r.bottom + dp(c, 22);
            cv.drawText(t(tg), pad, ty, p);
            long df = tg - st;
            p.setColor(acc(dark));
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText((df >= 0 ? "+" : "-") + t(Math.abs(df)), W - pad, ty, p);
            // mini timeline
            float y = H - dp(c, 14), x0 = pad, x1 = W - pad;
            p.setColor(0x55A07800);
            r.set(x0, y - dp(c, 3), x1, y + dp(c, 3));
            cv.drawRoundRect(r, dp(c, 3), dp(c, 3), p);
            if (du > 0) {
                float xs = x0 + (x1 - x0) * Math.min(1f, st / (float) du);
                float xt = x0 + (x1 - x0) * Math.max(0f, Math.min(1f, tg / (float) du));
                p.setColor(acc(dark));
                r.set(Math.min(xs, xt), y - dp(c, 3), Math.max(xs, xt), y + dp(c, 3));
                cv.drawRect(r, p);
                p.setColor(BROWN);
                cv.drawRect(xs - dp(c, 1), y - dp(c, 7), xs + dp(c, 1), y + dp(c, 7), p);
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(xt, y, dp(c, 7), p);
                p.setColor(acc(dark));
                cv.drawCircle(xt, y, dp(c, 4), p);
            }
        }
    }
}
