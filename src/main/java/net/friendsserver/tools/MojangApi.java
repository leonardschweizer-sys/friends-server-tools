package net.friendsserver.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The two Mojang web calls the login system needs. */
final class MojangApi {

    record Prop(String name, String value, String signature) {
    }

    record Profile(UUID id, String name, List<Prop> properties) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MojangApi() {
    }

    /**
     * Mojang's check that the player who sent us the encryption response really owns {@code username}:
     * the client told Mojang "I'm joining server {@code serverId}" with its login token.
     */
    static CompletableFuture<Optional<Profile>> hasJoined(String username, String serverId) {
        String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        return get(url);
    }

    /** Looks up the Mojang account that currently owns a name. */
    static CompletableFuture<Optional<Profile>> lookupName(String name) {
        String url = "https://api.mojang.com/users/profiles/minecraft/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8);
        return get(url);
    }

    private static CompletableFuture<Optional<Profile>> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                        return Optional.<Profile>empty();
                    }
                    return parse(response.body());
                });
    }

    private static Optional<Profile> parse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("id") || !json.has("name")) {
                return Optional.empty();
            }
            UUID id = parseId(json.get("id").getAsString());
            List<Prop> props = new ArrayList<>();
            if (json.has("properties") && json.get("properties").isJsonArray()) {
                JsonArray array = json.getAsJsonArray("properties");
                for (JsonElement element : array) {
                    JsonObject p = element.getAsJsonObject();
                    props.add(new Prop(p.get("name").getAsString(), p.get("value").getAsString(),
                            p.has("signature") ? p.get("signature").getAsString() : null));
                }
            }
            return Optional.of(new Profile(id, json.get("name").getAsString(), List.copyOf(props)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** "069a79f444e94726a5befca90e38aaf5" or the dashed form -> UUID. */
    static UUID parseId(String id) {
        String hex = id.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("Bad UUID: " + id);
        }
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }
}
