package aistudiomcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Java agent loaded into Altair AI Studio (RapidMiner) via -javaagent.
 * Exposes a localhost-only, token-protected HTTP/JSON API used by the MCP server.
 */
public class Agent {

    interface Handler { Object handle(JsonObject args) throws Exception; }

    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final Map<String, Handler> ROUTES = new HashMap<>();
    static String token;

    public static void premain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(agentArgs);
    }

    static synchronized void start(String agentArgs) {
        if (token != null) return;
        try {
            int port = 8765;
            if (agentArgs != null && agentArgs.matches("\\d+")) port = Integer.parseInt(agentArgs);
            String envPort = System.getenv("AISTUDIO_MCP_PORT");
            if (envPort != null && envPort.matches("\\d+")) port = Integer.parseInt(envPort);

            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            token = sb.toString();

            LogCapture.install();
            Ui.register(ROUTES);
            Proc.register(ROUTES);
            ROUTES.put("ping", a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("guiReady", Proc.mainFrameOrNull() != null);
                m.put("java", System.getProperty("java.version"));
                return m;
            });

            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/", Agent::serve);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "aistudio-mcp-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            writeConnectionFile(port);
            System.err.println("[aistudio-mcp] agent listening on 127.0.0.1:" + port);
        } catch (Throwable t) {
            System.err.println("[aistudio-mcp] failed to start agent: " + t);
            t.printStackTrace();
        }
    }

    /** Writes ~/.AltairRapidMiner/aistudio-mcp.json (mode 600) so the MCP server can find port + token. */
    static void writeConnectionFile(int port) throws Exception {
        File dir = new File(System.getProperty("user.home"), ".AltairRapidMiner");
        dir.mkdirs();
        File f = new File(dir, "aistudio-mcp.json");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("port", port);
        m.put("token", token);
        m.put("pid", ProcessHandle.current().pid());
        Files.writeString(f.toPath(), GSON.toJson(m));
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
        }
        f.deleteOnExit();
    }

    static void serve(HttpExchange ex) {
        Object result;
        int code = 200;
        try {
            if (!token.equals(ex.getRequestHeaders().getFirst("X-Token"))) {
                code = 401;
                result = Map.of("error", "bad token");
            } else {
                String route = ex.getRequestURI().getPath().replaceFirst("^/", "");
                Handler h = ROUTES.get(route);
                if (h == null) {
                    code = 404;
                    result = Map.of("error", "unknown route " + route, "routes", ROUTES.keySet());
                } else {
                    JsonObject args;
                    try (InputStream in = ex.getRequestBody()) {
                        String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        args = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    }
                    Object r = h.handle(args);
                    result = Map.of("result", r == null ? "ok" : r);
                }
            }
        } catch (Throwable t) {
            code = 500;
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            result = Map.of("error", root.getClass().getSimpleName() + ": " + root.getMessage());
        }
        try {
            byte[] out = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        } catch (Exception ignored) {
        } finally {
            ex.close();
        }
    }
}
