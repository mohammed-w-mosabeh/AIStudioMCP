package aistudiomcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Util {
    private Util() {}

    /** Run on the Swing EDT and wait (with timeout so a modal dialog can never hang the HTTP thread). */
    static <T> T edt(Callable<T> c) throws Exception {
        return edt(c, 15000);
    }

    static <T> T edt(Callable<T> c, long timeoutMs) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return c.call();
        FutureTask<T> f = new FutureTask<>(c);
        SwingUtilities.invokeLater(f);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for the UI thread (a modal dialog may be open; inspect it with ui_tree)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    /** Fire-and-forget on the EDT; used for actions that may open modal dialogs. */
    static void later(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    static String str(JsonObject a, String k, String def) {
        JsonElement e = a.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    static String req(JsonObject a, String k) {
        String v = str(a, k, null);
        if (v == null) throw new IllegalArgumentException("missing argument: " + k);
        return v;
    }

    static int num(JsonObject a, String k, int def) {
        JsonElement e = a.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsInt();
    }

    static Integer numOrNull(JsonObject a, String k) {
        JsonElement e = a.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsInt();
    }

    static boolean bool(JsonObject a, String k, boolean def) {
        JsonElement e = a.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    static String trunc(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
