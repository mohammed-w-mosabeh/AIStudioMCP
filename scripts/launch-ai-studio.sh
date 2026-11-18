#!/bin/bash
# Starts Altair AI Studio (RapidMiner) with the aistudio-mcp Java agent attached.
# Mirrors the official AI-Studio.sh, only adding -javaagent. The installation is not modified.
unset _JAVA_OPTIONS
HERE="$(cd "$(dirname "$0")/.." && pwd)"
AGENT="${AISTUDIO_MCP_AGENT:-$HERE/agent/aistudio-mcp-agent.jar}"
RAPIDMINER_HOME="${RAPIDMINER_HOME:-$HOME/Downloads/Altair/RapidMiner/AI Studio 2026.1.1}"
export RAPIDMINER_HOME
PORT="${AISTUDIO_MCP_PORT:-8765}"

# When started from an MCP client the desktop environment variables may be missing.
export DISPLAY="${DISPLAY:-:0}"
if [ -z "${XAUTHORITY}" ]; then
  XA="$(ls /run/user/$(id -u)/.mutter-Xwaylandauth.* 2>/dev/null | head -1)"
  [ -n "$XA" ] && export XAUTHORITY="$XA" || { [ -f "$HOME/.Xauthority" ] && export XAUTHORITY="$HOME/.Xauthority"; }
fi
# Java needs a UTF-8 locale to handle non-ASCII paths (e.g. Arabic folder names in $HOME).
case "${LC_ALL:-${LANG:-}}" in *[Uu][Tt][Ff]*8*) ;; *) export LANG=C.UTF-8; export LC_ALL=C.UTF-8 ;; esac
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

if [ -n "${JAVA_HOME}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then JAVA="${JAVA_HOME}/bin/java"; else JAVA="$(which java)"; fi
[ -x "$JAVA" ] || { echo "java not found"; exit 1; }
[ -f "$AGENT" ] || { echo "agent jar missing: $AGENT (run agent/build.sh)"; exit 1; }

LAUNCH=1
while [ ${LAUNCH} -eq 1 ]; do
  rmClasspath="${RAPIDMINER_HOME}"/lib/*
  if ! ("$JAVA" -cp "${rmClasspath}" com.rapidminer.launcher.PreLaunchMigrationManager --ui); then
    echo 'Directory migration failed. Make sure no other AI Studio instance is running.'; exit 1
  fi
  JVM_OPTIONS=$("$JAVA" "-Djava.awt.headless=true" -cp "${rmClasspath}" com.rapidminer.launcher.JVMOptionBuilder "$@")
  LAUNCH=0
  eval \"$JAVA\" \"-javaagent:${AGENT}=${PORT}\" $JVM_OPTIONS -cp \"${rmClasspath}\" com.rapidminer.launcher.GUILauncher \"\$@\"
  [ $? -eq 2 ] && { echo "AI Studio will now relaunch"; LAUNCH=1; }
done
