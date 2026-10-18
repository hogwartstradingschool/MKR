package com.mkr.player;

import android.content.Context;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

// App crash ho to wajah file mein likh leta hai; agli baar app khulne par dikha deta hai
final class Crash {
    static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        if (old != null && old.getClass().getName().contains("Crash")) return;
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                java.io.FileWriter w = new java.io.FileWriter(new File(app.getFilesDir(), "crash.txt"));
                w.write(sw.toString());
                w.close();
            } catch (Throwable ignored) {
            }
            if (old != null) old.uncaughtException(t, e);
        });
    }

    static void save(Context c, String text) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(c.getFilesDir(), "crash.txt"));
            w.write(text);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    static String take(Context c) {
        try {
            File f = new File(c.getFilesDir(), "crash.txt");
            if (!f.exists()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder b = new StringBuilder();
            String l;
            int n = 0;
            while ((l = r.readLine()) != null && n++ < 14) b.append(l).append('\n');
            r.close();
            f.delete();
            return b.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
