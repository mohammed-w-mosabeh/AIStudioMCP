package aistudiomcp;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Ring buffer of java.util.logging records (AI Studio and process logs). */
final class LogCapture extends Handler {
    static final LogCapture INSTANCE = new LogCapture();
    private static final int MAX = 2000;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private LogRecord last;
    private long seq;

    static void install() {
        Logger.getLogger("").addHandler(INSTANCE);
        Logger.getLogger("com.rapidminer").addHandler(INSTANCE);
    }

    /** Attach to an extra logger (e.g. a process logger that does not propagate). */
    static void attach(Logger l) {
        for (Handler h : l.getHandlers()) if (h == INSTANCE) return;
        l.addHandler(INSTANCE);
    }

    @Override
    public synchronized void publish(LogRecord r) {
        if (r == last) return; // same record seen via two loggers
        last = r;
        String msg = r.getMessage();
        try {
            if (r.getParameters() != null && msg != null) msg = java.text.MessageFormat.format(msg, r.getParameters());
        } catch (Exception ignored) {
        }
        String line = new SimpleDateFormat("HH:mm:ss").format(new Date(r.getMillis())) + " " + r.getLevel() + " "
                + (r.getLoggerName() == null ? "" : r.getLoggerName().replaceFirst("^com\\.rapidminer\\.", "") + ": ") + msg
                + (r.getThrown() != null ? " [" + r.getThrown() + "]" : "");
        lines.addLast((++seq) + "|" + line);
        while (lines.size() > MAX) lines.removeFirst();
    }

    synchronized List<String> tail(int n, String contains) {
        List<String> all = new ArrayList<>(lines);
        List<String> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            String l = all.get(i);
            if (contains == null || l.toLowerCase().contains(contains.toLowerCase())) out.add(0, l);
        }
        return out;
    }

    synchronized void clear() { lines.clear(); }

    synchronized long seq() { return seq; }

    /** Lines logged after sequence number `after`. */
    synchronized List<String> since(long after) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            int bar = l.indexOf('|');
            if (bar > 0 && Long.parseLong(l.substring(0, bar)) > after) out.add(l.substring(bar + 1));
        }
        return out;
    }

    @Override public void flush() {}
    @Override public void close() {}
}
