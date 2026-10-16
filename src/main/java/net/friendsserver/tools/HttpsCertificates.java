package net.friendsserver.tools;

import org.bukkit.Bukkit;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Free, automatic https for the upload website: a DuckDNS name (yourname.duckdns.org) and a Let's Encrypt certificate
 * for it, checked with the DNS-01 challenge through DuckDNS' API. Works on any port, no port 80/443 needed.
 * The certificate is renewed automatically after two thirds of its lifetime, without restarting the website.
 *
 * <p>Files: plugins/FriendsTools/https/(letsencrypt|staging)/account.key, domain.key, cert.pem.</p>
 */
final class HttpsCertificates {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final FriendsTools plugin;
    private final TlsSupport.ReloadingKeyManager keys = new TlsSupport.ReloadingKeyManager();
    private SSLContext context;
    private ScheduledExecutorService worker;
    private volatile String domain;
    private volatile X509Certificate current;
    private String subdomain;
    private String token;
    private Path dir;
    private String directoryUrl;
    private Runnable onFirstCertificate;

    HttpsCertificates(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** "ourserver", "ourserver.duckdns.org" or "https://ourserver.duckdns.org/" -> "ourserver" (or "" if invalid). */
    static String subdomain(String configured) {
        if (configured == null) {
            return "";
        }
        String s = configured.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        if (s.endsWith(".duckdns.org")) {
            s = s.substring(0, s.length() - ".duckdns.org".length());
        }
        return s.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?") ? s : "";
    }

    boolean configured() {
        return domain != null;
    }

    String domain() {
        return domain;
    }

    /** A valid certificate is loaded. */
    boolean ready() {
        X509Certificate cert = current;
        String name = domain;
        return keys.ready() && cert != null && name != null && TlsSupport.covers(cert, name)
                && TlsSupport.valid(cert, Instant.now());
    }

    SSLContext context() {
        return context;
    }

    /**
     * Loads a saved certificate right away (so a restart serves https immediately) and gets or renews one in the
     * background. {@code onFirstCertificate} runs on the main thread when a certificate becomes available that wasn't
     * there at start.
     */
    void start(Runnable onFirstCertificate) {
        stop();
        current = null;
        String configuredDomain = plugin.getConfig().getString("disc-upload.duckdns-domain", "");
        subdomain = subdomain(configuredDomain);
        token = plugin.getConfig().getString("disc-upload.duckdns-token", "");
        if (configuredDomain == null || configuredDomain.isBlank()) {
            if (token != null && !token.isBlank()) {
                plugin.getLogger().warning("HTTPS: disc-upload.duckdns-token is set but duckdns-domain is empty."
                        + " Put your DuckDNS name there (e.g. \"ourserver\" for ourserver.duckdns.org).");
            }
            return;
        }
        if (subdomain.isEmpty() || token == null || token.isBlank()) {
            plugin.getLogger().warning("HTTPS: disc-upload.duckdns-domain or duckdns-token is not valid, so the upload"
                    + " website stays on http.");
            return;
        }
        token = token.trim();
        domain = subdomain + ".duckdns.org";
        boolean staging = plugin.getConfig().getBoolean("disc-upload.https-staging", false);
        directoryUrl = staging ? AcmeClient.LETSENCRYPT_STAGING : AcmeClient.LETSENCRYPT;
        dir = plugin.getDataFolder().toPath().resolve("https").resolve(staging ? "staging" : "letsencrypt");
        this.onFirstCertificate = onFirstCertificate;
        try {
            context = TlsSupport.context(keys);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("HTTPS: TLS is not available (" + e + ")");
            domain = null;
            return;
        }
        loadSaved();

        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FriendsTools https");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::check, 5, 12 * 60 * 60, TimeUnit.SECONDS);
    }

    void stop() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        domain = null;
    }

    private void loadSaved() {
        Path certFile = dir.resolve("cert.pem");
        Path keyFile = dir.resolve("domain.key");
        if (!Files.exists(certFile) || !Files.exists(keyFile)) {
            return;
        }
        try {
            List<X509Certificate> chain = TlsSupport.readChain(certFile);
            X509Certificate leaf = chain.get(0);
            if (!TlsSupport.covers(leaf, domain) || !TlsSupport.valid(leaf, Instant.now())) {
                return;
            }
            keys.use(TlsSupport.readKey(keyFile).getPrivate(), chain);
            current = leaf;
            plugin.getLogger().info("HTTPS: certificate for " + domain + " loaded, valid until "
                    + leaf.getNotAfter().toInstant() + ".");
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            plugin.getLogger().warning("HTTPS: could not read the saved certificate (" + e + "), getting a new one.");
        }
    }

    /** Runs on the worker thread: get a certificate if there is none, or renew it when it's due. */
    private void check() {
        X509Certificate cert = current;
        if (cert != null && TlsSupport.covers(cert, domain) && !TlsSupport.needsRenewal(cert, Instant.now())) {
            return;
        }
        boolean first = !ready();
        try {
            obtain();
            if (first && onFirstCertificate != null) {
                Bukkit.getScheduler().runTask(plugin, onFirstCertificate);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().warning("HTTPS: getting the certificate for " + domain + " failed: " + safe(e)
                    + ". Trying again in an hour." + (first ? " The upload website uses http until then." : ""));
            ScheduledExecutorService w = worker;
            if (w != null && !w.isShutdown()) {
                w.schedule(this::check, 1, TimeUnit.HOURS);
            }
        }
    }

    private void obtain() throws IOException, GeneralSecurityException, InterruptedException {
        Path accountFile = dir.resolve("account.key");
        KeyPair account;
        if (Files.exists(accountFile)) {
            account = TlsSupport.readKey(accountFile);
        } else {
            account = TlsSupport.newKey();
            TlsSupport.writeKey(accountFile, account);
        }
        KeyPair domainKey = TlsSupport.newKey();
        plugin.getLogger().info("HTTPS: getting a certificate for " + domain
                + " from Let's Encrypt, this takes about a minute...");
        AcmeClient client = new AcmeClient(HTTP, directoryUrl, account,
                message -> plugin.getLogger().info("HTTPS: " + message));
        List<X509Certificate> chain = client.obtain(domain, domainKey, new DuckDnsTxt(), Duration.ofSeconds(60));
        TlsSupport.writeKey(dir.resolve("domain.key"), domainKey);
        TlsSupport.writeChain(dir.resolve("cert.pem"), chain);
        keys.use(domainKey.getPrivate(), chain);
        current = chain.get(0);
        plugin.getLogger().info("HTTPS: certificate for " + domain + " is ready, valid until "
                + current.getNotAfter().toInstant() + ". It renews itself.");
    }

    /** Error text without the DuckDNS token. */
    private String safe(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return token == null || token.isEmpty() ? text : text.replace(token, "***");
    }

    /** The TXT record through DuckDNS' update API (it answers it for _acme-challenge.yourname.duckdns.org too). */
    private final class DuckDnsTxt implements AcmeClient.DnsTxt {
        @Override
        public void set(String value) throws IOException, InterruptedException {
            call("&txt=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        @Override
        public void clear() throws IOException, InterruptedException {
            call("&txt=removed&clear=true");
        }

        private void call(String params) throws IOException, InterruptedException {
            URI uri = URI.create("https://www.duckdns.org/update?domains=" + subdomain + "&token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8) + params + "&verbose=true");
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body().trim();
            if (resp.statusCode() != 200 || !body.startsWith("OK")) {
                throw new IOException("DuckDNS refused the update (answer \"" + (body.startsWith("KO") ? "KO" : body)
                        + "\"). Check disc-upload.duckdns-domain and duckdns-token.");
            }
        }
    }
}
