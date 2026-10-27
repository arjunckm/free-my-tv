package io.freemytv.proto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How the phone app and the TV app talk, compiled into both (build.sh and companion/build.sh), so they cannot drift apart.
 * Plain HTTP on the home Wi-Fi. The TV announces itself as SERVICE_TYPE.
 *
 * Pairing (once): the TV shows a PIN and both sides agree a secret key with ECDH, so nothing a listener on the Wi-Fi
 * sees is enough to learn the PIN or the token. Each side proves it knows the PIN with an HMAC under a key stretched from
 * the PIN (see secret()), and the token is derived from it, never sent. This is not a PAKE: someone who actively sits
 * between the two could test PINs offline, so the PIN is long (8 digits), slow to test and short-lived.
 *
 * Every later request is signed with the token: HMAC-SHA256 over method, path, timestamp and body. The timestamp only
 * has to be new for that phone (the two clocks need not agree), so a recorded request cannot be sent again. The TV
 * signs its replies the same way, so the phone knows they come from the TV it paired with.
 */
public final class Proto {
    private Proto() {}

    public static final String SERVICE_TYPE = "_freemytv._tcp.";
    public static final int PORT = 8765;
    public static final int VERSION = 3;
    /** Digits in the pairing PIN, and how many hashes it costs to try one. */
    public static final int PIN_DIGITS = 8;
    static final int PIN_ITERATIONS = 100_000;

    public static final String H_ID = "X-Id", H_TS = "X-Ts", H_SIG = "X-Sig";

    // MARK: - Requests and replies

    /** hex(HMAC-SHA256(token, method \n path \n ts \n body)) */
    public static String sign(String token, String method, String path, long ts, String body) {
        return hex(hmac(token.getBytes(StandardCharsets.UTF_8), (method + "\n" + path + "\n" + ts + "\n" + body).getBytes(StandardCharsets.UTF_8)));
    }

    /** hex(HMAC-SHA256(token, "reply" \n ts \n status \n body)): the TV's signature on what it answered to the request with that timestamp. */
    public static String signReply(String token, long ts, int status, byte[] body) {
        byte[] head = ("reply\n" + ts + "\n" + status + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return hex(hmac(token.getBytes(StandardCharsets.UTF_8), all));
    }

    // MARK: - Pairing

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    /** The shared key: SHA-256 of the ECDH secret between our private key and the other side's public key (X.509 bytes). */
    public static byte[] agree(PrivateKey mine, byte[] theirPublic) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(theirPublic)), true);
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
    }

    /**
     * The PIN made slow to guess: PBKDF2 over the shared key. The PIN is not a real PAKE, so someone who sits between the
     * phone and the TV could try PINs against a proof offline; this makes each try cost PIN_ITERATIONS hashes, and the PIN
     * only lives for two minutes.
     */
    public static byte[] secret(byte[] key, String pin) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new javax.crypto.spec.PBEKeySpec(pin.toCharArray(), key, PIN_ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a side sends to show it knows the PIN: role is "phone" or "tv"; secret comes from secret(). */
    public static String proof(byte[] secret, String role) {
        return hex(hmac(secret, ("proof|" + role).getBytes(StandardCharsets.UTF_8)));
    }

    /** The token both sides derive after pairing. */
    public static String token(byte[] secret) {
        return hex(hmac(secret, "token".getBytes(StandardCharsets.UTF_8)));
    }

    // MARK: - Helpers

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Same length and same bytes, in time that does not depend on where they differ. */
    public static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
