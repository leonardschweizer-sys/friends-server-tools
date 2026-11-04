package net.friendsserver.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Comments and build marks for a website (sites.NAME.board: true), so its visitors can work together:
 * <ul>
 *   <li>GET  api/state[?since=REV]         everything (comments newest first, marks); "same" if nothing changed</li>
 *   <li>POST api/comments                  {target, label, ign, uid, text}</li>
 *   <li>DELETE api/comments/ID             only by the browser that wrote it (header X-Board-User = its uid)</li>
 *   <li>PUT  api/status/KEY                {state: "construction" | "finished", label, ign, uid}</li>
 *   <li>DELETE api/status/KEY              reset (header X-Board-Name = the in-game name)</li>
 * </ul>
 * Logging in is typing an in-game name, like on the page. The uid is a random id each browser keeps, so only it can
 * delete its own comments; it is never sent to other visitors. Writes are limited per address against spam.
 * Data: plugins/FriendsTools/sites/NAME-board.json.
 */
final class SiteBoard {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern IGN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern UID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9:_,.\\-]{1,64}");
    private static final int MAX_COMMENTS = 1000;
    private static final int MAX_MARKS = 3000;
    private static final int WRITES_PER_MINUTE = 20;

    static final class Comment {
        long id;
        String target;
        String label;
        String ign;
        String uid;
        String text;
        long at;
    }

    static final class Mark {
        String state;
        String label;
        String by;
        String uid;
        long at;
    }

    static final class Data {
        long rev;
        long nextId = 1;
        List<Comment> comments = new ArrayList<>();
        Map<String, Mark> status = new LinkedHashMap<>();
    }

    private final FriendsTools plugin;
    private final Path file;
    private final Map<String, Deque<Long>> writes = new ConcurrentHashMap<>();
    private Data data;

    SiteBoard(FriendsTools plugin, Path file) {
        this.plugin = plugin;
        this.file = file;
        Data loaded = null;
        if (Files.isRegularFile(file)) {
            try {
                loaded = GSON.fromJson(Files.readString(file), Data.class);
            } catch (IOException | JsonParseException e) {
                plugin.getLogger().warning("sites: could not read " + file.getFileName() + " (" + e + "), starting empty.");
            }
        }
        data = loaded == null ? new Data() : loaded;
        if (data.comments == null) {
            data.comments = new ArrayList<>();
        }
        if (data.status == null) {
            data.status = new LinkedHashMap<>();
        }
    }

    void handle(HttpExchange ex, String path) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        String method = ex.getRequestMethod();
        try {
            if (path.equals("state") && method.equals("GET")) {
                state(ex);
            } else if (path.equals("comments") && method.equals("POST")) {
                addComment(ex);
            } else if (path.startsWith("comments/") && method.equals("DELETE")) {
                deleteComment(ex, path.substring("comments/".length()));
            } else if (path.startsWith("status/") && method.equals("PUT")) {
                setMark(ex, path.substring("status/".length()));
            } else if (path.startsWith("status/") && method.equals("DELETE")) {
                resetMark(ex, path.substring("status/".length()));
            } else {
                error(ex, 404, "not_found");
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            error(ex, 400, "invalid_argument");
        }
    }

    // ------------------------------------------------------------------ reading

    private synchronized void state(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        if (query != null && query.startsWith("since=") && query.substring(6).equals(String.valueOf(data.rev))) {
            JsonObject same = new JsonObject();
            same.addProperty("rev", data.rev);
            same.addProperty("same", true);
            json(ex, 200, same);
            return;
        }
        String me = ex.getRequestHeaders().getFirst("X-Board-User");
        List<Map<String, Object>> comments = new ArrayList<>();
        for (int i = data.comments.size() - 1; i >= 0; i--) {
            Comment c = data.comments.get(i);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", String.valueOf(c.id));
            out.put("target", c.target);
            out.put("label", c.label);
            out.put("ign", c.ign);
            out.put("text", c.text);
            out.put("at", c.at);
            out.put("mine", me != null && me.equals(c.uid));
            comments.add(out);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        data.status.forEach((key, m) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", m.state);
            out.put("label", m.label);
            out.put("by", m.by);
            out.put("at", m.at);
            status.put(key, out);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rev", data.rev);
        out.put("comments", comments);
        out.put("status", status);
        send(ex, 200, GSON.toJson(out));
    }

    // ------------------------------------------------------------------ writing

    private void addComment(HttpExchange ex) throws IOException {
        JsonObject in = body(ex);
        String target = str(in, "target");
        String label = clip(str(in, "label"), 80);
        String ign = str(in, "ign");
        String uid = str(in, "uid");
        String text = clip(str(in, "text").strip(), 500);
        if (!KEY.matcher(target).matches() || !IGN.matcher(ign).matches() || !UID.matcher(uid).matches()
                || text.isEmpty()) {
            error(ex, 400, "invalid_argument");
            return;
        }
        if (limited(ex)) {
            return;
        }
        long id;
        synchronized (this) {
            Comment c = new Comment();
            c.id = id = data.nextId++;
            c.target = target;
            c.label = label;
            c.ign = ign;
            c.uid = uid;
            c.text = text;
            c.at = System.currentTimeMillis();
            data.comments.add(c);
            while (data.comments.size() > MAX_COMMENTS) {
                data.comments.remove(0);
            }
            changed();
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("id", String.valueOf(id));
        json(ex, 200, out);
    }

    private void deleteComment(HttpExchange ex, String idText) throws IOException {
        String uid = ex.getRequestHeaders().getFirst("X-Board-User");
        long id;
        try {
            id = Long.parseLong(idText);
        } catch (NumberFormatException e) {
            error(ex, 404, "not_found");
            return;
        }
        synchronized (this) {
            Comment found = data.comments.stream().filter(c -> c.id == id).findFirst().orElse(null);
            if (found == null) {
                error(ex, 404, "not_found");
                return;
            }
            if (uid == null || !uid.equals(found.uid)) {
                error(ex, 403, "not_granted");
                return;
            }
            data.comments.remove(found);
            changed();
        }
        ok(ex);
    }

    private void setMark(HttpExchange ex, String key) throws IOException {
        JsonObject in = body(ex);
        String state = str(in, "state");
        String ign = str(in, "ign");
        String uid = str(in, "uid");
        if (!KEY.matcher(key).matches() || !IGN.matcher(ign).matches() || !UID.matcher(uid).matches()
                || !state.equals("construction") && !state.equals("finished")) {
            error(ex, 400, "invalid_argument");
            return;
        }
        if (limited(ex)) {
            return;
        }
        synchronized (this) {
            if (!data.status.containsKey(key) && data.status.size() >= MAX_MARKS) {
                error(ex, 429, "quota_exceeded");
                return;
            }
            Mark m = new Mark();
            m.state = state;
            m.label = clip(str(in, "label"), 80);
            m.by = ign;
            m.uid = uid;
            m.at = System.currentTimeMillis();
            data.status.put(key, m);
            changed();
        }
        ok(ex);
    }

    private void resetMark(HttpExchange ex, String key) throws IOException {
        String ign = ex.getRequestHeaders().getFirst("X-Board-Name");
        if (ign == null || !IGN.matcher(ign).matches()) {
            error(ex, 400, "invalid_argument");
            return;
        }
        if (limited(ex)) {
            return;
        }
        synchronized (this) {
            if (data.status.remove(key) != null) {
                changed();
            }
        }
        ok(ex);
    }

    /** Called with the lock held: new revision, saved right away (small file). */
    private void changed() {
        data.rev++;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("sites: could not save " + file.getFileName() + ": " + e);
        }
    }

    /** Spam guard: at most WRITES_PER_MINUTE writes per address; answers 429 when over it. */
    private boolean limited(HttpExchange ex) throws IOException {
        String who = ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        Deque<Long> times = writes.computeIfAbsent(who, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= WRITES_PER_MINUTE) {
                error(ex, 429, "slow_down");
                return true;
            }
            times.addLast(now);
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(4097);
        if (raw.length > 4096) {
            throw new JsonParseException("too big");
        }
        JsonObject obj = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
        if (obj == null) {
            throw new JsonParseException("empty");
        }
        return obj;
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void ok(HttpExchange ex) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        json(ex, 200, out);
    }

    private static void error(HttpExchange ex, int status, String code) throws IOException {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("code", code);
        json(ex, status, out);
    }

    private static void json(HttpExchange ex, int status, JsonObject obj) throws IOException {
        send(ex, status, GSON.toJson(obj));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
