package cn.petshop.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自签 JWT（HS256），同一套密钥服务于两类互不相通的令牌。
 *
 * <h3>为什么成员令牌与后台令牌用不同的 claim 名</h3>
 * 成员令牌带 {@code mid}，后台令牌带 {@code aid}，两个解析器各自只认自己那个字段。
 * 于是「拿会员 token 去调 /admin/**」会在 {@link #parseAdminId} 处直接解析失败 ——
 * <b>域隔离由 claim 名天然成立，而不是靠调用方记得多校验一步</b>。
 * 若两者都叫 {@code uid}、只在别处比对角色，就变成"某天忘了校验就直接提权"的结构性隐患：
 * 会员登录拿到的令牌能被拿去审退款。
 *
 * <p>后台令牌只装管理员 id，不装角色与姓名。这不是省事，是故意的：
 * 角色与账号状态每次请求都回库读（见 {@code AdminAuthService}），
 * 因而「停用一个账号」「把店长降成店员」立刻生效，不必等令牌过期。
 * 把角色塞进令牌，撤销权限就得额外维护一张吊销名单 —— 那正是最容易出错的部分。
 *
 * <p>payload 用正则提取而非引入 JSON 库：令牌内容完全由本类生成、格式可控，
 * 不值得为此拉一个依赖。正则也让它与字段顺序解耦，{@code aid}/{@code exp} 谁先谁后都能解析。
 */
public final class JwtUtil {

    private static final Pattern MID_PATTERN = Pattern.compile("\"mid\"\\s*:\\s*(-?\\d+)");
    private static final Pattern AID_PATTERN = Pattern.compile("\"aid\"\\s*:\\s*(-?\\d+)");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(-?\\d+)");

    private JwtUtil() {
    }

    /** 签发会员令牌。 */
    public static String sign(long memberId, long ttlMillis, String secret) {
        return signWith("mid", memberId, ttlMillis, secret);
    }

    /** 签发后台管理员令牌。 */
    public static String signAdmin(long adminId, long ttlMillis, String secret) {
        return signWith("aid", adminId, ttlMillis, secret);
    }

    /** 解析会员令牌；签名不对、已过期、或不是会员令牌，一律返回 {@code null}。 */
    public static Long parseMemberId(String token, String secret) {
        return parseIdField(token, secret, MID_PATTERN);
    }

    /** 解析后台令牌；签名不对、已过期、或不是后台令牌，一律返回 {@code null}。 */
    public static Long parseAdminId(String token, String secret) {
        return parseIdField(token, secret, AID_PATTERN);
    }

    private static String signWith(String claim, long id, long ttlMillis, String secret) {
        long exp = Instant.now().toEpochMilli() + ttlMillis;
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64(("{\"" + claim + "\":" + id + ",\"exp\":" + exp + "}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + b64(hmac(signingInput, secret));
    }

    private static Long parseIdField(String token, String secret, Pattern idPattern) {
        if (token == null || secret == null || secret.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected;
        byte[] actual;
        try {
            expected = hmac(signingInput, secret);
            actual = unb64(parts[2]);
        } catch (RuntimeException e) {
            // 签名段不是合法 base64（随手拼的假令牌就是这样）—— 判失败，别冒泡成 500
            return null;
        }
        // 常量时间比较：用签名比对的时间差去反推签名内容是很实际的攻击路径
        if (!MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        String payload = new String(unb64(parts[1]), StandardCharsets.UTF_8);
        Matcher idMatcher = idPattern.matcher(payload);
        Matcher expMatcher = EXP_PATTERN.matcher(payload);
        if (!idMatcher.find() || !expMatcher.find()) {
            // 域隔离在这里生效：会员令牌没有 aid，后台令牌没有 mid
            return null;
        }
        try {
            long exp = Long.parseLong(expMatcher.group(1));
            if (exp < Instant.now().toEpochMilli()) {
                return null;
            }
            long id = Long.parseLong(idMatcher.group(1));
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] hmac(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("jwt sign failed", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
