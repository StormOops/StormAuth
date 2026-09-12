package dev.storm.stormauth.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {

    private static final int ITERATIONS = 65_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String encoded = Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + encoded;
    }

    public static boolean verify(String password, String stored) {
        try {
            if (stored.startsWith("pbkdf2$")) {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                // timing-safe сравнение, чтобы по времени ответа не читать длину совпадения
                return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
            }
            if (stored.startsWith("$SHA$")) {
                return verifyAuthmeSha(password, stored);
            }
            // открытый текст бывает только у импортированных аккаунтов и только до первого входа
            return MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isLegacy(String stored) {
        return !stored.startsWith("pbkdf2$");
    }

    // authme кладет sha256 в виде "$SHA$<соль>$<хэш>", где хэш = sha256(sha256(пароль) + соль)
    private static boolean verifyAuthmeSha(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        String inner = sha256Hex(password);
        String outer = sha256Hex(inner + parts[2]);
        return MessageDigest.isEqual(
                outer.getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8));
    }

    // pbkdf2 из jdk - bcrypt-зависимость в jar не тянем
    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("pbkdf2 недоступен в этой jvm", e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 недоступен в этой jvm", e);
        }
    }
}
