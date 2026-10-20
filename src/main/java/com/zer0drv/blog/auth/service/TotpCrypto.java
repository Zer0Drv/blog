package com.zer0drv.blog.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** RFC 6238: SHA-1, six digits, 30 seconds. JCA supplies all crypto primitives. */
@Component
public class TotpCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final byte[] key;

    public TotpCrypto(@Value("${blog.totp.encryption-key:}") String encoded) {
        key = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
        if (key != null && key.length != 32) throw new IllegalArgumentException("TOTP encryption key must be 32 bytes (Base64)");
    }

    public boolean configured() { return key != null; }
    public static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    public static String newSecret() {
        byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes);
        return base32(bytes);
    }
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder(); int bits = 0, buffer = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(ALPHABET.charAt((buffer >>> bits) & 31)); }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
    private static byte[] decode(String secret) {
        byte[] out = new byte[secret.length() * 5 / 8]; int bits = 0, buffer = 0, index = 0;
        for (char c : secret.toCharArray()) {
            int n = ALPHABET.indexOf(c);
            if (n < 0) throw new IllegalArgumentException("Invalid Base32");
            buffer = (buffer << 5) | n; bits += 5;
            if (bits >= 8) { bits -= 8; out[index++] = (byte) (buffer >>> bits); }
        }
        return out;
    }
    public static String code(String secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[19] & 15;
            int value = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
            int divisor = digits == 8 ? 100000000 : 1000000;
            return String.format(Locale.ROOT, "%0" + digits + "d", value % divisor);
        } catch (Exception e) { throw new IllegalStateException("TOTP computation failed", e); }
    }
    public static long match(String secret, String code, long now, long lastStep) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        long step = now / 30;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            if (candidate > lastStep && MessageDigest.isEqual(code(secret, candidate, 6).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) return candidate;
        }
        return -1;
    }
    public String encrypt(long user, String secret) {
        if (key == null) throw new IllegalStateException("TOTP encryption key is not configured");
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Long.toString(user).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (Exception e) { throw new IllegalStateException("TOTP encryption failed", e); }
    }
    public String decrypt(long user, String encrypted) {
        if (key == null) throw new IllegalStateException("TOTP encryption key is not configured");
        try {
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(Long.toString(user).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.US_ASCII);
        } catch (Exception e) { throw new IllegalStateException("TOTP decryption failed", e); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
