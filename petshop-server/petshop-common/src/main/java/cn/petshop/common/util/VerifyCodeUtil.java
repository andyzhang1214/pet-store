package cn.petshop.common.util;

import java.security.SecureRandom;

public final class VerifyCodeUtil {
    private static final SecureRandom RANDOM = new SecureRandom();

    private VerifyCodeUtil() {
    }

    public static String generate() {
        int body = RANDOM.nextInt(1_000_000);
        return String.format("%06d%02d", body, body % 97);
    }

    public static boolean validate(String code) {
        if (code == null || !code.matches("\\d{8}")) {
            return false;
        }
        int body = Integer.parseInt(code.substring(0, 6));
        int check = Integer.parseInt(code.substring(6));
        return check == body % 97;
    }
}
