package aistudiomcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rapidminer.FileProcessLocation;
import com.rapidminer.Process;
import com.rapidminer.ProcessListener;
import com.rapidminer.RapidMiner;
import com.rapidminer.example.Attribute;
import com.rapidminer.example.Example;
import com.rapidminer.example.ExampleSet;
import com.rapidminer.gui.MainFrame;
import com.rapidminer.gui.RapidMinerGUI;
import com.rapidminer.operator.ExecutionUnit;
import com.rapidminer.operator.IOContainer;
import com.rapidminer.operator.IOObject;
import com.rapidminer.operator.Operator;
import com.rapidminer.operator.OperatorChain;
import com.rapidminer.operator.OperatorDescription;
import com.rapidminer.operator.ProcessSetupError;
import com.rapidminer.operator.ports.InputPort;
import com.rapidminer.operator.ports.OutputPort;
import com.rapidminer.operator.ports.Port;
import com.rapidminer.operator.ports.Ports;
import com.rapidminer.parameter.ParameterType;
import com.rapidminer.tools.OperatorService;
import com.rapidminer.tools.ParameterService;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/** RapidMiner process / operator / settings API. */
final class Proc {
    private Proc() {}

    static volatile IOContainer lastResults;
    static volatile String lastRunError;
    static volatile long lastRunEnded;

    static Object mainFrameOrNull() {
        try {
            return RapidMinerGUI.getMainFrame();
        } catch (Throwable t) {
            return null;
        }
    }

    static MainFrame mf() {
        MainFrame m = RapidMinerGUI.getMainFrame();
        if (m == null) throw new IllegalStateException("AI Studio is still starting (main window not ready)");
        return m;
    }

    static Process process() {
        Process p = mf().getProcess();
        if (p == null) throw new IllegalStateException("no process open");
        return p;
    }

    static void register(Map<String, Agent.Handler> r) {
        r.put("rm_status", a -> Util.edt(Proc::status));
        r.put("process_structure", a -> Util.edt(Proc::structure));
        r.put("process_xml", a -> Util.edt(() -> process().getRootOperator().getXML(false)));
        r.put("process_set_xml", Proc::setXml);
        r.put("process_new", a -> { Util.later(() -> mf().newProcess()); Util.sleep(800); return Util.edt(Proc::status); });
        r.put("process_open", Proc::open);
        r.put("process_save", Proc::save);
        r.put("process_validate", a -> Util.edt(Proc::validate));
        r.put("process_run", Proc::run);
        r.put("process_stop", a -> { Util.later(() -> mf().stopProcess()); Util.sleep(500); return Util.edt(Proc::status); });
        r.put("process_results", a -> results(Util.num(a, "max_rows", 20)));
        r.put("operator_search", Proc::search);
        r.put("operator_info", Proc::info);
        r.put("operator_add", Proc::add);
        r.put("operator_remove", a -> Util.edt(() -> { Operator o = op(Util.req(a, "name")); o.remove(); return "removed " + o.getName(); }));
        r.put("operator_rename", a -> Util.edt(() -> "renamed to " + op(Util.req(a, "name")).rename(Util.req(a, "new_name"))));
        r.put("operator_enable", a -> Util.edt(() -> { Operator o = op(Util.req(a, "name")); o.setEnabled(Util.bool(a, "enabled", true)); return o.getName() + " enabled=" + o.isEnabled(); }));
        r.put("operator_get", a -> Util.edt(() -> opDetails(op(Util.req(a, "name")))));
        r.put("operator_set_parameters", Proc::setParams);
        r.put("operator_select", a -> Util.edt(() -> { Operator o = op(Util.req(a, "name")); mf().selectAndShowOperator(o, true); return "selected " + o.getName(); }));
        r.put("connect", Proc::connect);
        r.put("disconnect", Proc::disconnect);
        r.put("arrange", a -> Util.edt(() -> { arrangeAll(); return "arranged"; }));
        r.put("log", a -> String.join("\n", LogCapture.INSTANCE.tail(Util.num(a, "lines", 100), Util.str(a, "contains", null))));
        r.put("settings_get", Proc::settingsGet);
        r.put("settings_set", Proc::settingsSet);
        r.put("groovy", Proc::groovy);
    }

    // ---------- status / structure ----------
    static Object status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", RapidMiner.getLongVersion());
        MainFrame f = RapidMinerGUI.getMainFrame();
        m.put("guiReady", f != null);
        if (f == null) return m;
        Process p = f.getProcess();
        m.put("processLocation", p.getProcessLocation() == null ? null : p.getProcessLocation().toString());
        int st = p.getProcessState();
        m.put("state", st == Process.PROCESS_STATE_RUNNING ? "running" : st == Process.PROCESS_STATE_PAUSED ? "paused" : "stopped");
        m.put("operators", p.getAllOperators().size());
        int errs = 0;
        for (Operator o : p.getAllOperators()) errs += o.getErrorList().size();
        m.put("problems", errs);
        m.put("perspective", f.getPerspectiveController().getModel().getSelectedPerspective() == null ? null
                : f.getPerspectiveController().getModel().getSelectedPerspective().getName());
        m.put("lastRunError", lastRunError);
        return m;
    }

    static String portRef(Port p) {
        Operator o = p.getPorts().getOwner().getOperator();
        boolean inner = p.getPorts().getOwner().getConnectionContext() != o.getExecutionUnit() && o instanceof OperatorChain
                && isInnerPort(p, (OperatorChain) o);
        return (inner ? o.getName() + ":inner" : o.getName()) + "." + p.getName();
    }

    static boolean isInnerPort(Port p, OperatorChain c) {
        for (ExecutionUnit u : c.getSubprocesses())
            if (u.getInnerSources().getAllPorts().contains(p) || u.getInnerSinks().getAllPorts().contains(p)) return true;
        return false;
    }

    static Object structure() {
        StringBuilder sb = new StringBuilder();
        Process p = process();
        sb.append("Process: ").append(p.getProcessLocation() == null ? "(unsaved)" : p.getProcessLocation()).append('\n');
        describeChain(p.getRootOperator(), 0, sb);
        sb.append("\nPort notation: <operator>.<port>; inner ports of a subprocess use <operator>:inner.<port> (root = Process:inner).\n");
        return sb.toString();
    }

    static void describeChain(OperatorChain chain, int depth, StringBuilder sb) {
        String ind = "  ".repeat(depth);
        List<ExecutionUnit> units = chain.getSubprocesses();
        for (int i = 0; i < units.size(); i++) {
            ExecutionUnit u = units.get(i);
            sb.append(ind).append("subprocess #").append(i).append(" \"").append(u.getName()).append("\" of ").append(chain.getName()).append('\n');
            for (OutputPort src : u.getInnerSources().getAllPorts())
                if (src.isConnected()) sb.append(ind).append("  ").append(chain.getName()).append(":inner.").append(src.getName()).append(" -> ").append(portRef(src.getOpposite())).append('\n');
            for (Operator o : u.getOperators()) {
                sb.append(ind).append("  - ").append(o.getName()).append(" [").append(o.getOperatorDescription().getKey()).append("]");
                if (!o.isEnabled()) sb.append(" (disabled)");
                sb.append('\n');
                String params = nonDefaultParams(o);
                if (!params.isEmpty()) sb.append(ind).append("      params: ").append(params).append('\n');
                for (OutputPort out : o.getOutputPorts().getAllPorts())
                    if (out.isConnected()) sb.append(ind).append("      ").append(out.getName()).append(" -> ").append(portRef(out.getOpposite())).append('\n');
                for (ProcessSetupError e : o.getErrorList()) sb.append(ind).append("      ! ").append(Util.trunc(e.getMessage(), 200)).append('\n');
                if (o instanceof OperatorChain) describeChain((OperatorChain) o, depth + 3, sb);
            }
        }
    }

    static String nonDefaultParams(Operator o) {
        List<String> out = new ArrayList<>();
        for (ParameterType t : o.getParameterTypes()) {
            String v = o.getParameters().getParameterOrNull(t.getKey());
            if (v == null) continue;
            String def = t.getDefaultValueAsString();
            if (Objects.equals(v, def)) continue;
            out.add(t.getKey() + "=" + Util.trunc(v, 120));
        }
        return String.join(", ", out);
    }

    static Object validate() {
        List<String> out = new ArrayList<>();
        for (Operator o : process().getAllOperators())
            for (ProcessSetupError e : o.getErrorList()) out.add(o.getName() + ": [" + e.getSeverity() + "] " + e.getMessage());
        return out.isEmpty() ? "no problems" : out;
    }

    // ---------- open / save / xml ----------
    static Object setXml(JsonObject a) throws Exception {
        String xml = Util.req(a, "xml");
        Process np = new Process(xml);
        Util.edt(() -> {
            Process old = mf().getProcess();
            if (old != null && old.getProcessLocation() != null && Util.bool(a, "keep_location", true)) np.setProcessLocation(old.getProcessLocation());
            mf().setProcess(np, true);
            return null;
        });
        Util.sleep(300);
        return Util.edt(Proc::structure);
    }

    static Object open(JsonObject a) throws Exception {
        File f = new File(Util.req(a, "path")).getAbsoluteFile();
        if (!f.isFile()) throw new IllegalArgumentException("file not found: " + f);
        FileProcessLocation loc = new FileProcessLocation(f);
        Process p = loc.load(null);
        p.setProcessLocation(loc);
        Util.edt(() -> { mf().setOpenedProcess(p); return null; });
        Util.sleep(500);
        return Util.edt(Proc::structure);
    }

    static Object save(JsonObject a) throws Exception {
        String path = Util.str(a, "path", null);
        return Util.edt(() -> {
            Process p = process();
            if (path != null) {
                File f = new File(path).getAbsoluteFile();
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                p.save(f);
                p.setProcessLocation(new FileProcessLocation(f));
                mf().setOpenedProcess(p);
                return "saved to " + f;
            }
            if (!p.hasSaveDestination()) throw new IllegalStateException("process has no location yet; pass path (e.g. /home/me/proc.rmp)");
            p.save();
            return "saved to " + p.getProcessLocation();
        });
    }

    // ---------- run ----------
    static Object run(JsonObject a) throws Exception {
        boolean wait = Util.bool(a, "wait", true);
        int timeout = Util.num(a, "timeout_seconds", 600);
        int maxRows = Util.num(a, "max_rows", 20);
        long started = System.currentTimeMillis();
        long logStart = LogCapture.INSTANCE.seq();
        Util.edt(() -> {
            Process p = process();
            if (p.getProcessState() == Process.PROCESS_STATE_RUNNING) throw new IllegalStateException("process already running");
            lastRunError = null;
            LogCapture.attach(p.getLogger());
            p.getRootOperator().addProcessListener(new ProcessListener() {
                @Override public void processStarts(Process process) {}
                @Override public void processStartedOperator(Process process, Operator op) {}
                @Override public void processFinishedOperator(Process process, Operator op) {}
                @Override public void processEnded(Process process) {
                    try { lastResults = process.getRootOperator().getResults(false); } catch (Throwable t) { lastRunError = String.valueOf(t); }
                    lastRunEnded = System.currentTimeMillis();
                    process.getRootOperator().removeProcessListener(this);
                }
            });
            return null;
        });
        Util.later(() -> mf().runProcess());
        if (!wait) return "started";
        // wait to start
        long deadline = started + timeout * 1000L;
        boolean sawRunning = false;
        while (System.currentTimeMillis() < deadline) {
            int st = Util.edt(() -> process().getProcessState());
            if (st == Process.PROCESS_STATE_RUNNING) sawRunning = true;
            if ((sawRunning || System.currentTimeMillis() - started > 5000) && st == Process.PROCESS_STATE_STOPPED) break;
            if (lastRunEnded >= started) break;
            Util.sleep(300);
        }
        Util.sleep(300);
        Map<String, Object> m = new LinkedHashMap<>();
        boolean finished = lastRunEnded >= started;
        m.put("finished", finished);
        m.put("seconds", (System.currentTimeMillis() - started) / 1000.0);
        if (!sawRunning && !finished) {
            m.put("note", "process did not start - a dialog may be asking something (check ui_windows / screenshot), or there are setup errors");
            m.put("problems", Util.edt(Proc::validate));
        }
        List<String> errs = LogCapture.INSTANCE.since(logStart);
        errs.removeIf(l -> !(l.contains("SEVERE") || l.contains("WARNING")) || l.contains("usage statistics"));
        if (errs.size() > 20) errs = errs.subList(errs.size() - 20, errs.size());
        if (!errs.isEmpty()) m.put("recentWarnings", errs);
        if (finished) m.put("results", results(maxRows));
        return m;
    }

    static Object results(int maxRows) {
        IOContainer c = lastResults;
        if (c == null) return "no results captured yet (run the process with process_run)";
        List<Object> out = new ArrayList<>();
        int i = 0;
        for (IOObject o : c.asList()) {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", "result " + i);
            if (o == null) continue;
            m.put("type", o.getClass().getSimpleName());
            m.put("source", o.getSource());
            if (o instanceof ExampleSet) {
                ExampleSet es = (ExampleSet) o;
                m.put("rows", es.size());
                List<String> cols = new ArrayList<>();
                List<Attribute> attrs = new ArrayList<>();
                Iterator<Attribute> it = es.getAttributes().allAttributes();
                while (it.hasNext()) {
                    Attribute at = it.next();
                    attrs.add(at);
                    String special = null;
                    try { special = es.getAttributes().getRole(at).getSpecialName(); } catch (Throwable ignored) {}
                    cols.add(at.getName() + (special != null ? " <" + special + ">" : ""));
                }
                m.put("columns", cols);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < Math.min(es.size(), maxRows); r++) {
                    Example ex = es.getExample(r);
                    List<String> row = new ArrayList<>();
                    for (Attribute at : attrs) row.add(ex.getValueAsString(at));
                    rows.add(row);
                }
                m.put("preview", rows);
            } else {
                Object view = o;
                try { // belt IOTable -> Table has a readable toString
                    view = o.getClass().getMethod("getTable").invoke(o);
                } catch (Throwable ignored) {}
                m.put("text", Util.trunc(String.valueOf(view), 6000));
            }
            out.add(m);
        }
        return out;
    }

    // ---------- operators ----------
    static Operator op(String name) {
        Operator o = process().getOperator(name);
        if (o == null) throw new IllegalArgumentException("no operator named \"" + name + "\". Existing: " + process().getAllOperatorNames());
        return o;
    }

    static Object search(JsonObject a) {
        String q = Util.req(a, "query").toLowerCase();
        int limit = Util.num(a, "limit", 40);
        List<String[]> hits = new ArrayList<>();
        for (String key : OperatorService.getOperatorKeys()) {
            OperatorDescription d = OperatorService.getOperatorDescription(key);
            if (d == null || !d.isEnabled()) continue;
            String name = displayName(d);
            String group = d.getGroup() == null ? "" : d.getGroup();
            String tags = "";
            try { tags = String.join(" ", d.getTags()).toLowerCase(); } catch (Throwable ignored) {}
            int score = name.equalsIgnoreCase(q) || key.equalsIgnoreCase(q) ? 0 : name.toLowerCase().startsWith(q) ? 1
                    : name.toLowerCase().contains(q) || key.toLowerCase().contains(q) ? 2 : group.toLowerCase().contains(q) || tags.contains(q) ? 3 : -1;
            if (score >= 0) hits.add(new String[]{String.valueOf(score), key, name, group});
        }
        hits.sort(Comparator.comparing((String[] h) -> h[0]).thenComparing(h -> h[2]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) sb.append(hits.get(i)[1]).append("  —  ").append(hits.get(i)[2]).append("  (").append(hits.get(i)[3]).append(")\n");
        return hits.isEmpty() ? "no operators match" : sb.toString() + (hits.size() > limit ? "… " + (hits.size() - limit) + " more" : "");
    }

    static String displayName(OperatorDescription d) {
        try {
            String n = com.rapidminer.tools.documentation.OperatorI18N.getOperatorName(d);
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return d.getName() == null ? d.getKey() : d.getName();
    }

    static Object info(JsonObject a) throws Exception {
        String key = Util.req(a, "key");
        OperatorDescription d = OperatorService.getOperatorDescription(key);
        if (d == null) throw new IllegalArgumentException("unknown operator key " + key + " (use operator_search)");
        Operator tmp = OperatorService.createOperator(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.getKey());
        m.put("name", displayName(d));
        m.put("group", d.getGroup());
        try { m.put("tags", d.getTags()); } catch (Throwable ignored) {}
        m.put("description", Util.trunc(Ui.stripHtml(d.getLongDescriptionHTML()), 2500));
        m.put("parameters", paramTypes(tmp, null));
        m.put("inputPorts", Arrays.asList(tmp.getInputPorts().getPortNames()));
        m.put("outputPorts", Arrays.asList(tmp.getOutputPorts().getPortNames()));
        if (tmp instanceof OperatorChain) {
            List<String> subs = new ArrayList<>();
            for (ExecutionUnit u : ((OperatorChain) tmp).getSubprocesses())
                subs.add(u.getName() + " (inner sources " + Arrays.asList(u.getInnerSources().getPortNames()) + ", inner sinks " + Arrays.asList(u.getInnerSinks().getPortNames()) + ")");
            m.put("subprocesses", subs);
        }
        return m;
    }

    static List<Map<String, Object>> paramTypes(Operator o, Operator current) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParameterType t : o.getParameterTypes()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("key", t.getKey());
            p.put("type", t.getClass().getSimpleName().replace("ParameterType", ""));
            if (current != null) p.put("value", current.getParameters().getParameterOrNull(t.getKey()));
            p.put("default", t.getDefaultValueAsString());
            String range = null;
            try { range = t.getRange(); } catch (Throwable ignored) {}
            if (range != null && !range.isEmpty()) p.put("range", Util.trunc(range, 300));
            if (t.isExpert()) p.put("expert", true);
            if (!t.isOptional()) p.put("required", true);
            p.put("description", Util.trunc(Ui.stripHtml(t.getDescription()), 200));
            out.add(p);
        }
        return out;
    }

    static Object opDetails(Operator o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", o.getName());
        m.put("key", o.getOperatorDescription().getKey());
        m.put("enabled", o.isEnabled());
        m.put("parameters", paramTypes(o, o));
        List<String> in = new ArrayList<>(), out = new ArrayList<>();
        for (InputPort p : o.getInputPorts().getAllPorts()) in.add(p.getName() + (p.isConnected() ? " <- " + portRef(p.getOpposite()) : ""));
        for (OutputPort p : o.getOutputPorts().getAllPorts()) out.add(p.getName() + (p.isConnected() ? " -> " + portRef(p.getOpposite()) : ""));
        m.put("inputPorts", in);
        m.put("outputPorts", out);
        List<String> errs = new ArrayList<>();
        for (ProcessSetupError e : o.getErrorList()) errs.add(e.getMessage());
        m.put("problems", errs);
        return m;
    }

    static Object add(JsonObject a) throws Exception {
        String key = Util.req(a, "key");
        String name = Util.str(a, "name", null);
        String parent = Util.str(a, "parent", null);
        int sub = Util.num(a, "subprocess", 0);
        Operator created = OperatorService.createOperator(key);
        return Util.edt(() -> {
            OperatorChain chain = parent == null ? process().getRootOperator() : (OperatorChain) op(parent);
            ExecutionUnit u = chain.getSubprocess(sub);
            u.addOperator(created);
            if (name != null) created.rename(name);
            JsonElement params = a.get("parameters");
            if (params != null && params.isJsonObject())
                for (Map.Entry<String, JsonElement> e : params.getAsJsonObject().entrySet()) created.setParameter(e.getKey(), jsonStr(e.getValue()));
            if (Util.bool(a, "arrange", true)) arrange(u);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", created.getName());
            m.put("inputPorts", Arrays.asList(created.getInputPorts().getPortNames()));
            m.put("outputPorts", Arrays.asList(created.getOutputPorts().getPortNames()));
            return m;
        });
    }

    static String jsonStr(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }

    static Object setParams(JsonObject a) throws Exception {
        String name = Util.req(a, "name");
        JsonObject params = a.getAsJsonObject("parameters");
        if (params == null) throw new IllegalArgumentException("missing parameters object");
        return Util.edt(() -> {
            Operator o = op(name);
            Set<String> known = new HashSet<>();
            for (ParameterType t : o.getParameterTypes()) known.add(t.getKey());
            List<String> done = new ArrayList<>(), unknown = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : params.entrySet()) {
                if (!known.contains(e.getKey())) { unknown.add(e.getKey()); continue; }
                o.setParameter(e.getKey(), jsonStr(e.getValue()));
                done.add(e.getKey() + "=" + o.getParameters().getParameterOrNull(e.getKey()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("set", done);
            if (!unknown.isEmpty()) { m.put("unknownKeys", unknown); m.put("validKeys", known); }
            return m;
        });
    }

    static OutputPort outPort(String opRef, String port, int sub) {
        Operator o;
        boolean inner = false;
        if (opRef.equalsIgnoreCase("process") || opRef.endsWith(":inner")) {
            inner = true;
            o = opRef.equalsIgnoreCase("process") ? process().getRootOperator() : op(opRef.substring(0, opRef.length() - 6));
        } else o = op(opRef);
        Ports<OutputPort> ports = inner ? ((OperatorChain) o).getSubprocess(sub).getInnerSources() : o.getOutputPorts();
        OutputPort p = ports.getPortByName(port);
        if (p == null) throw new IllegalArgumentException("no output port \"" + port + "\" on " + opRef + "; available: " + Arrays.asList(ports.getPortNames()));
        return p;
    }

    static InputPort inPort(String opRef, String port, int sub) {
        Operator o;
        boolean inner = false;
        if (opRef.equalsIgnoreCase("process") || opRef.endsWith(":inner")) {
            inner = true;
            o = opRef.equalsIgnoreCase("process") ? process().getRootOperator() : op(opRef.substring(0, opRef.length() - 6));
        } else o = op(opRef);
        Ports<InputPort> ports = inner ? ((OperatorChain) o).getSubprocess(sub).getInnerSinks() : o.getInputPorts();
        InputPort p = ports.getPortByName(port);
        if (p == null) throw new IllegalArgumentException("no input port \"" + port + "\" on " + opRef + "; available: " + Arrays.asList(ports.getPortNames()));
        return p;
    }

    static Object connect(JsonObject a) throws Exception {
        return Util.edt(() -> {
            int sub = Util.num(a, "subprocess", 0);
            OutputPort from = outPort(Util.req(a, "from_operator"), Util.req(a, "from_port"), sub);
            InputPort to = inPort(Util.req(a, "to_operator"), Util.req(a, "to_port"), sub);
            if (from.isConnected() && Util.bool(a, "replace", true)) from.disconnect();
            if (to.isConnected() && Util.bool(a, "replace", true)) to.getOpposite().disconnect();
            from.connectTo(to);
            return "connected " + portRef(from) + " -> " + portRef(to);
        });
    }

    static Object disconnect(JsonObject a) throws Exception {
        return Util.edt(() -> {
            int sub = Util.num(a, "subprocess", 0);
            String opRef = Util.req(a, "operator"), port = Util.req(a, "port");
            String dir = Util.str(a, "direction", "output");
            Port p = "input".equals(dir) ? inPort(opRef, port, sub) : outPort(opRef, port, sub);
            if (!p.isConnected()) return "port was not connected";
            if (p instanceof InputPort) ((InputPort) p).getOpposite().disconnect();
            else p.disconnect();
            return "disconnected " + opRef + "." + port;
        });
    }

    static com.rapidminer.gui.flow.processrendering.view.ProcessRendererController controller() throws Exception {
        com.rapidminer.gui.flow.processrendering.view.ProcessRendererView v = mf().getProcessPanel().getProcessRenderer();
        for (java.lang.reflect.Field f : v.getClass().getDeclaredFields()) {
            if (com.rapidminer.gui.flow.processrendering.view.ProcessRendererController.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object c = f.get(v);
                if (c != null) return (com.rapidminer.gui.flow.processrendering.view.ProcessRendererController) c;
            }
        }
        return new com.rapidminer.gui.flow.processrendering.view.ProcessRendererController(v, v.getModel());
    }

    static void arrange(ExecutionUnit u) {
        try {
            controller().autoArrange(u);
        } catch (Throwable ignored) {
        }
    }

    static void arrangeAll() throws Exception {
        List<ExecutionUnit> units = new ArrayList<>();
        for (Operator o : process().getAllOperators())
            if (o instanceof OperatorChain) units.addAll(((OperatorChain) o).getSubprocesses());
        controller().autoArrange(units);
    }

    // ---------- preferences ----------
    static Object settingsGet(JsonObject a) {
        String key = Util.str(a, "key", null);
        String filter = Util.str(a, "filter", null);
        if (key != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("value", ParameterService.getParameterValue(key));
            ParameterType t = ParameterService.getParameterType(key);
            if (t != null) {
                m.put("description", Ui.stripHtml(t.getDescription()));
                try { m.put("range", t.getRange()); } catch (Throwable ignored) {}
            }
            return m;
        }
        StringBuilder sb = new StringBuilder();
        List<String> keys = new ArrayList<>(ParameterService.getParameterKeys());
        Collections.sort(keys);
        for (String k : keys) {
            if (filter != null && !k.toLowerCase().contains(filter.toLowerCase())) continue;
            if (k.toLowerCase().contains("password") || k.toLowerCase().contains("secret") || k.toLowerCase().contains("token")) {
                sb.append(k).append(" = (hidden)\n");
                continue;
            }
            sb.append(k).append(" = ").append(Util.trunc(ParameterService.getParameterValue(k), 120)).append('\n');
        }
        return sb.toString();
    }

    static Object settingsSet(JsonObject a) {
        String key = Util.req(a, "key");
        String value = Util.req(a, "value");
        ParameterService.setParameterValue(key, value);
        if (Util.bool(a, "save", true)) ParameterService.saveParameters();
        return key + " = " + ParameterService.getParameterValue(key);
    }

    // ---------- groovy escape hatch ----------
    static Object groovy(JsonObject a) throws Exception {
        String script = Util.req(a, "script");
        boolean onEdt = Util.bool(a, "on_edt", true);
        StringWriter outBuf = new StringWriter();
        java.util.concurrent.Callable<Object> c = () -> {
            Binding b = new Binding();
            b.setVariable("mainFrame", RapidMinerGUI.getMainFrame());
            b.setVariable("process", RapidMinerGUI.getMainFrame() == null ? null : RapidMinerGUI.getMainFrame().getProcess());
            b.setVariable("out", new PrintWriter(outBuf, true));
            GroovyShell sh = new GroovyShell(Proc.class.getClassLoader(), b);
            return sh.evaluate(script);
        };
        Object res = onEdt ? Util.edt(c, Util.num(a, "timeout_seconds", 60) * 1000L) : c.call();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", res == null ? null : Util.trunc(String.valueOf(res), 20000));
        if (outBuf.getBuffer().length() > 0) m.put("output", Util.trunc(outBuf.toString(), 20000));
        return m;
    }
}
