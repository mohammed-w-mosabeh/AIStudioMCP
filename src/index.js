#!/usr/bin/env node
// MCP server for Altair AI Studio (RapidMiner). Talks to the Java agent running inside AI Studio.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CONN_FILE = path.join(os.homedir(), ".AltairRapidMiner", "aistudio-mcp.json");
const LAUNCHER = process.env.AISTUDIO_LAUNCHER || path.join(ROOT, "scripts", "launch-ai-studio.sh");
const SHOT_DIR = process.env.AISTUDIO_SCREENSHOT_DIR || path.join(os.homedir(), ".AltairRapidMiner", "mcp-screenshots");

function conn() {
  try {
    return JSON.parse(fs.readFileSync(CONN_FILE, "utf8"));
  } catch {
    return null;
  }
}

async function call(route, args = {}, timeoutMs = 120000) {
  const c = conn();
  if (!c) throw new Error("AI Studio agent not running. Call rm_launch first (it starts AI Studio with the agent).");
  let res;
  try {
    res = await fetch(`http://127.0.0.1:${c.port}/${route}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Token": c.token },
      body: JSON.stringify(args),
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (e) {
    throw new Error(`Cannot reach the AI Studio agent on port ${c.port} (${e.cause?.code || e.message}). Is AI Studio running? Call rm_launch.`);
  }
  const body = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
  if (body.error) throw new Error(body.error);
  return body.result;
}

const server = new McpServer({ name: "rapidminer-aistudio", version: "0.1.0" });

const asText = (v) => ({ content: [{ type: "text", text: typeof v === "string" ? v : JSON.stringify(v, null, 2) }] });

function tool(name, description, schema, handler) {
  server.registerTool(name, { description, inputSchema: schema }, async (args) => {
    try {
      return await handler(args || {});
    } catch (e) {
      return { isError: true, content: [{ type: "text", text: `${name}: ${e.message}` }] };
    }
  });
}

/** Tool that forwards its arguments straight to an agent route. */
const fwd = (name, route, description, schema = {}, timeoutMs) =>
  tool(name, description, schema, async (a) => asText(await call(route, a, timeoutMs)));

// ---------- shared schemas ----------
const target = {
  id: z.number().int().optional().describe("Component id from rm_ui_tree / rm_ui_find"),
  text: z.string().optional().describe("Match visible text / label (substring, case-insensitive)"),
  name: z.string().optional().describe("Match Swing component name"),
  tooltip: z.string().optional().describe("Match tooltip text"),
  class: z.string().optional().describe("Match Java class name, e.g. JButton, JTree"),
  exact: z.boolean().optional().describe("Exact (not substring) text match"),
  index: z.number().int().optional().describe("Which match to use when several match (0-based)"),
  window: z.number().int().optional().describe("Window id from rm_windows (default: active window)"),
};
const xy = {
  x: z.number().optional().describe("X in window coordinates (pixels of an unscaled rm_screenshot)"),
  y: z.number().optional().describe("Y in window coordinates"),
  scale: z.number().optional().describe("If x/y come from a scaled screenshot, pass that scale"),
};

// ================= lifecycle =================

tool(
  "rm_launch",
  "Start Altair AI Studio (RapidMiner) with the control agent, or confirm it is already running. Waits until the main window is ready.",
  { wait_seconds: z.number().default(240) },
  async ({ wait_seconds }) => {
    const c = conn();
    const pidAlive = (() => {
      try { return c?.pid && process.kill(c.pid, 0); } catch { return false; }
    })();
    if (pidAlive) {
      // Already running (maybe still starting up): never spawn a second instance.
      try {
        const p = await call("ping", {}, 10000);
        if (p.guiReady) return asText({ alreadyRunning: true, ...(await call("rm_status")) });
      } catch {}
    } else {
      if (!fs.existsSync(LAUNCHER)) throw new Error(`launcher not found: ${LAUNCHER}`);
      try { fs.unlinkSync(CONN_FILE); } catch {}
      fs.mkdirSync(path.dirname(CONN_FILE), { recursive: true });
      const log = fs.openSync(path.join(path.dirname(CONN_FILE), "aistudio-mcp-launch.log"), "a");
      spawn(LAUNCHER, [], { detached: true, stdio: ["ignore", log, log], env: process.env }).unref();
    }
    const deadline = Date.now() + wait_seconds * 1000;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 2000));
      try {
        const p = await call("ping", {}, 10000);
        if (p.guiReady) {
          await new Promise((r) => setTimeout(r, 2000));
          return asText({ started: true, ...(await call("rm_status")) });
        }
        // A startup dialog (safe mode, license, EULA...) blocks the main window: report it.
        const wins = await call("ui_windows", {}, 10000);
        const modal = wins.filter((w) => w.modal);
        if (modal.length) {
          return asText({
            started: false,
            blockedByDialog: modal,
            hint: "Inspect with rm_ui_tree / rm_screenshot, answer it with rm_click, then call rm_launch again.",
          });
        }
      } catch {}
    }
    throw new Error("AI Studio did not become ready in time. See ~/.AltairRapidMiner/aistudio-mcp-launch.log");
  }
);

fwd("rm_status", "rm_status", "AI Studio version, current process location, run state, operator/problem counts, perspective.");

// ================= screen control =================

fwd("rm_windows", "ui_windows", "List AI Studio's open windows and dialogs (ids, titles, modal, active).");

fwd(
  "rm_ui_tree",
  "ui_tree",
  "Dump the Swing component tree of a window (or a subtree via id). Each line: [id] Class \"text\" name= tip= @x,y wxh (window coords). Use interactive_only to cut noise.",
  {
    window: target.window,
    id: z.number().int().optional().describe("Start at this component instead of the whole window"),
    interactive_only: z.boolean().default(true),
    max_depth: z.number().int().optional(),
    max_nodes: z.number().int().optional(),
  }
);

fwd("rm_ui_find", "ui_find", "Find components on all windows by text/name/tooltip/class. Returns ids and positions.", {
  text: target.text, name: target.name, tooltip: target.tooltip, class: target.class, exact: target.exact,
  limit: z.number().int().optional(),
});

fwd("rm_ui_get", "ui_get", "Details of one component: full text, and for tables/lists/trees/combos/tabs their rows/items/tabs.", {
  ...target, max_rows: z.number().int().optional(),
});

tool(
  "rm_screenshot",
  "Screenshot an AI Studio window (default: active) or a single component. Rendered from inside the app, so it works on Wayland. Coordinates in the image = window coordinates when scale=1.",
  {
    ...target,
    scale: z.number().default(0.75).describe("Image scale; pass the same scale to rm_click/rm_drag when using x/y"),
    format: z.enum(["png", "jpg"]).default("jpg"),
  },
  async (a) => {
    const r = await call("ui_screenshot", a, 30000);
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    const file = path.join(SHOT_DIR, `shot-${Date.now()}.${r.mime === "image/png" ? "png" : "jpg"}`);
    fs.writeFileSync(file, Buffer.from(r.base64, "base64"));
    return {
      content: [
        { type: "image", data: r.base64, mimeType: r.mime },
        { type: "text", text: `${r.width}x${r.height} at scale ${r.scale} (pass scale=${r.scale} with x/y clicks). Saved: ${file}` },
      ],
    };
  }
);

fwd(
  "rm_click",
  "ui_click",
  "Click a component (id or text/name/tooltip/class query) or a point (x,y). Buttons are pressed via their action; other components get real mouse events. Supports right-click (context menus) and double-click.",
  {
    ...target, ...xy,
    button: z.enum(["left", "right", "middle"]).optional(),
    clicks: z.number().int().optional().describe("2 = double-click"),
    modifiers: z.string().optional().describe("e.g. 'ctrl', 'shift', 'ctrl+shift'"),
    offset_x: z.number().optional().describe("Point inside the component (default: center)"),
    offset_y: z.number().optional(),
    synthetic: z.boolean().optional().describe("Force mouse events even for buttons"),
    wait_ms: z.number().optional(),
  }
);

fwd("rm_type", "ui_type", "Type `value` into a text field (found by target query, or the focused one). clear=true replaces the content; enter=true presses Enter after.", {
  ...target, value: z.string().describe("The text to type"), clear: z.boolean().optional(), enter: z.boolean().optional(),
});

fwd("rm_key", "ui_key", "Press a key or chord on the focused component (or target): e.g. ENTER, ESCAPE, TAB, DELETE, F11, ctrl+S, ctrl+shift+Z.", {
  ...target, keys: z.string(), repeat: z.number().int().optional(), wait_ms: z.number().optional(),
});

fwd(
  "rm_select",
  "ui_select",
  "Select an item in a combo box, list, table, tree or tab pane by text (or item_index). For trees, value can be a path 'Samples > data > Iris'. double_click=true opens/activates it.",
  { ...target, value: z.string().optional(), item_index: z.number().int().optional(), double_click: z.boolean().optional() }
);

fwd("rm_menu", "ui_menu", "Invoke a main-menu item by path, e.g. 'File > Save Process as' or 'View > Restore Default View'. Without path, lists the menus with shortcuts.", {
  path: z.string().optional(), depth: z.number().int().optional(),
});

fwd("rm_scroll", "ui_scroll", "Mouse-wheel scroll over a component or point. amount>0 scrolls down, <0 up.", {
  ...target, ...xy, amount: z.number().int().default(3), modifiers: z.string().optional(),
});

fwd("rm_drag", "ui_drag", "Drag with the mouse between two window points (e.g. move an operator or draw a connection in the process canvas).", {
  window: target.window, from_x: z.number(), from_y: z.number(), to_x: z.number(), to_y: z.number(),
  scale: xy.scale, button: z.enum(["left", "right", "middle"]).optional(), modifiers: z.string().optional(), steps: z.number().int().optional(),
});

fwd("rm_window", "ui_window", "Window actions: front, close (like clicking X; needs an explicit window id), maximize, resize.", {
  window: target.window, confirm_exit: z.boolean().optional().describe("Required to close the main window (exits AI Studio)"), action: z.enum(["front", "close", "maximize", "resize"]), width: z.number().int().optional(), height: z.number().int().optional(),
});

// ================= process =================

fwd("rm_process_structure", "process_structure", "Readable outline of the current process: operators (name [key]), non-default parameters, connections, problems, nested subprocesses.");
fwd("rm_process_xml", "process_xml", "Return the current process as RapidMiner process XML.");
fwd("rm_process_set_xml", "process_set_xml", "Replace the current process with the given process XML (as in a .rmp file). Fastest way to build a whole process.", {
  xml: z.string(), keep_location: z.boolean().optional().describe("Keep the current file location (default true)"),
});
fwd("rm_process_new", "process_new", "Create a new empty process (may prompt to save the current one - check rm_windows afterwards).");
fwd("rm_process_open", "process_open", "Open a .rmp process file from disk.", { path: z.string() });
fwd("rm_process_save", "process_save", "Save the current process (to its location, or to a new .rmp path).", { path: z.string().optional() });
fwd("rm_process_validate", "process_validate", "List all setup problems (errors/warnings) in the current process.");
fwd(
  "rm_process_run",
  "process_run",
  "Run the current process in AI Studio and (by default) wait for it to finish, returning results previews and warnings.",
  { wait: z.boolean().optional(), timeout_seconds: z.number().int().optional(), max_rows: z.number().int().optional() },
  3600_000
);
fwd("rm_process_stop", "process_stop", "Stop the running process.");
fwd("rm_process_results", "process_results", "Results of the last run: each result port's type plus a data preview (rows/columns) or text.", { max_rows: z.number().int().optional() });

// ================= operators =================

fwd("rm_operator_search", "operator_search", "Search the operator catalog (including installed extensions). Returns operator keys to use with rm_operator_add.", {
  query: z.string(), limit: z.number().int().optional(),
});
fwd("rm_operator_info", "operator_info", "Documentation of an operator key: description, all parameters (type, default, range), ports, subprocesses.", { key: z.string() });
fwd(
  "rm_operator_add",
  "operator_add",
  "Add an operator by key (e.g. 'retrieve', 'concurrency:parallel_decision_tree', 'normalize') to the process or into a subprocess of a chain. Optionally name it and set parameters.",
  {
    key: z.string(),
    name: z.string().optional(),
    parent: z.string().optional().describe("Name of the enclosing operator chain (default: main process)"),
    subprocess: z.number().int().optional().describe("Subprocess index in the parent (default 0)"),
    parameters: z.record(z.string(), z.any()).optional(),
    arrange: z.boolean().optional(),
  }
);
fwd("rm_operator_get", "operator_get", "All parameters (value/default/range) and port connections of an operator in the process.", { name: z.string() });
fwd(
  "rm_operator_set_parameters",
  "operator_set_parameters",
  "Set operator parameters, e.g. {\"criterion\":\"gain_ratio\",\"maximal_depth\":\"10\"}. List/enum parameters use RapidMiner's string encoding (see rm_process_xml for examples).",
  { name: z.string(), parameters: z.record(z.string(), z.any()) }
);
fwd("rm_operator_remove", "operator_remove", "Delete an operator from the process.", { name: z.string() });
fwd("rm_operator_rename", "operator_rename", "Rename an operator.", { name: z.string(), new_name: z.string() });
fwd("rm_operator_enable", "operator_enable", "Enable or disable an operator.", { name: z.string(), enabled: z.boolean() });
fwd("rm_operator_select", "operator_select", "Select an operator in the GUI and show it (its parameters appear in the Parameters panel).", { name: z.string() });
fwd(
  "rm_connect",
  "connect",
  "Connect an output port to an input port. Use 'Process:inner' (or 'process') / '<Chain>:inner' for a subprocess's inner ports, e.g. connect 'Decision Tree'.'model' -> 'Process:inner'.'result 1'.",
  {
    from_operator: z.string(), from_port: z.string(), to_operator: z.string(), to_port: z.string(),
    subprocess: z.number().int().optional(), replace: z.boolean().optional(),
  }
);
fwd("rm_disconnect", "disconnect", "Disconnect a port.", {
  operator: z.string(), port: z.string(), direction: z.enum(["output", "input"]).optional(), subprocess: z.number().int().optional(),
});
fwd("rm_arrange", "arrange", "Auto-arrange operators in all subprocesses of the canvas.");

// ================= misc / configuration =================

fwd("rm_log", "log", "Recent AI Studio / process log lines (filter with contains).", { lines: z.number().int().optional(), contains: z.string().optional() });
fwd("rm_settings_get", "settings_get", "Read AI Studio preferences (Settings > Preferences). Give key for one value, or filter to list matching keys.", {
  key: z.string().optional(), filter: z.string().optional(),
});
fwd("rm_settings_set", "settings_set", "Change an AI Studio preference by key and save it.", { key: z.string(), value: z.string(), save: z.boolean().optional() });
fwd(
  "rm_groovy",
  "groovy",
  "Escape hatch: run a Groovy script inside AI Studio with full API access. Bound vars: mainFrame (com.rapidminer.gui.MainFrame), process (com.rapidminer.Process), out (PrintWriter). Returns the script result.",
  { script: z.string(), on_edt: z.boolean().optional().describe("Run on the Swing UI thread (default true)"), timeout_seconds: z.number().int().optional() },
  600_000
);

await server.connect(new StdioServerTransport());
