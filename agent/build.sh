#!/bin/bash
# Compiles the Java agent against the installed AI Studio jars.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
RM_HOME="${RAPIDMINER_HOME:-$HOME/Downloads/Altair/RapidMiner/AI Studio 2026.1.1}"
JAVAC="${JAVAC:-javac}"
rm -rf "$HERE/build" && mkdir -p "$HERE/build/classes"
"$JAVAC" --release 17 -nowarn -encoding UTF-8 -cp "$RM_HOME/lib/*" -d "$HERE/build/classes" "$HERE"/src/aistudiomcp/*.java
cat > "$HERE/build/MANIFEST.MF" <<MF
Premain-Class: aistudiomcp.Agent
Agent-Class: aistudiomcp.Agent
MF
jar cfm "$HERE/aistudio-mcp-agent.jar" "$HERE/build/MANIFEST.MF" -C "$HERE/build/classes" .
echo "built $HERE/aistudio-mcp-agent.jar"
