package cn.normanclub.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Locale;

/** RFC 6238: SHA-1, six digits, 30-second steps. No network or external QR service. */
public final class Totp {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() { }
    public static String secret() {
        byte[] raw = new byte[20]; new SecureRandom().nextBytes(raw);
        StringBuilder out = new StringBuilder(); int value = 0, bits = 0;
        for (byte b : raw) { value = (value << 8) | (b & 255); bits += 8; while (bits >= 5) { bits -= 5; out.append(BASE32.charAt((value >> bits) & 31)); } }
        return out.toString();
    }
    static byte[] decode(String secret) {
        ByteBuffer bytes = ByteBuffer.allocate(secret.length() * 5 / 8); int value = 0, bits = 0;
        for (char c : secret.toCharArray()) { int n = BASE32.indexOf(c); if (n < 0) throw new IllegalArgumentException(); value = (value << 5) | n; bits += 5; if (bits >= 8) { bits -= 8; bytes.put((byte)(value >> bits)); } }
        return bytes.array();
    }
    public static String code(String secret, long step) {
        byte[] mac = Crypto.mac("HmacSHA1", decode(secret), ByteBuffer.allocate(8).putLong(step).array());
        int offset = mac[mac.length - 1] & 15;
        int value = ByteBuffer.wrap(mac, offset, 4).getInt() & 0x7fffffff;
        return String.format(Locale.ROOT, "%06d", value % 1_000_000);
    }
    public static long verify(String secret, String code, long nowMillis, long lastStep) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        long current = nowMillis / 30_000;
        for (long step = current - 1; step <= current + 1; step++)
            if (step > lastStep && Crypto.equal(code(secret, step), code)) return step;
        return -1;
    }
}
