# RapidMiner / Altair AI Studio MCP

An MCP server that lets Claude open, see, and control **Altair AI Studio** (the current name for RapidMiner Studio).

```
Claude ──MCP/stdio──▶ src/index.js (Node) ──HTTP 127.0.0.1:8765 + token──▶ Java agent inside AI Studio's JVM
                                                                           ├─ Swing UI control (screenshots, clicks, typing, menus, trees…)
                                                                           └─ RapidMiner API (process, operators, parameters, run, results, settings)
```

The agent runs **inside** AI Studio (loaded with `-javaagent`). That way:
- screenshots and clicks work on **GNOME Wayland**, with no xdotool, ydotool or screen-capture permissions;
- Claude reads the real component tree (buttons, trees, tables, dialogs) instead of guessing pixels;
- processes can be built and configured exactly through RapidMiner's own API, with Groovy as an escape hatch.

## Setup

```bash
npm install
./agent/build.sh                 # compiles agent/aistudio-mcp-agent.jar against your AI Studio jars
claude mcp add rapidminer -s user -- node "$PWD/src/index.js"
```

AI Studio is expected at `~/Downloads/Altair/RapidMiner/AI Studio 2026.1.1`. If yours is elsewhere, set `RAPIDMINER_HOME`
(for both `agent/build.sh` and the MCP server's environment).

Start AI Studio **through the launcher** (`npm run launch`, or let Claude call `rm_launch`). The launcher matches the official
`AI-Studio.sh` and only adds the agent. Your installation is not modified. If you start AI Studio from its normal shortcut, the
agent is not loaded and the tools can't connect.

## Tools

| Group | Tools |
|---|---|
| Lifecycle | `rm_launch`, `rm_status` |
| Screen control | `rm_windows`, `rm_screenshot`, `rm_ui_tree`, `rm_ui_find`, `rm_ui_get`, `rm_click`, `rm_type`, `rm_key`, `rm_select`, `rm_menu`, `rm_scroll`, `rm_drag`, `rm_window` |
| Process | `rm_process_structure`, `rm_process_xml`, `rm_process_set_xml`, `rm_process_new`, `rm_process_open`, `rm_process_save`, `rm_process_validate`, `rm_process_run`, `rm_process_stop`, `rm_process_results` |
| Operators | `rm_operator_search`, `rm_operator_info`, `rm_operator_add`, `rm_operator_get`, `rm_operator_set_parameters`, `rm_operator_remove`, `rm_operator_rename`, `rm_operator_enable`, `rm_operator_select`, `rm_connect`, `rm_disconnect`, `rm_arrange` |
| Configuration | `rm_settings_get`, `rm_settings_set`, `rm_log`, `rm_groovy` |

Tips
- Find components by `id` (from `rm_ui_tree`/`rm_ui_find`) or by `text` / `name` / `tooltip` / `class` (class matches superclasses too, e.g. `JTree`).
- `rm_screenshot` returns window coordinates scaled by `scale`. Pass the same `scale` to `rm_click`/`rm_drag` when clicking by `x`,`y`.
- Some screens (e.g. the Welcome dialog) are custom-painted: use a screenshot plus an `x`,`y` click.
- Port notation for `rm_connect`: inner ports of a subprocess are `<Chain>:inner` (the main process is `Process:inner` or `process`).
- `rm_window close` needs an explicit window id. Closing the main window also needs `confirm_exit: true`.

## Files
- `src/index.js`: MCP server (Node, stdio)
- `agent/src/aistudiomcp/*.java`: the Java agent (`Agent` HTTP server, `Ui` Swing control, `Proc` RapidMiner API, `LogCapture`)
- `scripts/launch-ai-studio.sh`: starts AI Studio with the agent (it also fixes missing `DISPLAY`/`XAUTHORITY` and the UTF-8 locale when started from MCP)
- `examples/iris_decision_tree.rmp`: a sample process built entirely through the MCP

## Security
The agent listens on `127.0.0.1` only. Every request needs a random token that is regenerated each launch and written to
`~/.AltairRapidMiner/aistudio-mcp.json` (mode 600). `rm_groovy` can run arbitrary code inside AI Studio, so treat this MCP
like shell access.
