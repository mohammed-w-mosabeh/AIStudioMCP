package aistudiomcp;

import com.google.gson.JsonObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.*;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;

/** Swing-level screen control: inspect, screenshot, click, type, select, menus. Works on Wayland (no X tools needed). */
final class Ui {
    private Ui() {}

    // ---------- stable component ids ----------
    private static final Map<Component, Integer> IDS = new WeakHashMap<>();
    private static final Map<Integer, WeakReference<Component>> BY_ID = new HashMap<>();
    private static int nextId = 1;

    static synchronized int id(Component c) {
        Integer i = IDS.get(c);
        if (i == null) {
            i = nextId++;
            IDS.put(c, i);
            BY_ID.put(i, new WeakReference<>(c));
        }
        return i;
    }

    static synchronized Component byId(int id) {
        WeakReference<Component> r = BY_ID.get(id);
        Component c = r == null ? null : r.get();
        if (c == null) throw new IllegalArgumentException("No component with id " + id + " (stale? call ui_tree again)");
        return c;
    }

    static void register(Map<String, Agent.Handler> r) {
        r.put("ui_windows", a -> Util.edt(Ui::windows));
        r.put("ui_tree", a -> Util.edt(() -> tree(a), 30000));
        r.put("ui_find", a -> Util.edt(() -> find(a)));
        r.put("ui_get", a -> Util.edt(() -> details(target(a), Util.num(a, "max_rows", 200))));
        r.put("ui_screenshot", Ui::screenshot);
        r.put("ui_click", Ui::click);
        r.put("ui_type", Ui::type);
        r.put("ui_key", Ui::key);
        r.put("ui_select", Ui::select);
        r.put("ui_menu", Ui::menu);
        r.put("ui_scroll", Ui::scroll);
        r.put("ui_drag", Ui::drag);
        r.put("ui_window", Ui::windowAction);
    }

    // ---------- windows ----------
    static List<Window> showingWindows() {
        List<Window> out = new ArrayList<>();
        for (Window w : Window.getWindows()) if (w.isShowing()) out.add(w);
        return out;
    }

    static Window window(JsonObject a) {
        Integer w = Util.numOrNull(a, "window");
        if (w != null) {
            Component c = byId(w);
            if (!(c instanceof Window)) throw new IllegalArgumentException("id " + w + " is not a window");
            return (Window) c;
        }
        // default: active window, else main frame, else first showing window
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isShowing()) return active;
        Object mf = Proc.mainFrameOrNull();
        if (mf instanceof Window && ((Window) mf).isShowing()) return (Window) mf;
        List<Window> ws = showingWindows();
        if (ws.isEmpty()) throw new IllegalStateException("No AI Studio window is showing yet");
        return ws.get(0);
    }

    static Object windows() {
        List<Map<String, Object>> out = new ArrayList<>();
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        for (Window w : showingWindows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("window", id(w));
            m.put("title", w instanceof Frame ? ((Frame) w).getTitle() : w instanceof Dialog ? ((Dialog) w).getTitle() : "");
            m.put("class", w.getClass().getSimpleName());
            m.put("modal", w instanceof Dialog && ((Dialog) w).isModal());
            m.put("active", w == active);
            m.put("size", w.getWidth() + "x" + w.getHeight());
            out.add(m);
        }
        return out;
    }

    // ---------- renderer-based cell text (what the user actually sees) ----------
    static String rendererText(Component rc, Object value) {
        String t = null;
        if (rc instanceof JLabel) t = ((JLabel) rc).getText();
        else if (rc instanceof AbstractButton) t = ((AbstractButton) rc).getText();
        else if (rc instanceof JTextComponent) t = ((JTextComponent) rc).getText();
        else if (rc instanceof Container) {
            StringBuilder sb = new StringBuilder();
            for (Component ch : ((Container) rc).getComponents()) {
                String x = rendererText(ch, null);
                if (x != null && !x.isBlank()) sb.append(sb.length() > 0 ? " " : "").append(x);
            }
            t = sb.toString();
        }
        t = stripHtml(t);
        if (t == null || t.isBlank()) t = value == null ? "" : objText(value);
        return t;
    }

    static String objText(Object v) {
        String s = String.valueOf(v);
        if (s.matches("[\\w.$]+@[0-9a-f]+")) { // default Object.toString: try getName()
            try { return String.valueOf(v.getClass().getMethod("getName").invoke(v)); } catch (Throwable ignored) {}
        }
        return s;
    }

    static String treeText(JTree t, int row) {
        TreePath p = t.getPathForRow(row);
        Object v = p.getLastPathComponent();
        try {
            Component rc = t.getCellRenderer().getTreeCellRendererComponent(t, v, false, t.isExpanded(row), t.getModel().isLeaf(v), row, false);
            return rendererText(rc, v);
        } catch (Throwable e) { return objText(v); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String listText(JList l, int i) {
        Object v = l.getModel().getElementAt(i);
        try {
            Component rc = l.getCellRenderer().getListCellRendererComponent(l, v, i, false, false);
            return rendererText(rc, v);
        } catch (Throwable e) { return objText(v); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String comboText(JComboBox cb, int i) {
        Object v = cb.getItemAt(i);
        try {
            Component rc = cb.getRenderer().getListCellRendererComponent(new JList(), v, i, false, false);
            return rendererText(rc, v);
        } catch (Throwable e) { return objText(v); }
    }

    static String cellText(JTable t, int r, int c) {
        try {
            return rendererText(t.prepareRenderer(t.getCellRenderer(r, c), r, c), t.getValueAt(r, c));
        } catch (Throwable e) { return objText(t.getValueAt(r, c)); }
    }

    // ---------- describing components ----------
    static String stripHtml(String s) {
        if (s == null) return null;
        return s.replaceAll("<[^>]*>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    static String text(Component c) {
        try {
            if (c instanceof JMenu) return ((JMenu) c).getText();
            if (c instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) c;
                String t = b.getText();
                if ((t == null || t.isEmpty()) && b.getAction() != null) {
                    Object n = b.getAction().getValue(Action.NAME);
                    if (n != null) t = n.toString();
                }
                return t;
            }
            if (c instanceof JLabel) return ((JLabel) c).getText();
            if (c instanceof JPasswordField) return "********";
            if (c instanceof JTextComponent) return ((JTextComponent) c).getText();
            if (c instanceof JComboBox) {
                Object s = ((JComboBox<?>) c).getSelectedItem();
                return s == null ? "" : s.toString();
            }
            if (c instanceof JTabbedPane) {
                JTabbedPane t = (JTabbedPane) c;
                List<String> titles = new ArrayList<>();
                for (int i = 0; i < t.getTabCount(); i++)
                    titles.add((i == t.getSelectedIndex() ? "*" : "") + t.getTitleAt(i));
                return "tabs: " + String.join(" | ", titles);
            }
            if (c instanceof JSpinner) return String.valueOf(((JSpinner) c).getValue());
            if (c instanceof JSlider) return String.valueOf(((JSlider) c).getValue());
            if (c instanceof JProgressBar) return ((JProgressBar) c).getString();
            if (c instanceof JList) return ((JList<?>) c).getModel().getSize() + " items";
            if (c instanceof JTable) return ((JTable) c).getRowCount() + "x" + ((JTable) c).getColumnCount() + " table";
            if (c instanceof JTree) return ((JTree) c).getRowCount() + " visible rows";
            if (c instanceof Frame) return ((Frame) c).getTitle();
            if (c instanceof Dialog) return ((Dialog) c).getTitle();
        } catch (Exception ignored) {
        }
        return null;
    }

    static String tooltip(Component c) {
        return c instanceof JComponent ? stripHtml(((JComponent) c).getToolTipText()) : null;
    }

    static String accName(Component c) {
        try {
            return c.getAccessibleContext() == null ? null : c.getAccessibleContext().getAccessibleName();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean interactive(Component c) {
        return c instanceof AbstractButton || c instanceof JTextComponent && ((JTextComponent) c).isEditable()
                || c instanceof JComboBox || c instanceof JList || c instanceof JTable || c instanceof JTree
                || c instanceof JTabbedPane || c instanceof JSlider || c instanceof JSpinner
                || c.getClass().getName().contains("ProcessRenderer");
    }

    static Rectangle boundsIn(Component c, Window w) {
        Point p = SwingUtilities.convertPoint(c.getParent() == null ? c : c.getParent(), c.getLocation(), w);
        if (c == w) p = new Point(0, 0);
        return new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
    }

    static String line(Component c, Window w) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(id(c)).append("] ").append(c.getClass().getSimpleName());
        if (c.getClass().getSimpleName().isEmpty()) sb.append(c.getClass().getName());
        String t = Util.trunc(stripHtml(text(c)), 120);
        if (t != null && !t.isEmpty()) sb.append(" \"").append(t).append('"');
        if (c.getName() != null && !c.getName().isEmpty()) sb.append(" name=").append(c.getName());
        String tip = Util.trunc(tooltip(c), 100);
        if (tip != null && !tip.isEmpty() && !tip.equals(t)) sb.append(" tip=\"").append(tip).append('"');
        if (c instanceof AbstractButton && ((AbstractButton) c).isSelected() && !(c instanceof JMenu)) sb.append(" [selected]");
        if (!c.isEnabled()) sb.append(" [disabled]");
        if (c.isFocusOwner()) sb.append(" [focused]");
        Rectangle r = boundsIn(c, w);
        sb.append(" @").append(r.x).append(',').append(r.y).append(' ').append(r.width).append('x').append(r.height);
        return sb.toString();
    }

    static Object tree(JsonObject a) {
        Integer rootId = Util.numOrNull(a, "id");
        Component root = rootId != null ? byId(rootId) : window(a);
        Window w = root instanceof Window ? (Window) root : SwingUtilities.getWindowAncestor(root);
        boolean interactiveOnly = Util.bool(a, "interactive_only", false);
        int maxDepth = Util.num(a, "max_depth", 60);
        int maxNodes = Util.num(a, "max_nodes", 1500);
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        walk(root, w, 0, maxDepth, interactiveOnly, maxNodes, count, sb);
        if (count[0] >= maxNodes) sb.append("…truncated at ").append(maxNodes).append(" nodes; pass id= to inspect a subtree\n");
        return sb.toString();
    }

    private static void walk(Component c, Window w, int depth, int maxDepth, boolean interactiveOnly, int maxNodes, int[] count, StringBuilder sb) {
        if (!c.isShowing() || count[0] >= maxNodes || depth > maxDepth) return;
        boolean print = !interactiveOnly || interactive(c) || c instanceof Window
                || (text(c) != null && !text(c).isEmpty() && (c instanceof JLabel));
        int childDepth = depth;
        if (print) {
            sb.append("  ".repeat(interactiveOnly ? Math.min(depth, 12) : depth)).append(line(c, w)).append('\n');
            count[0]++;
            childDepth = depth + 1;
        }
        if (c instanceof JMenuBar && interactiveOnly) {
            // menus are listed via ui_menu
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) walk(ch, w, interactiveOnly ? childDepth : depth + 1, maxDepth, interactiveOnly, maxNodes, count, sb);
        }
    }

    static List<Component> allShowing() {
        List<Component> out = new ArrayList<>();
        for (Window w : showingWindows()) collect(w, out);
        return out;
    }

    private static void collect(Component c, List<Component> out) {
        if (!c.isShowing()) return;
        out.add(c);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) collect(ch, out);
    }

    static boolean matches(Component c, JsonObject a) {
        boolean exact = Util.bool(a, "exact", false);
        String q;
        if ((q = Util.str(a, "text", null)) != null && !m(stripHtml(text(c)), q, exact) && !m(accName(c), q, exact)) return false;
        if ((q = Util.str(a, "name", null)) != null && !m(c.getName(), q, exact)) return false;
        if ((q = Util.str(a, "tooltip", null)) != null && !m(tooltip(c), q, exact)) return false;
        if ((q = Util.str(a, "class", null)) != null && !classMatches(c.getClass(), q)) return false;
        return true;
    }

    static boolean classMatches(Class<?> k, String q) {
        for (; k != null; k = k.getSuperclass()) if (k.getName().toLowerCase().contains(q.toLowerCase())) return true;
        return false;
    }

    private static boolean m(String v, String q, boolean exact) {
        if (v == null) return false;
        return exact ? v.trim().equalsIgnoreCase(q.trim()) : v.toLowerCase().contains(q.toLowerCase());
    }

    static boolean hasQuery(JsonObject a) {
        return a.has("text") || a.has("name") || a.has("tooltip") || a.has("class");
    }

    static Object find(JsonObject a) {
        if (!hasQuery(a)) throw new IllegalArgumentException("give text, name, tooltip and/or class");
        int limit = Util.num(a, "limit", 30);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Component c : allShowing()) {
            if (!matches(c, a)) continue;
            Window w = c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
            sb.append(line(c, w)).append("  (window ").append(id(w)).append(")\n");
            if (++n >= limit) break;
        }
        return n == 0 ? "no matches" : sb.toString();
    }

    /** Resolve the target component from id or a query (+index). Prefers interactive matches. Must run on EDT. */
    static Component target(JsonObject a) {
        Integer id = Util.numOrNull(a, "id");
        if (id != null) return byId(id);
        if (!hasQuery(a)) return null;
        int index = Util.num(a, "index", 0);
        List<Component> hits = new ArrayList<>();
        for (Component c : allShowing()) if (matches(c, a)) hits.add(c);
        // Put interactive components first; labels inside buttons would otherwise win.
        hits.sort(Comparator.comparingInt(c -> interactive(c) ? 0 : 1));
        if (hits.size() <= index) throw new IllegalArgumentException("no component matches " + a);
        return hits.get(index);
    }

    static Object details(Component c, int maxRows) {
        if (c == null) throw new IllegalArgumentException("give id or a query");
        Map<String, Object> m = new LinkedHashMap<>();
        Window w = c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
        m.put("summary", line(c, w));
        m.put("class", c.getClass().getName());
        m.put("text", text(c));
        m.put("tooltip", tooltip(c));
        m.put("accessibleName", accName(c));
        m.put("enabled", c.isEnabled());
        if (c instanceof JComboBox) {
            JComboBox<?> cb = (JComboBox<?>) c;
            List<String> items = new ArrayList<>();
            for (int i = 0; i < cb.getItemCount(); i++) items.add(comboText(cb, i));
            m.put("items", items);
            m.put("selectedIndex", cb.getSelectedIndex());
        } else if (c instanceof JList) {
            JList<?> l = (JList<?>) c;
            List<String> items = new ArrayList<>();
            for (int i = 0; i < Math.min(l.getModel().getSize(), maxRows); i++) items.add(listText(l, i));
            m.put("items", items);
            m.put("selectedIndices", l.getSelectedIndices());
        } else if (c instanceof JTable) {
            JTable t = (JTable) c;
            TableModel tm = t.getModel();
            List<String> cols = new ArrayList<>();
            for (int i = 0; i < t.getColumnCount(); i++) cols.add(t.getColumnName(i));
            m.put("columns", cols);
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r < Math.min(t.getRowCount(), maxRows); r++) {
                List<String> row = new ArrayList<>();
                for (int col = 0; col < t.getColumnCount(); col++) row.add(cellText(t, r, col));
                rows.add(row);
            }
            m.put("rows", rows);
            m.put("rowCount", tm.getRowCount());
            m.put("selectedRows", t.getSelectedRows());
        } else if (c instanceof JTree) {
            JTree t = (JTree) c;
            List<String> rows = new ArrayList<>();
            for (int r = 0; r < Math.min(t.getRowCount(), maxRows); r++) {
                TreePath p = t.getPathForRow(r);
                rows.add("  ".repeat(Math.max(0, p.getPathCount() - 1)) + treeText(t, r)
                        + (t.isExpanded(r) ? " [expanded]" : "") + (t.isRowSelected(r) ? " [selected]" : ""));
            }
            m.put("visibleRows", rows);
        } else if (c instanceof JTabbedPane) {
            JTabbedPane t = (JTabbedPane) c;
            List<String> tabs = new ArrayList<>();
            for (int i = 0; i < t.getTabCount(); i++) tabs.add(t.getTitleAt(i));
            m.put("tabs", tabs);
            m.put("selectedIndex", t.getSelectedIndex());
        }
        return m;
    }

    // ---------- screenshot ----------
    static Object screenshot(JsonObject a) throws Exception {
        double scale = a.has("scale") ? a.get("scale").getAsDouble() : 1.0;
        String format = Util.str(a, "format", "png");
        BufferedImage img = Util.edt(() -> {
            Component c = target(a);
            if (c == null) c = window(a);
            int w = Math.max(1, c.getWidth()), h = Math.max(1, c.getHeight());
            int sw = Math.max(1, (int) Math.round(w * scale)), sh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, sw, sh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(scale, scale);
            c.printAll(g);
            // Lightweight popups/tooltips live in the layered pane and are painted by printAll of the window.
            g.dispose();
            return out;
        }, 20000);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String mime;
        if (format.startsWith("jp")) {
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.8f);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                wr.setOutput(ios);
                wr.write(null, new IIOImage(img, null, null), p);
            }
            wr.dispose();
            mime = "image/jpeg";
        } else {
            ImageIO.write(img, "png", bos);
            mime = "image/png";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mime", mime);
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("scale", scale);
        m.put("base64", Base64.getEncoder().encodeToString(bos.toByteArray()));
        return m;
    }

    // ---------- mouse ----------
    /** Resolve where to click: returns {component, pointInComponent}. */
    static Object[] resolvePoint(JsonObject a) {
        Component c = target(a);
        if (c != null) {
            int x = a.has("offset_x") ? a.get("offset_x").getAsInt() : c.getWidth() / 2;
            int y = a.has("offset_y") ? a.get("offset_y").getAsInt() : c.getHeight() / 2;
            return new Object[]{c, new Point(x, y)};
        }
        if (a.has("x") && a.has("y")) {
            Window w = window(a);
            double scale = a.has("scale") ? a.get("scale").getAsDouble() : 1.0;
            int x = (int) Math.round(a.get("x").getAsDouble() / scale), y = (int) Math.round(a.get("y").getAsDouble() / scale);
            Component deep = SwingUtilities.getDeepestComponentAt(w, x, y);
            if (deep == null) deep = w;
            return new Object[]{deep, SwingUtilities.convertPoint(w, x, y, deep)};
        }
        throw new IllegalArgumentException("give id, a query (text/name/tooltip/class), or x+y (window coordinates)");
    }

    static void post(AWTEvent e) {
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(e);
    }

    static int buttonOf(String b) {
        return "right".equals(b) ? MouseEvent.BUTTON3 : "middle".equals(b) ? MouseEvent.BUTTON2 : MouseEvent.BUTTON1;
    }

    static int downMask(int button) {
        return button == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : button == MouseEvent.BUTTON2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
    }

    static int modifiers(JsonObject a) {
        int m = 0;
        String mods = Util.str(a, "modifiers", "");
        for (String s : mods.toLowerCase().split("[+, ]+")) {
            switch (s) {
                case "ctrl": case "control": m |= InputEvent.CTRL_DOWN_MASK; break;
                case "shift": m |= InputEvent.SHIFT_DOWN_MASK; break;
                case "alt": m |= InputEvent.ALT_DOWN_MASK; break;
                case "meta": case "cmd": m |= InputEvent.META_DOWN_MASK; break;
                default:
            }
        }
        return m;
    }

    static Object click(JsonObject a) throws Exception {
        int button = buttonOf(Util.str(a, "button", "left"));
        int clicks = Util.num(a, "clicks", 1);
        int mods = modifiers(a);
        boolean synthetic = Util.bool(a, "synthetic", false);
        Object[] cp = Util.edt(() -> resolvePoint(a));
        Component c = (Component) cp[0];
        Point p = (Point) cp[1];
        String desc = Util.edt(() -> line(c, c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c)));
        boolean useDoClick = !synthetic && c instanceof AbstractButton && !(c instanceof JMenu) && clicks == 1
                && button == MouseEvent.BUTTON1 && mods == 0 && !a.has("offset_x");
        if (useDoClick) {
            Util.later(() -> ((AbstractButton) c).doClick(0));
        } else {
            Util.later(() -> {
                if (c.isFocusable()) c.requestFocusInWindow();
                long t = System.currentTimeMillis();
                post(new MouseEvent(c, MouseEvent.MOUSE_MOVED, t, mods, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
                for (int i = 1; i <= clicks; i++) {
                    post(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, t, mods | downMask(button), p.x, p.y, i, button == MouseEvent.BUTTON3, button));
                    post(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, t, mods, p.x, p.y, i, false, button));
                    post(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, t, mods, p.x, p.y, i, false, button));
                }
            });
        }
        Util.sleep(Util.num(a, "wait_ms", 400));
        return "clicked " + desc + (useDoClick ? " (button action)" : " at " + p.x + "," + p.y);
    }

    static Object drag(JsonObject a) throws Exception {
        // from_x/from_y/to_x/to_y in window coordinates
        Object[] r = Util.edt(() -> {
            Window w = window(a);
            double scale = a.has("scale") ? a.get("scale").getAsDouble() : 1.0;
            Point from = new Point((int) Math.round(a.get("from_x").getAsDouble() / scale), (int) Math.round(a.get("from_y").getAsDouble() / scale));
            Point to = new Point((int) Math.round(a.get("to_x").getAsDouble() / scale), (int) Math.round(a.get("to_y").getAsDouble() / scale));
            Component src = SwingUtilities.getDeepestComponentAt(w, from.x, from.y);
            return new Object[]{w, src, from, to};
        });
        Window w = (Window) r[0];
        Component src = (Component) r[1];
        Point from = (Point) r[2], to = (Point) r[3];
        int button = buttonOf(Util.str(a, "button", "left"));
        int mods = modifiers(a);
        int steps = Util.num(a, "steps", 10);
        Util.later(() -> {
            long t = System.currentTimeMillis();
            Point pf = SwingUtilities.convertPoint(w, from, src);
            post(new MouseEvent(src, MouseEvent.MOUSE_MOVED, t, mods, pf.x, pf.y, 0, false, MouseEvent.NOBUTTON));
            post(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, t, mods | downMask(button), pf.x, pf.y, 1, false, button));
            for (int i = 1; i <= steps; i++) {
                int x = from.x + (to.x - from.x) * i / steps, y = from.y + (to.y - from.y) * i / steps;
                Point pp = SwingUtilities.convertPoint(w, x, y, src);
                post(new MouseEvent(src, MouseEvent.MOUSE_DRAGGED, t + i * 15, mods | downMask(button), pp.x, pp.y, 0, false, button));
            }
            Point pt = SwingUtilities.convertPoint(w, to, src);
            post(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, t + steps * 15 + 10, mods, pt.x, pt.y, 1, false, button));
        });
        Util.sleep(500);
        return "dragged from " + from + " to " + to + " (source " + src.getClass().getSimpleName() + ")";
    }

    static Object scroll(JsonObject a) throws Exception {
        int amount = Util.num(a, "amount", 3);
        Object[] cp = Util.edt(() -> resolvePoint(a));
        Component c = (Component) cp[0];
        Point p = (Point) cp[1];
        Util.later(() -> post(new MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers(a), p.x, p.y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, amount)));
        Util.sleep(250);
        return "scrolled " + amount;
    }

    // ---------- keyboard ----------
    static Component focusOwner() {
        Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f != null) return f;
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (w == null) {
            Object mf = Proc.mainFrameOrNull();
            w = mf instanceof Window ? (Window) mf : null;
        }
        if (w == null) throw new IllegalStateException("No focused component");
        Component mf = w.getMostRecentFocusOwner();
        return mf != null ? mf : w;
    }

    static int keyCode(String name) {
        String n = name.trim().toUpperCase();
        Map<String, String> alias = Map.of("ESC", "ESCAPE", "DEL", "DELETE", "RETURN", "ENTER", "PGUP", "PAGE_UP", "PGDN", "PAGE_DOWN",
                "BACKSPACE", "BACK_SPACE", "UP", "UP", "ARROWUP", "UP", "ARROWDOWN", "DOWN");
        n = alias.getOrDefault(n, n);
        if (n.length() == 1) {
            int kc = KeyEvent.getExtendedKeyCodeForChar(n.charAt(0));
            if (kc != KeyEvent.VK_UNDEFINED) return kc;
        }
        try {
            return KeyEvent.class.getField("VK_" + n).getInt(null);
        } catch (Exception e) {
            throw new IllegalArgumentException("unknown key: " + name);
        }
    }

    static Object key(JsonObject a) throws Exception {
        String keys = Util.req(a, "keys");
        int repeat = Util.num(a, "repeat", 1);
        Component target = Util.edt(() -> {
            Component t = target(a);
            if (t != null) {
                t.requestFocusInWindow();
                return t;
            }
            return focusOwner();
        });
        String[] parts = keys.split("\\+");
        int mods = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase()) {
                case "ctrl": case "control": mods |= InputEvent.CTRL_DOWN_MASK; break;
                case "shift": mods |= InputEvent.SHIFT_DOWN_MASK; break;
                case "alt": mods |= InputEvent.ALT_DOWN_MASK; break;
                case "meta": case "cmd": case "super": mods |= InputEvent.META_DOWN_MASK; break;
                default: throw new IllegalArgumentException("unknown modifier " + parts[i]);
            }
        }
        String last = parts[parts.length - 1];
        int kc = keyCode(last);
        char ch = KeyEvent.CHAR_UNDEFINED;
        if (last.length() == 1) ch = (mods & InputEvent.SHIFT_DOWN_MASK) != 0 ? last.toUpperCase().charAt(0) : last.toLowerCase().charAt(0);
        else if (kc == KeyEvent.VK_ENTER) ch = '\n';
        else if (kc == KeyEvent.VK_TAB) ch = '\t';
        else if (kc == KeyEvent.VK_SPACE) ch = ' ';
        final int fm = mods;
        final char fch = ch;
        Util.sleep(80);
        Util.later(() -> {
            Component t = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (t == null) t = target;
            for (int r = 0; r < repeat; r++) {
                long now = System.currentTimeMillis();
                post(new KeyEvent(t, KeyEvent.KEY_PRESSED, now, fm, kc, fch));
                if (fch != KeyEvent.CHAR_UNDEFINED && (fm & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0)
                    post(new KeyEvent(t, KeyEvent.KEY_TYPED, now, fm, KeyEvent.VK_UNDEFINED, fch));
                post(new KeyEvent(t, KeyEvent.KEY_RELEASED, now, fm, kc, fch));
            }
        });
        Util.sleep(Util.num(a, "wait_ms", 300));
        return "pressed " + keys + (repeat > 1 ? " x" + repeat : "") + " on " + target.getClass().getSimpleName();
    }

    static JTextComponent textChild(Component c) {
        if (c instanceof JTextComponent) return (JTextComponent) c;
        if (c instanceof JComboBox && ((JComboBox<?>) c).isEditable()) {
            Component e = ((JComboBox<?>) c).getEditor().getEditorComponent();
            if (e instanceof JTextComponent) return (JTextComponent) e;
        }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) {
                JTextComponent t = textChild(ch);
                if (t != null && t.isShowing()) return t;
            }
        }
        return null;
    }

    static Object type(JsonObject a) throws Exception {
        String text = Util.req(a, "value");
        boolean clear = Util.bool(a, "clear", false);
        boolean enter = Util.bool(a, "enter", false);
        String how = Util.edt(() -> {
            Component c = target(a);
            if (c == null) c = focusOwner();
            JTextComponent tc = textChild(c);
            if (tc != null) {
                tc.requestFocusInWindow();
                if (clear) tc.setText(text);
                else tc.replaceSelection(text);
                // Some widgets (search/filter fields) react to key events, not document changes.
                if (!text.isEmpty()) {
                    char last = text.charAt(text.length() - 1);
                    int kc = KeyEvent.getExtendedKeyCodeForChar(last);
                    post(new KeyEvent(tc, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, kc == KeyEvent.VK_UNDEFINED ? KeyEvent.VK_A : kc, last));
                }
                return "set text on " + line(tc, SwingUtilities.getWindowAncestor(tc));
            }
            return null;
        });
        if (how == null) {
            // not a text component: send key-typed events to the focus owner
            Util.later(() -> {
                Component f = focusOwner();
                long now = System.currentTimeMillis();
                for (char ch : text.toCharArray()) post(new KeyEvent(f, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch));
            });
            how = "typed " + text.length() + " chars into focused component";
        }
        if (enter) {
            JsonObject k = new JsonObject();
            k.addProperty("keys", "ENTER");
            key(k);
        }
        Util.sleep(150);
        return how;
    }

    // ---------- selection in lists/tables/trees/combos/tabs ----------
    static Object select(JsonObject a) throws Exception {
        String value = Util.str(a, "value", null);
        Integer idx = Util.numOrNull(a, "item_index");
        boolean dbl = Util.bool(a, "double_click", false);
        Object[] res = Util.edt(() -> {
            Component c = target(a);
            if (c == null) throw new IllegalArgumentException("give id or a query for the list/table/tree/combo/tabs");
            if (c instanceof JScrollPane) c = ((JScrollPane) c).getViewport().getView();
            Rectangle cell = null;
            String chosen;
            if (c instanceof JComboBox) {
                JComboBox<?> cb = (JComboBox<?>) c;
                int i = idx != null ? idx : indexOf(cb.getItemCount(), k -> comboText(cb, k), value);
                cb.setSelectedIndex(i);
                chosen = comboText(cb, i);
            } else if (c instanceof JList) {
                JList<?> l = (JList<?>) c;
                int i = idx != null ? idx : indexOf(l.getModel().getSize(), k -> listText(l, k), value);
                l.setSelectedIndex(i);
                l.ensureIndexIsVisible(i);
                cell = l.getCellBounds(i, i);
                chosen = listText(l, i);
            } else if (c instanceof JTable) {
                JTable t = (JTable) c;
                int i = idx != null ? idx : indexOf(t.getRowCount(), r -> {
                    StringBuilder sb = new StringBuilder();
                    for (int col = 0; col < t.getColumnCount(); col++) sb.append(cellText(t, r, col)).append(' ');
                    return sb.toString();
                }, value);
                t.setRowSelectionInterval(i, i);
                t.scrollRectToVisible(t.getCellRect(i, 0, true));
                cell = t.getCellRect(i, 0, true);
                chosen = "row " + i;
            } else if (c instanceof JTree) {
                JTree t = (JTree) c;
                int row;
                if (idx != null) row = idx;
                else {
                    // value may be a path "A > B > C": expand step by step
                    String[] steps = value.split("\\s*>\\s*");
                    row = -1;
                    int start = 0;
                    for (String s : steps) {
                        row = -1;
                        for (int r = start; r < t.getRowCount(); r++) {
                            if (treeText(t, r).toLowerCase().contains(s.toLowerCase())) {
                                row = r;
                                break;
                            }
                        }
                        if (row < 0) throw new IllegalArgumentException("tree node not found: " + s);
                        t.expandRow(row);
                        start = row + 1;
                    }
                }
                t.setSelectionRow(row);
                t.scrollRowToVisible(row);
                cell = t.getRowBounds(row);
                chosen = treeText(t, row);
            } else if (c instanceof JTabbedPane) {
                JTabbedPane t = (JTabbedPane) c;
                int i = idx != null ? idx : indexOf(t.getTabCount(), t::getTitleAt, value);
                t.setSelectedIndex(i);
                chosen = t.getTitleAt(i);
            } else {
                throw new IllegalArgumentException("component is " + c.getClass().getSimpleName() + ", not a list/table/tree/combo/tabs");
            }
            return new Object[]{c, cell, stripHtml(chosen)};
        });
        if (dbl && res[1] != null) {
            Component c = (Component) res[0];
            Rectangle r = (Rectangle) res[1];
            JsonObject k = new JsonObject();
            k.addProperty("id", id(c));
            k.addProperty("offset_x", r.x + Math.min(r.width / 2, 40));
            k.addProperty("offset_y", r.y + r.height / 2);
            k.addProperty("clicks", 2);
            click(k);
        }
        return "selected " + res[2] + (dbl ? " (double-clicked)" : "");
    }

    interface IntFn { String at(int i); }

    static int indexOf(int n, IntFn f, String value) {
        if (value == null) throw new IllegalArgumentException("give value or item_index");
        for (int i = 0; i < n; i++) if (stripHtml(f.at(i)).equalsIgnoreCase(value)) return i;
        for (int i = 0; i < n; i++) if (stripHtml(f.at(i)).toLowerCase().contains(value.toLowerCase())) return i;
        throw new IllegalArgumentException("no item matching \"" + value + "\"");
    }

    // ---------- menus ----------
    static String clean(String s) {
        return s == null ? "" : stripHtml(s).replace("…", "").replaceAll("\\.\\.\\.$", "").trim().toLowerCase();
    }

    static Object menu(JsonObject a) throws Exception {
        String path = Util.str(a, "path", null);
        return Util.edt(() -> {
            JMenuBar bar = null;
            Window w = window(a);
            if (w instanceof JFrame) bar = ((JFrame) w).getJMenuBar();
            if (bar == null && Proc.mainFrameOrNull() instanceof JFrame) bar = ((JFrame) Proc.mainFrameOrNull()).getJMenuBar();
            if (bar == null) throw new IllegalStateException("no menu bar found");
            if (path == null || path.isBlank()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < bar.getMenuCount(); i++) if (bar.getMenu(i) != null) listMenu(bar.getMenu(i), 0, Util.num(a, "depth", 2), sb);
                return sb.toString();
            }
            String[] steps = path.split("\\s*>\\s*");
            MenuElement cur = bar;
            JMenuItem item = null;
            for (String s : steps) {
                item = null;
                MenuElement[] kids = cur instanceof JMenu ? ((JMenu) cur).getPopupMenu().getSubElements() : cur.getSubElements();
                for (MenuElement k : kids) {
                    if (k instanceof JMenuItem && clean(((JMenuItem) k).getText()).equals(clean(s))) { item = (JMenuItem) k; break; }
                }
                if (item == null) for (MenuElement k : kids) {
                    if (k instanceof JMenuItem && clean(((JMenuItem) k).getText()).contains(clean(s))) { item = (JMenuItem) k; break; }
                }
                if (item == null) throw new IllegalArgumentException("menu item not found: " + s);
                cur = item;
            }
            if (item instanceof JMenu) {
                StringBuilder sb = new StringBuilder();
                listMenu((JMenu) item, 0, 2, sb);
                return "that is a submenu:\n" + sb;
            }
            if (!item.isEnabled()) throw new IllegalStateException("menu item is disabled: " + path);
            final JMenuItem fi = item;
            Util.later(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                fi.doClick(0);
            });
            return "invoked menu " + path;
        });
    }

    private static void listMenu(JMenuItem m, int depth, int maxDepth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(m.getText());
        if (!m.isEnabled()) sb.append(" [disabled]");
        if (m.getAccelerator() != null) sb.append("  (").append(m.getAccelerator().toString().replace("pressed ", "")).append(')');
        if (m instanceof JCheckBoxMenuItem || m instanceof JRadioButtonMenuItem) sb.append(m.isSelected() ? " [on]" : " [off]");
        sb.append('\n');
        if (m instanceof JMenu && depth < maxDepth) {
            for (Component c : ((JMenu) m).getMenuComponents()) if (c instanceof JMenuItem) listMenu((JMenuItem) c, depth + 1, maxDepth, sb);
        }
    }

    // ---------- window actions ----------
    static Object windowAction(JsonObject a) throws Exception {
        String action = Util.str(a, "action", "front");
        return Util.edt(() -> {
            Window w = window(a);
            switch (action) {
                case "front": w.toFront(); w.requestFocus(); return "brought to front";
                case "close":
                    if (!a.has("window")) throw new IllegalArgumentException("close needs an explicit window id (see rm_windows)");
                    if (w == Proc.mainFrameOrNull() && !Util.bool(a, "confirm_exit", false))
                        throw new IllegalArgumentException("that is the main window - closing it exits AI Studio; pass confirm_exit=true to do that");
                    Util.later(() -> w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)));
                    return "close requested";
                case "maximize":
                    if (w instanceof Frame) ((Frame) w).setExtendedState(Frame.MAXIMIZED_BOTH);
                    return "maximized";
                case "resize":
                    w.setSize(Util.num(a, "width", w.getWidth()), Util.num(a, "height", w.getHeight()));
                    w.validate();
                    return "resized to " + w.getWidth() + "x" + w.getHeight();
                default: throw new IllegalArgumentException("action must be front|close|maximize|resize");
            }
        });
    }
}
