package net.friendsserver.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal ACME (RFC 8555) client for Let's Encrypt with the DNS-01 challenge. Only uses the JDK and Gson.
 *
 * <p>Account and certificate keys are EC P-256. Flow: account, order, DNS-01 challenge (TXT record set through
 * {@link DnsTxt}), finalize with a CSR, download the certificate chain.</p>
 */
final class AcmeClient {

    static final String LETSENCRYPT = "https://acme-v02.api.letsencrypt.org/directory";
    static final String LETSENCRYPT_STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory";

    /** Sets and removes the _acme-challenge TXT record of the domain. */
    interface DnsTxt {
        void set(String value) throws IOException, InterruptedException;

        void clear() throws IOException, InterruptedException;
    }

    private record Response(int status, String body, String location, String retryAfter) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(4);

    private final HttpClient http;
    private final URI directoryUrl;
    private final KeyPair accountKey;
    private final Consumer<String> log;
    private JsonObject directory;
    private String nonce;
    private String accountUrl;

    AcmeClient(HttpClient http, String directoryUrl, KeyPair accountKey, Consumer<String> log) {
        this.http = http;
        this.directoryUrl = URI.create(directoryUrl);
        this.accountKey = accountKey;
        this.log = log;
    }

    /**
     * Gets a certificate for {@code domain}. Blocks for a few minutes (DNS propagation, validation).
     *
     * @return the certificate chain, leaf first
     */
    List<X509Certificate> obtain(String domain, KeyPair domainKey, DnsTxt dns, Duration propagation)
            throws IOException, GeneralSecurityException, InterruptedException {
        directory = get(directoryUrl).json();
        accountUrl = newAccount();

        JsonObject identifier = new JsonObject();
        identifier.addProperty("type", "dns");
        identifier.addProperty("value", domain);
        JsonArray identifiers = new JsonArray();
        identifiers.add(identifier);
        JsonObject orderRequest = new JsonObject();
        orderRequest.add("identifiers", identifiers);
        Response created = post(url("newOrder"), orderRequest, false);
        String orderUrl = created.location();
        if (orderUrl == null) {
            throw new IOException("ACME: order has no URL");
        }
        JsonObject order = created.json();

        for (JsonElement authUrl : order.getAsJsonArray("authorizations")) {
            authorize(authUrl.getAsString(), domainKey, dns, propagation);
        }

        order = poll(orderUrl, "ready", "valid");
        if (str(order, "status").equals("ready")) {
            JsonObject finalize = new JsonObject();
            finalize.addProperty("csr", B64.encodeToString(csr(domain, domainKey)));
            post(str(order, "finalize"), finalize, false);
            order = poll(orderUrl, "valid");
        }
        String certUrl = str(order, "certificate");
        Response pem = post(certUrl, null, false);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : factory.generateCertificates(
                new ByteArrayInputStream(pem.body().getBytes(StandardCharsets.US_ASCII)))) {
            chain.add((X509Certificate) c);
        }
        if (chain.isEmpty()) {
            throw new IOException("ACME: empty certificate chain");
        }
        return chain;
    }

    private void authorize(String authUrl, KeyPair domainKey, DnsTxt dns, Duration propagation)
            throws IOException, GeneralSecurityException, InterruptedException {
        JsonObject auth = post(authUrl, null, false).json();
        if (str(auth, "status").equals("valid")) {
            return; // validated recently, nothing to do
        }
        JsonObject challenge = null;
        for (JsonElement c : auth.getAsJsonArray("challenges")) {
            if (str(c.getAsJsonObject(), "type").equals("dns-01")) {
                challenge = c.getAsJsonObject();
            }
        }
        if (challenge == null) {
            throw new IOException("ACME: no dns-01 challenge offered");
        }
        String keyAuthorization = str(challenge, "token") + "." + thumbprint();
        String txt = B64.encodeToString(sha256(keyAuthorization.getBytes(StandardCharsets.US_ASCII)));
        try {
            dns.set(txt);
            log.accept("DNS record set, waiting " + propagation.toSeconds() + " s before the check...");
            Thread.sleep(propagation.toMillis());
            post(str(challenge, "url"), new JsonObject(), false);
            JsonObject done = poll(authUrl, "valid");
            if (!str(done, "status").equals("valid")) {
                throw new IOException("ACME: authorization " + str(done, "status"));
            }
        } finally {
            try {
                dns.clear();
            } catch (IOException e) {
                log.accept("Could not remove the DNS record again: " + e.getMessage());
            }
        }
    }

    /** POST-as-GET until the object has one of the wanted statuses; "invalid" fails with the server's reason. */
    private JsonObject poll(String url, String... wanted)
            throws IOException, GeneralSecurityException, InterruptedException {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (true) {
            Response r = post(url, null, false);
            JsonObject obj = r.json();
            String status = str(obj, "status");
            for (String w : wanted) {
                if (w.equals(status)) {
                    return obj;
                }
            }
            if (status.equals("invalid")) {
                throw new IOException("ACME: " + url.substring(url.lastIndexOf('/') + 1) + " is invalid: "
                        + problemDetail(obj));
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("ACME: timed out waiting, status " + status);
            }
            long wait = 3000;
            if (r.retryAfter() != null) {
                try {
                    wait = Math.min(10_000, Math.max(1000, Long.parseLong(r.retryAfter().trim()) * 1000));
                } catch (NumberFormatException ignored) {
                    // HTTP date format: keep the default
                }
            }
            Thread.sleep(wait);
        }
    }

    /** The error of an invalid authorization/order, or of its failed challenge. */
    private static String problemDetail(JsonObject obj) {
        if (obj.has("error")) {
            return detail(obj.getAsJsonObject("error"));
        }
        if (obj.has("challenges")) {
            for (JsonElement c : obj.getAsJsonArray("challenges")) {
                JsonObject ch = c.getAsJsonObject();
                if (ch.has("error")) {
                    return detail(ch.getAsJsonObject("error"));
                }
            }
        }
        return "no reason given";
    }

    private static String detail(JsonObject problem) {
        String type = str(problem, "type");
        String detail = str(problem, "detail");
        return detail.isEmpty() ? type : detail + (type.isEmpty() ? "" : " (" + type + ")");
    }

    private String newAccount() throws IOException, GeneralSecurityException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("termsOfServiceAgreed", true);
        Response r = post(url("newAccount"), request, true);
        if (r.location() == null) {
            throw new IOException("ACME: account has no URL");
        }
        return r.location();
    }

    // ------------------------------------------------------------------ HTTP + JWS

    private String url(String name) throws IOException {
        if (directory == null || !directory.has(name)) {
            throw new IOException("ACME directory has no " + name);
        }
        return directory.get(name).getAsString();
    }

    private Response get(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET()
                .header("User-Agent", "FriendsTools-ACME").build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("ACME: GET " + uri + " returned " + resp.statusCode());
        }
        return new Response(resp.statusCode(), resp.body(), null, null);
    }

    private void fetchNonce() throws IOException, InterruptedException {
        HttpResponse<Void> resp = http.send(HttpRequest.newBuilder(URI.create(url("newNonce")))
                .timeout(Duration.ofSeconds(30)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "FriendsTools-ACME").build(), HttpResponse.BodyHandlers.discarding());
        nonce = resp.headers().firstValue("Replay-Nonce").orElseThrow(() -> new IOException("ACME: no nonce"));
    }

    /** Signed POST; {@code payload == null} is a POST-as-GET. Retries on badNonce. */
    private Response post(String url, JsonObject payload, boolean withJwk)
            throws IOException, GeneralSecurityException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            if (nonce == null) {
                fetchNonce();
            }
            JsonObject protectedHeader = new JsonObject();
            protectedHeader.addProperty("alg", "ES256");
            if (withJwk) {
                protectedHeader.add("jwk", jwk());
            } else {
                protectedHeader.addProperty("kid", accountUrl);
            }
            protectedHeader.addProperty("nonce", nonce);
            protectedHeader.addProperty("url", url);
            nonce = null;

            String protected64 = B64.encodeToString(protectedHeader.toString().getBytes(StandardCharsets.UTF_8));
            String payload64 = payload == null ? ""
                    : B64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(accountKey.getPrivate());
            signer.update((protected64 + "." + payload64).getBytes(StandardCharsets.US_ASCII));
            JsonObject jws = new JsonObject();
            jws.addProperty("protected", protected64);
            jws.addProperty("payload", payload64);
            jws.addProperty("signature", B64.encodeToString(signer.sign()));

            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/jose+json")
                    .header("User-Agent", "FriendsTools-ACME")
                    .POST(HttpRequest.BodyPublishers.ofString(jws.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            nonce = resp.headers().firstValue("Replay-Nonce").orElse(null);
            String location = resp.headers().firstValue("Location").orElse(null);
            String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
            if (resp.statusCode() / 100 == 2) {
                return new Response(resp.statusCode(), resp.body(), location, retryAfter);
            }
            String type = "";
            String detail = resp.body();
            try {
                JsonObject problem = JsonParser.parseString(resp.body()).getAsJsonObject();
                type = str(problem, "type");
                detail = detail(problem);
            } catch (RuntimeException ignored) {
                // not JSON
            }
            if (type.endsWith(":badNonce") && attempt < 3) {
                continue;
            }
            throw new IOException("ACME error " + resp.statusCode() + ": " + detail);
        }
    }

    private JsonObject jwk() {
        ECPublicKey pub = (ECPublicKey) accountKey.getPublic();
        JsonObject jwk = new JsonObject(); // members in lexicographic order (also used for the thumbprint)
        jwk.addProperty("crv", "P-256");
        jwk.addProperty("kty", "EC");
        jwk.addProperty("x", B64.encodeToString(unsigned32(pub.getW().getAffineX())));
        jwk.addProperty("y", B64.encodeToString(unsigned32(pub.getW().getAffineY())));
        return jwk;
    }

    private String thumbprint() throws GeneralSecurityException {
        return B64.encodeToString(sha256(jwk().toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    // ------------------------------------------------------------------ CSR (PKCS#10, DER)

    /** A certificate request for one DNS name, signed with the EC key (ecdsa-with-SHA256). */
    static byte[] csr(String domain, KeyPair key) throws GeneralSecurityException {
        byte[] cn = oid(2, 5, 4, 3);
        byte[] subject = seq(set(seq(cn, tlv(0x0C, domain.getBytes(StandardCharsets.UTF_8)))));
        byte[] spki = key.getPublic().getEncoded();
        byte[] generalNames = seq(tlv(0x82, domain.getBytes(StandardCharsets.US_ASCII)));
        byte[] extensions = seq(seq(oid(2, 5, 29, 17), tlv(0x04, generalNames)));
        byte[] extensionRequest = seq(oid(1, 2, 840, 113549, 1, 9, 14), set(extensions));
        byte[] attributes = tlv(0xA0, extensionRequest);
        byte[] info = seq(new byte[] {0x02, 0x01, 0x00}, subject, spki, attributes);

        PrivateKey priv = key.getPrivate();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(priv);
        signer.update(info);
        byte[] signature = signer.sign();
        byte[] algorithm = seq(oid(1, 2, 840, 10045, 4, 3, 2));
        byte[] bits = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return seq(info, algorithm, tlv(0x03, bits));
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] oid(long... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((int) (arcs[0] * 40 + arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            long v = arcs[i];
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>= 7;
            } while (v > 0);
            for (int j = n - 1; j >= 0; j--) {
                out.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, out.toByteArray());
    }
}
