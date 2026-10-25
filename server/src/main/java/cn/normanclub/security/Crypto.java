package cn.normanclub.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import cn.normanclub.config.ClubConfig;

@Component
public class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ClubConfig config;
    public Crypto(ClubConfig config) { this.config = config; }
    public static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    public static String otp() { return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000)); }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public String digest(String text) { return HexFormat.of().formatHex(mac("HmacSHA256", config.hashKey, text.getBytes(StandardCharsets.UTF_8))); }
    public static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    public String encrypt(String text) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(config.encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("加密失败", e); }
    }
    public String decrypt(String text) {
        try {
            if (!text.startsWith("v1:")) throw new GeneralSecurityException();
            byte[] raw = Base64.getDecoder().decode(text.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(config.encryptionKey, "AES"), new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(raw, 12, raw.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("密钥不匹配或加密数据损坏"); }
    }
    static byte[] mac(String algorithm, byte[] key, byte[] bytes) {
        try { Mac mac = Mac.getInstance(algorithm); mac.init(new SecretKeySpec(key, algorithm)); return mac.doFinal(bytes); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
