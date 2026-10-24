package io.freemytv.proto;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * How the phone app and the TV app talk, compiled into both (build.sh and companion/build.sh), so they cannot drift apart.
 * Plain HTTP on the home Wi-Fi. The TV announces itself as SERVICE_TYPE; the phone pairs once with a PIN shown on the TV
 * and gets a token. Every later request is signed with it: HMAC-SHA256 over method, path, timestamp and body. The timestamp
 * only has to grow (the TV remembers the last one per phone), so the two clocks need not agree and a recorded request
 * cannot be sent again.
 */
public final class Proto {
    private Proto() {}

    public static final String SERVICE_TYPE = "_freemytv._tcp.";
    public static final int PORT = 8765;
    public static final int VERSION = 1;

    public static final String H_ID = "X-Id", H_TS = "X-Ts", H_SIG = "X-Sig";

    /** hex(HMAC-SHA256(token, method \n path \n ts \n body)) */
    public static String sign(String token, String method, String path, long ts, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((method + "\n" + path + "\n" + ts + "\n" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same length and same bytes, in time that does not depend on where they differ. */
    public static boolean same(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
