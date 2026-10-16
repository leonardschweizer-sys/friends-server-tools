package net.friendsserver.tools;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import org.bukkit.Bukkit;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mojang login check for protected names while the server runs in offline mode (needs PacketEvents).
 *
 * <p>Offline mode skips Mojang's check for everyone. For names that are protected, this does the same check vanilla
 * does in online mode, at the protocol level: when such a name connects, its LOGIN_START is held back, the client gets
 * an encryption request, and its answer is checked with Mojang's session server ("hasJoined"). Only a client logged in
 * to that Mojang account can pass. Then the LOGIN_START is handed back to the server and the login continues; the
 * result waits for {@link LoginManager} in AsyncPlayerPreLoginEvent, which rejects the join if there is none.</p>
 *
 * <p>Fails closed: anything that goes wrong just means no verified result, so the protected name is refused.</p>
 */
final class PremiumVerifier extends PacketListenerAbstract implements LoginManager.PremiumCheck {

    private record Pending(String name, byte[] verifyToken, UUID clientUuid, ClientVersion version, String ip,
                           long time) {
    }

    private static final long MAX_AGE_MILLIS = 60_000;

    private final FriendsTools plugin;
    private final LoginManager login;
    private final KeyPair keyPair;
    private final SecureRandom random = new SecureRandom();
    private final Map<User, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, LoginManager.VerifiedLogin> verified = new ConcurrentHashMap<>();

    /** Factory with the interface as return type, so LoginManager loads without PacketEvents installed. */
    static LoginManager.PremiumCheck create(FriendsTools plugin, LoginManager login) throws GeneralSecurityException {
        return new PremiumVerifier(plugin, login);
    }

    private PremiumVerifier(FriendsTools plugin, LoginManager login) throws GeneralSecurityException {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.login = login;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        this.keyPair = generator.generateKeyPair();
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void close() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        pending.clear();
        verified.clear();
    }

    @Override
    public LoginManager.VerifiedLogin consume(String name, String ip) {
        LoginManager.VerifiedLogin result = verified.remove(Accounts.key(name));
        if (result == null || System.currentTimeMillis() - result.time() > MAX_AGE_MILLIS
                || !result.ip().equals(ip)) {
            return null;
        }
        return result;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            onLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            onEncryptionResponse(event);
        }
    }

    private void onLoginStart(PacketReceiveEvent event) {
        if (Bukkit.getOnlineMode() || !login.needsMojangCheck()) {
            return; // online mode: vanilla does the Mojang check for everyone
        }
        WrapperLoginClientLoginStart start = new WrapperLoginClientLoginStart(event);
        String name = start.getUsername();
        if (name == null || !login.isProtected(name)) {
            return; // cracked or unknown name: normal offline login
        }
        User user = event.getUser();
        event.setCancelled(true);

        byte[] token = new byte[4];
        random.nextBytes(token);
        pending.put(user, new Pending(name, token, start.getPlayerUUID().orElse(null), user.getClientVersion(),
                ipOf(user), System.currentTimeMillis()));
        user.sendPacket(new WrapperLoginServerEncryptionRequest("", keyPair.getPublic(), token, true));
    }

    private void onEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        Pending p = pending.remove(user);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        WrapperLoginClientEncryptionResponse response = new WrapperLoginClientEncryptionResponse(event);
        Optional<byte[]> encryptedToken = response.getEncryptedVerifyToken();
        byte[] encryptedSecret = response.getEncryptedSharedSecret();
        if (encryptedToken.isEmpty() || encryptedSecret == null) {
            plugin.getLogger().warning("Login check for " + p.name() + ": the client sent no verify token.");
            user.closeConnection();
            return;
        }

        byte[] sharedSecret;
        try {
            byte[] token = rsaDecrypt(encryptedToken.get());
            if (!MessageDigest.isEqual(token, p.verifyToken())) {
                plugin.getLogger().warning("Login check for " + p.name() + ": wrong verify token.");
                user.closeConnection();
                return;
            }
            sharedSecret = rsaDecrypt(encryptedSecret);
            // The client encrypts everything from here on; switch the connection to encryption right now,
            // on this network thread, before any further bytes arrive.
            enableEncryption(user, new SecretKeySpec(sharedSecret, "AES"));
        } catch (Exception e) {
            plugin.getLogger().warning("Login check for " + p.name() + " failed: " + e);
            user.closeConnection();
            return;
        }

        String serverId = serverId(sharedSecret);
        MojangApi.hasJoined(p.name(), serverId).whenComplete((profile, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Mojang check for " + p.name() + " failed: " + error);
            } else if (profile.isEmpty()) {
                plugin.getLogger().warning("Mojang did not confirm " + p.name()
                        + " (not logged in to that Mojang account).");
            } else {
                MojangApi.Profile pr = profile.get();
                verified.put(Accounts.key(p.name()),
                        new LoginManager.VerifiedLogin(pr.id(), pr.name(), pr.properties(), p.ip(),
                                System.currentTimeMillis()));
            }
            // Hand the login back to the server. Without a verified result LoginManager refuses the join,
            // and the kick message reaches the client encrypted, as it expects.
            user.receivePacketSilently(new WrapperLoginClientLoginStart(p.version(), p.name(), null, p.clientUuid()));
        });
    }

    // ------------------------------------------------------------------ crypto helpers

    private byte[] rsaDecrypt(byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        return cipher.doFinal(data);
    }

    /** Same server hash vanilla sends to Mojang: SHA-1 of (server id "", shared secret, public key) as signed hex. */
    private String serverId(byte[] sharedSecret) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
            sha1.update(sharedSecret);
            sha1.update(keyPair.getPublic().getEncoded());
            return new BigInteger(sha1.digest()).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Turns on AES encryption for this connection the way the server itself does in online mode: by calling the
     * server's own Connection#setEncryptionKey (Paper: SecretKey, vanilla: two Ciphers), found through the
     * "packet_handler" entry of the Netty pipeline.
     */
    private static void enableEncryption(User user, SecretKey key) throws Exception {
        Object channel = user.getChannel();
        Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
        Object connection = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, "packet_handler");
        if (connection == null) {
            throw new IllegalStateException("no packet_handler in the pipeline");
        }
        for (Method m : connection.getClass().getMethods()) {
            if (!m.getName().equals("setEncryptionKey") && !m.getName().equals("setupEncryption")) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(SecretKey.class)) {
                m.invoke(connection, key);
                return;
            }
            if (params.length == 2 && params[0] == Cipher.class && params[1] == Cipher.class) {
                IvParameterSpec iv = new IvParameterSpec(key.getEncoded());
                Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
                decrypt.init(Cipher.DECRYPT_MODE, key, iv);
                Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
                encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
                m.invoke(connection, decrypt, encrypt);
                return;
            }
        }
        throw new IllegalStateException("no setEncryptionKey method on " + connection.getClass().getName());
    }

    private static String ipOf(User user) {
        InetSocketAddress address = user.getAddress();
        return address == null || address.getAddress() == null ? "" : address.getAddress().getHostAddress();
    }
}
