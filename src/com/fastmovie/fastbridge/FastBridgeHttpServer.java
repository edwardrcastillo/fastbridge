package com.fastmovie.fastbridge;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FastBridgeHttpServer {
    private static final String TAG = "FastBridgeHTTP";
    public static final int DEFAULT_PORT = 8888;

    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private volatile ExFatVolume volume;
    private volatile String statusMessage = "Initializing USB Bridge...";

    public FastBridgeHttpServer(int port) {
        this.port = port;
    }

    public void setVolume(ExFatVolume volume, String message) {
        this.volume = volume;
        this.statusMessage = message;
    }

    public ExFatVolume getVolume() {
        return volume;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        running = true;

        executor.submit(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "FastBridge HTTP Server listening on 0.0.0.0:" + port);
                while (running) {
                    try {
                        final Socket client = serverSocket.accept();
                        client.setSoTimeout(30000);
                        client.setTcpNoDelay(true);
                        executor.submit(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        });
                    } catch (IOException e) {
                        if (running) {
                            Log.e(TAG, "Accept error: " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 65536);

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String fullUri = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String k = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    String v = line.substring(colon + 1).trim();
                    headers.put(k, v);
                }
            }

            String rawPath = fullUri;
            String queryString = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx >= 0) {
                rawPath = fullUri.substring(0, qIdx);
                queryString = fullUri.substring(qIdx + 1);
            }

            Map<String, String> query = parseQuery(queryString);

            if ("/api/status".equals(rawPath)) {
                serveApiStatus(out);
            } else if ("/api/list".equals(rawPath)) {
                String dirPath = query.containsKey("path") ? query.get("path") : "/";
                serveApiList(out, dirPath);
            } else if (rawPath.startsWith("/kodi")) {
                String subPath = rawPath.substring("/kodi".length());
                String decoded = decodeUrlPath(subPath);
                serveKodiPath(method, decoded, headers, out);
            } else if (rawPath.startsWith("/stream/")) {
                String subPath = rawPath.substring("/stream".length());
                String decoded = decodeUrlPath(subPath);
                serveFileStream(method, decoded, headers, out);
            } else {
                String dirPath = query.containsKey("path") ? query.get("path") : "/";
                serveWebUi(out, dirPath);
            }

            out.flush();
        } catch (Exception ignored) {
            // Ignore BrokenPipe / ConnectionReset on video seek
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } else if (!pair.isEmpty()) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (UnsupportedEncodingException ignored) {
            }
        }
        return map;
    }

    private String decodeUrlPath(String p) {
        if (p == null || p.isEmpty()) return "/";
        try {
            // Preserve '+' if present in filenames before URLDecoder turns '+' into space
            String safe = p.replace("+", "%2B");
            return URLDecoder.decode(safe, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return p;
        }
    }

    private static String encodeUrlSegment(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return segment;
        }
    }

    private static String encodeUrlPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) {
                sb.append("/").append(encodeUrlSegment(seg));
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void serveApiStatus(OutputStream out) throws IOException {
        ExFatVolume vol = this.volume;
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"ready\": ").append(vol != null).append(",\n");
        json.append("  \"status\": \"").append(escapeJson(statusMessage)).append("\"");
        if (vol != null) {
            json.append(",\n  \"vendorProduct\": \"").append(escapeJson(vol.getDevice().getVendorProduct())).append("\",\n");
            json.append("  \"volumeLabel\": \"").append(escapeJson(vol.getVolumeLabel())).append("\",\n");
            json.append("  \"volumeUuid\": \"").append(escapeJson(vol.getVolumeUuidHex())).append("\",\n");
            json.append("  \"partitionStartLba\": ").append(vol.getPartitionStartLba()).append(",\n");
            json.append("  \"clusterSizeBytes\": ").append(vol.getBytesPerCluster()).append(",\n");
            json.append("  \"totalDiskBytes\": ").append(vol.getDevice().getTotalBytes()).append(",\n");
            json.append("  \"totalVolumeBytes\": ").append(vol.getTotalVolumeBytes()).append("\n");
        } else {
            json.append("\n");
        }
        json.append("}\n");
        sendSimpleResponse(out, "200 OK", "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void serveApiList(OutputStream out, String dirPath) throws IOException {
        ExFatVolume vol = this.volume;
        if (vol == null) {
            String err = "{\"error\": \"" + escapeJson(statusMessage) + "\"}\n";
            sendSimpleResponse(out, "503 Service Unavailable", "application/json; charset=utf-8", err.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            String norm = ExFatVolume.normalizePath(dirPath);
            List<ExFatVolume.ExFatEntry> list = vol.listDirectory(norm);
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"path\": \"").append(escapeJson(norm)).append("\",\n");
            sb.append("  \"volumeLabel\": \"").append(escapeJson(vol.getVolumeLabel())).append("\",\n");
            sb.append("  \"count\": ").append(list.size()).append(",\n");
            sb.append("  \"entries\": [\n");
            for (int i = 0; i < list.size(); i++) {
                ExFatVolume.ExFatEntry e = list.get(i);
                sb.append("    {");
                sb.append("\"name\": \"").append(escapeJson(e.name)).append("\", ");
                sb.append("\"path\": \"").append(escapeJson(e.path)).append("\", ");
                sb.append("\"isDir\": ").append(e.isDirectory).append(", ");
                sb.append("\"size\": ").append(e.size).append(", ");
                sb.append("\"firstCluster\": ").append(e.firstCluster).append(", ");
                sb.append("\"contiguous\": ").append(e.noFatChain);
                if (!e.isDirectory) {
                    sb.append(", \"streamUrl\": \"/stream").append(escapeJson(encodeUrlPath(e.path))).append("\"");
                }
                sb.append("}").append(i + 1 < list.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n}\n");
            sendSimpleResponse(out, "200 OK", "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String err = "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}\n";
            sendSimpleResponse(out, "404 Not Found", "application/json; charset=utf-8", err.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void serveKodiPath(String method, String path, Map<String, String> headers, OutputStream out) throws IOException {
        ExFatVolume vol = this.volume;
        if (vol == null) {
            sendSimpleResponse(out, "503 Service Unavailable", "text/plain; charset=utf-8", statusMessage.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String norm = ExFatVolume.normalizePath(path);
        ExFatVolume.ExFatEntry entry = vol.resolveEntry(norm);
        if (entry == null) {
            sendSimpleResponse(out, "404 Not Found", "text/plain; charset=utf-8", ("Not found: " + norm).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (entry.isDirectory) {
            List<ExFatVolume.ExFatEntry> items = vol.listDirectory(norm);
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Index of ")
                .append(escapeHtml(norm)).append("</title></head><body><h1>Index of ")
                .append(escapeHtml(norm)).append("</h1><hr><pre>\n");
            if (!"/".equals(norm)) {
                html.append("<a href=\"../\">../</a>\n");
            }
            for (ExFatVolume.ExFatEntry item : items) {
                String href = encodeUrlSegment(item.name) + (item.isDirectory ? "/" : "");
                String label = escapeHtml(item.name) + (item.isDirectory ? "/" : "");
                html.append("<a href=\"").append(href).append("\">").append(label).append("</a>\n");
            }
            html.append("</pre><hr></body></html>");
            sendSimpleResponse(out, "200 OK", "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            serveEntryBytes(method, entry, headers, out);
        }
    }

    private void serveFileStream(String method, String path, Map<String, String> headers, OutputStream out) throws IOException {
        ExFatVolume vol = this.volume;
        if (vol == null) {
            sendSimpleResponse(out, "503 Service Unavailable", "text/plain; charset=utf-8", statusMessage.getBytes(StandardCharsets.UTF_8));
            return;
        }
        ExFatVolume.ExFatEntry entry = vol.resolveEntry(path);
        if (entry == null || entry.isDirectory) {
            sendSimpleResponse(out, "404 Not Found", "text/plain; charset=utf-8", ("File not found: " + path).getBytes(StandardCharsets.UTF_8));
            return;
        }
        serveEntryBytes(method, entry, headers, out);
    }

    private void serveEntryBytes(String method, ExFatVolume.ExFatEntry file, Map<String, String> headers, OutputStream out) throws IOException {
        ExFatVolume vol = this.volume;
        long fileSize = file.size;
        String mime = guessMimeType(file.name);

        long start = 0;
        long end = fileSize - 1;
        boolean isPartial = false;

        String rangeHeader = headers.get("range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true;
            String spec = rangeHeader.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0L, fileSize - suffix);
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash + 1 < spec.length()) {
                        end = Long.parseLong(spec.substring(dash + 1));
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (start < 0 || start >= fileSize) {
            String hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */" + fileSize + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(hdr.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (end >= fileSize) {
            end = fileSize - 1;
        }
        long contentLength = (end - start) + 1;

        StringBuilder resp = new StringBuilder();
        resp.append(isPartial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        resp.append("Content-Type: ").append(mime).append("\r\n");
        resp.append("Accept-Ranges: bytes\r\n");
        resp.append("Content-Length: ").append(contentLength).append("\r\n");
        if (isPartial) {
            resp.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileSize).append("\r\n");
        }
        resp.append("Access-Control-Allow-Origin: *\r\n");
        resp.append("Connection: close\r\n\r\n");
        out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        if ("HEAD".equals(method)) {
            return;
        }

        byte[] buffer = new byte[128 * 1024]; // 128 KB streaming buffer
        long curOffset = start;
        long remaining = contentLength;
        while (remaining > 0) {
            int chunk = (int) Math.min((long) buffer.length, remaining);
            int read = vol.readFileBytes(file, curOffset, buffer, 0, chunk);
            if (read <= 0) {
                break;
            }
            out.write(buffer, 0, read);
            curOffset += read;
            remaining -= read;
        }
    }

    private void serveWebUi(OutputStream out, String dirPath) throws IOException {
        ExFatVolume vol = this.volume;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>FastBridge exFAT Server</title>");
        html.append("<style>");
        html.append("body{background:#070B14;color:#f8fafc;font-family:system-ui,-apple-system,sans-serif;margin:0;padding:28px;}");
        html.append(".header{display:flex;align-items:center;justify-content:space-between;background:#111827;padding:20px 26px;border-radius:14px;border:1px solid #1f2937;margin-bottom:20px;}");
        html.append(".badge{background:#f59e0b;color:#090d16;padding:6px 14px;border-radius:8px;font-weight:800;font-size:14px;}");
        html.append(".list{display:flex;flex-direction:column;gap:10px;}");
        html.append(".item{display:flex;align-items:center;justify-content:space-between;background:#131c2e;padding:16px 22px;border-radius:10px;border:2px solid #23324e;text-decoration:none;color:#f8fafc;font-weight:600;font-size:17px;transition:0.12s;}");
        html.append(".item:hover,.item:focus{border:3px solid #fef08a;background:#f59e0b;color:#090d16;transform:scale(1.01);outline:none;}");
        html.append(".item:hover .meta,.item:focus .meta{color:#090d16;font-weight:700;}");
        html.append(".meta{color:#94a3b8;font-size:14px;font-family:monospace;}");
        html.append("</style></head><body>");

        if (vol == null) {
            html.append("<div class=\"header\"><div><h2>FastBridge USB exFAT Server</h2><p class=\"meta\">")
                .append(escapeHtml(statusMessage)).append("</p></div></div>");
        } else {
            String norm = ExFatVolume.normalizePath(dirPath);
            html.append("<div class=\"header\"><div><h2 style=\"margin:0 0 6px 0;\">💾 Disco USB: ")
                .append(escapeHtml(vol.getVolumeLabel())).append(" (").append(escapeHtml(vol.getDevice().getVendorProduct()))
                .append(")</h2><div class=\"meta\">UUID: ").append(escapeHtml(vol.getVolumeUuidHex()))
                .append(" • Ruta: ").append(escapeHtml(norm))
                .append(" • Fuente para Kodi: <code>http://127.0.0.1:8888/kodi/</code></div></div>")
                .append("<span class=\"badge\">exFAT Activo</span></div>");

            html.append("<div class=\"list\">");
            if (!"/".equals(norm)) {
                int lastSlash = norm.lastIndexOf('/');
                String parent = (lastSlash <= 0) ? "/" : norm.substring(0, lastSlash);
                html.append("<a class=\"item\" href=\"/?path=").append(encodeUrlSegment(parent)).append("\">")
                    .append("<span>⬅️ Subir a carpeta anterior (").append(escapeHtml(parent)).append(")</span><span class=\"meta\">Carpeta</span></a>");
            }

            try {
                List<ExFatVolume.ExFatEntry> entries = vol.listDirectory(norm);
                for (ExFatVolume.ExFatEntry e : entries) {
                    if (e.isDirectory) {
                        html.append("<a class=\"item\" href=\"/?path=").append(encodeUrlSegment(e.path)).append("\">")
                            .append("<span>📁 ").append(escapeHtml(e.name)).append("</span><span class=\"meta\">Carpeta</span></a>");
                    } else {
                        String streamHref = "/stream" + encodeUrlPath(e.path);
                        html.append("<a class=\"item\" href=\"").append(streamHref).append("\" target=\"_blank\">")
                            .append("<span>🎬 ").append(escapeHtml(e.name)).append("</span><span class=\"meta\">")
                            .append(formatSize(e.size)).append("</span></a>");
                    }
                }
            } catch (Exception e) {
                html.append("<div class=\"item\">Error leyendo directorio: ").append(escapeHtml(e.getMessage())).append("</div>");
            }
            html.append("</div>");
        }

        html.append("</body></html>");
        sendSimpleResponse(out, "200 OK", "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String guessMimeType(String name) {
        String low = name.toLowerCase(Locale.US);
        if (low.endsWith(".mp4") || low.endsWith(".m4v")) return "video/mp4";
        if (low.endsWith(".mkv")) return "video/x-matroska";
        if (low.endsWith(".avi")) return "video/x-msvideo";
        if (low.endsWith(".webm")) return "video/webm";
        if (low.endsWith(".mov")) return "video/quicktime";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".flac")) return "audio/flac";
        if (low.endsWith(".srt")) return "application/x-subrip; charset=utf-8";
        if (low.endsWith(".vtt")) return "text/vtt; charset=utf-8";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private void sendSimpleResponse(OutputStream out, String status, String contentType, byte[] body) throws IOException {
        String hdr = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }
}
