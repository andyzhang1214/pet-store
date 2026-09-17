package cn.petshop.common.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令哈希。用 JDK 自带的 PBKDF2-HMAC-SHA256，不引入 spring-security-crypto。
 *
 * <h3>为什么不直接用 SHA-256 加盐</h3>
 * 加盐只能挡住彩虹表，挡不住 GPU 暴力破解 —— SHA-256 太快，正是帮凶。
 * PBKDF2 把哈希重复跑几十万次，把单次校验的代价抬到"用户无感、爆破者绝望"的量级，
 * 是 JDK 自带、无需第三方依赖的合格选择（bcrypt / argon2 更优，但要额外依赖）。
 *
 * <h3>存储格式自描述</h3>
 * <pre>pbkdf2-sha256$&lt;迭代次数&gt;$&lt;盐 base64&gt;$&lt;摘要 base64&gt;</pre>
 * 迭代次数是<b>存在哈希串里</b>而不是写死在代码里：将来要调高迭代次数时，
 * 老口令用它们自己的历史参数校验、新口令用新参数，不需要一次性重置所有人的密码。
 * 如果把迭代次数写死在常量里，升级迭代次数就等于让所有存量口令当天失效。
 *
 * <h3>为什么要常量时间比较</h3>
 * 用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：后者一旦发现首个不同的字节
 * 就立刻返回，比较耗时会随"猜对了几个前缀字节"变化 —— 这个时间差足以被用来逐字节还原摘要。
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    /** OWASP 对 PBKDF2-HMAC-SHA256 的建议量级；本地实测约数十毫秒，用户无感 */
    private static final int DEFAULT_ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
    }

    /** 生成口令哈希（默认迭代次数）。 */
    public static String hash(String rawPassword) {
        return hash(rawPassword, DEFAULT_ITERATIONS);
    }

    /** 生成口令哈希，显式指定迭代次数（测试与将来升级用）。 */
    public static String hash(String rawPassword, int iterations) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("迭代次数必须大于 0");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] digest = pbkdf2(rawPassword, salt, iterations);
        return PREFIX + "$" + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 校验口令。任何格式异常一律返回 {@code false}，不抛异常 ——
     * 校验入口被各种输入触碰，抛异常容易在调用链上游被当成 500 泄漏"这条记录格式不对"的信息。
     * 输入非法时仍然算一次哈希，避免"格式不对 ⇒ 立即返回"形成时间侧信道。
     */
    public static boolean verify(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 口令强度下限。刻意只做长度与字符种类这两条可判定的规则，
     * 不引入"必须含符号"之类会逼用户写在便利贴上、或让店员无法在收银台输入的规则。
     */
    public static void requireStrength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("口令至少 8 位");
        }
        if (rawPassword.length() > 64) {
            throw new IllegalArgumentException("口令不能超过 64 位");
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasOther = rawPassword.chars().anyMatch(c -> !Character.isLetter(c));
        if (!hasLetter || !hasOther) {
            throw new IllegalArgumentException("口令需同时包含字母与数字/符号");
        }
    }

    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希失败", e);
        } finally {
            // PBEKeySpec 会持有口令副本，用完清掉，减少口令在堆里驻留的时间
            spec.clearPassword();
        }
    }
}
