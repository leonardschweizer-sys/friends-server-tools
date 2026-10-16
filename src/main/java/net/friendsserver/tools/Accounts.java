package net.friendsserver.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login accounts, saved in plugins/FriendsTools/accounts.json, one per player name (case-insensitive).
 *
 * <ul>
 *   <li>{@code uuid}: the ID this name always plays with. For everyone who played before the switch to offline
 *       mode this is their Mojang UUID, so inventories, advancements, teams and ranks stay where they are.</li>
 *   <li>{@code premium}: the name is protected. Joining with it requires passing Mojang's login check against
 *       {@code mojangUuid}. A cracked client can't do that.</li>
 *   <li>{@code password}: PBKDF2 hash, used by cracked accounts (and by protected ones in password fallback).</li>
 * </ul>
 */
final class Accounts {

    static final class Account {
        String name;
        String uuid;
        boolean premium;
        String mojangUuid;
        String password;
        String lastIp;
        String sessionIp;
        long sessionUntil;

        UUID uuid() {
            return UUID.fromString(uuid);
        }

        UUID mojangUuid() {
            return mojangUuid == null ? null : UUID.fromString(mojangUuid);
        }
    }

    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FriendsTools plugin;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Account> byName = new ConcurrentHashMap<>();
    private volatile String latestJson = "{}";

    Accounts(FriendsTools plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("accounts.json");
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    Account get(String name) {
        return byName.get(key(name));
    }

    Map<String, Account> all() {
        return byName;
    }

    void put(Account account) {
        byName.put(key(account.name), account);
        save();
    }

    void remove(String name) {
        byName.remove(key(name));
        save();
    }

    long premiumCount() {
        return byName.values().stream().filter(a -> a.premium).count();
    }

    // ------------------------------------------------------------------ passwords

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2_sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    static boolean matches(String password, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    // ------------------------------------------------------------------ storage

    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Type type = new TypeToken<Map<String, Account>>() { }.getType();
            Map<String, Account> data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (data != null) {
                data.values().forEach(a -> byName.put(key(a.name), a));
            }
        } catch (IOException | RuntimeException e) {
            // Never start with an empty account list by accident: that would let known names in with new IDs.
            throw new IllegalStateException("Could not read accounts.json: " + e, e);
        }
    }

    void save() {
        latestJson = gson.toJson(byName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::write);
    }

    void saveNow() {
        latestJson = gson.toJson(byName);
        write();
    }

    private synchronized void write() {
        String json = latestJson;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("accounts.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save accounts.json: " + e);
        }
    }
}
