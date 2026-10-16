package net.friendsserver.tools;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Keys, certificate files and the TLS setup of the upload website (JDK only). */
final class TlsSupport {

    private TlsSupport() {
    }

    static KeyPair newKey() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** Private key (PKCS#8) and public key in one PEM file, readable only by the server's user. */
    static void writeKey(Path file, KeyPair key) throws IOException {
        String pem = pem("PRIVATE KEY", key.getPrivate().getEncoded()) + pem("PUBLIC KEY", key.getPublic().getEncoded());
        writePrivate(file, pem);
    }

    static KeyPair readKey(Path file) throws IOException, GeneralSecurityException {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        KeyFactory factory = KeyFactory.getInstance("EC");
        PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(unpem(text, "PRIVATE KEY")));
        PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(unpem(text, "PUBLIC KEY")));
        return new KeyPair(pub, priv);
    }

    static void writeChain(Path file, List<X509Certificate> chain) throws IOException, CertificateException {
        StringBuilder out = new StringBuilder();
        for (X509Certificate cert : chain) {
            out.append(pem("CERTIFICATE", cert.getEncoded()));
        }
        writePrivate(file, out.toString());
    }

    static List<X509Certificate> readChain(Path file) throws IOException, CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : factory.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            chain.add((X509Certificate) c);
        }
        return chain;
    }

    /** True if the certificate is for this domain (subject alternative name). */
    static boolean covers(X509Certificate cert, String domain) {
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names == null) {
                return false;
            }
            for (List<?> name : names) {
                if (name.size() >= 2 && Integer.valueOf(2).equals(name.get(0))
                        && domain.equalsIgnoreCase(String.valueOf(name.get(1)))) {
                    return true;
                }
            }
        } catch (CertificateException ignored) {
            // unreadable extension
        }
        return false;
    }

    /** Renew after two thirds of the certificate's lifetime (Let's Encrypt's advice), or when expired. */
    static boolean needsRenewal(X509Certificate cert, Instant now) {
        long from = cert.getNotBefore().getTime();
        long to = cert.getNotAfter().getTime();
        return now.toEpochMilli() >= from + (to - from) * 2 / 3;
    }

    static boolean valid(X509Certificate cert, Instant now) {
        return now.isAfter(cert.getNotBefore().toInstant()) && now.isBefore(cert.getNotAfter().toInstant());
    }

    static SSLContext context(ReloadingKeyManager keys) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {keys}, null, null);
        return context;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] unpem(String text, String type) throws IOException {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int a = text.indexOf(begin);
        int b = text.indexOf(end);
        if (a < 0 || b < a) {
            throw new IOException("no " + type.toLowerCase(Locale.ROOT) + " in the file");
        }
        return Base64.getMimeDecoder().decode(text.substring(a + begin.length(), b));
    }

    private static void writePrivate(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** A key manager whose certificate can be replaced while the web server runs (renewals). */
    static final class ReloadingKeyManager extends X509ExtendedKeyManager {
        private volatile X509ExtendedKeyManager delegate;

        void use(PrivateKey key, List<X509Certificate> chain) throws GeneralSecurityException, IOException {
            char[] password = "friendstools".toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("web", key, password, chain.toArray(new X509Certificate[0]));
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            for (KeyManager km : factory.getKeyManagers()) {
                if (km instanceof X509ExtendedKeyManager x) {
                    delegate = x;
                    return;
                }
            }
            throw new GeneralSecurityException("no X509 key manager available");
        }

        boolean ready() {
            return delegate != null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return delegate.chooseEngineClientAlias(keyType, issuers, engine);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }
    }
}
