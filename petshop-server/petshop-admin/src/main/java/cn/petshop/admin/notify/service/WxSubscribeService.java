package cn.petshop.admin.notify.service;

import cn.petshop.admin.notify.entity.NotifyLog;
import cn.petshop.admin.notify.entity.WxSubscribeQuota;
import cn.petshop.admin.notify.mapper.NotifyLogMapper;
import cn.petshop.admin.notify.mapper.WxSubscribeQuotaMapper;
import cn.petshop.settle.event.RefundAppliedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息：把「有新退款申请」推到店长手机。
 *
 * <h3>是提醒通道，不是业务依赖</h3>
 * 本类任何失败都<b>不得</b>向上抛异常 —— 退款申请的事务已经提交，抛异常只会污染调用方
 * 而无济于事。模板未配置、配额用尽、微信接口报错，统一降级为「只发后台角标」，
 * 退款主流程照常可用。
 *
 * <h3>为什么需要配额表</h3>
 * 订阅消息是<b>一次性授权</b>：店长点一次「允许通知」才换来一次推送机会，推成功即消耗。
 * 这与公众号模板消息能无限推完全不同。所以要把剩余次数记在
 * {@code wx_subscribe_quota} 里，用完就停，而不是让推送静默失败、让店长以为系统坏了。
 *
 * <h3>幂等</h3>
 * 同一笔退款只推一次，锚点是 {@code notify_log} 的 {@code (biz_no, template_id)} 唯一索引。
 * 只有<b>推送成功</b>才写留痕；失败不写，这样将来想补推时不会被自己挡住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxSubscribeService {

    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 微信 thing 类型字段上限 20 个字符，超出会被判 47003 */
    private static final int THING_MAX = 20;

    private final NotifyLogMapper notifyLogMapper;
    private final WxSubscribeQuotaMapper quotaMapper;

    @Value("${petshop.wx.appid:}")
    private String appid;
    @Value("${petshop.wx.secret:}")
    private String secret;
    /** 退款申请提醒模板 ID，需在微信公众平台申请后填入；留空即降级为只发角标 */
    @Value("${petshop.notify.refund-template-id:}")
    private String refundTemplateId;
    /** 接收提醒的店长 openid，逗号分隔。只推给第一个有配额的人，避免同一件事通知多人 */
    @Value("${petshop.notify.manager-openids:}")
    private String managerOpenids;

    /** access_token 缓存。微信有效期 7200 秒，这里留出余量提前失效，避免边界上拿到刚过期的 token。 */
    private final Cache<String, String> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(110))
            .maximumSize(4)
            .build();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ==================== 对外 ====================

    /**
     * 店长在小程序点「允许通知」成功后上报，配额 +1。
     * 只有配置在 {@code manager-openids} 名单里的 openid 才记录 —— 否则任何顾客授权一下都会收到退款单信息。
     */
    public boolean grantQuota(String openid) {
        if (openid == null || openid.isBlank() || refundTemplateId == null || refundTemplateId.isBlank()) {
            return false;
        }
        if (!managerOpenids().contains(openid)) {
            log.info("[订阅授权] 非店长 openid，忽略授权上报");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = quotaMapper.grantOne(openid, refundTemplateId, now);
        if (updated == 0) {
            WxSubscribeQuota q = new WxSubscribeQuota();
            q.setOpenid(openid);
            q.setTemplateId(refundTemplateId);
            q.setRemainCount(1);
            q.setCreatedAt(now);
            q.setUpdatedAt(now);
            quotaMapper.insert(q);
        }
        return true;
    }

    /** 当前店长的剩余推送配额，供后台自检用。 */
    public int quotaOf(String openid) {
        if (openid == null || refundTemplateId == null || refundTemplateId.isBlank()) {
            return 0;
        }
        WxSubscribeQuota q = quotaMapper.selectOne(new LambdaQueryWrapper<WxSubscribeQuota>()
                .eq(WxSubscribeQuota::getOpenid, openid)
                .eq(WxSubscribeQuota::getTemplateId, refundTemplateId));
        return q == null || q.getRemainCount() == null ? 0 : q.getRemainCount();
    }

    /** 是否具备推送条件（凭据 + 模板 + 店长名单齐全）。缺任一项即降级为只发角标。 */
    public boolean isConfigured() {
        return notBlank(appid) && notBlank(secret) && notBlank(refundTemplateId) && !managerOpenids().isEmpty();
    }

    /** 配置的店长数量，供后台自检。 */
    public int managerCount() {
        return managerOpenids().size();
    }

    /** 各店长剩余配额快照。openid 只留尾号 —— 自检要看得见，但没必要把完整 openid 送到前端。 */
    public List<Map<String, Object>> quotaSnapshot() {
        return managerOpenids().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openidTail", o.length() <= 6 ? o : o.substring(o.length() - 6));
            m.put("remain", quotaOf(o));
            return m;
        }).toList();
    }

    /**
     * 推送退款申请提醒。<b>永不抛异常</b> —— 事务已提交，抛了只会污染调用方。
     * 任何一步不满足都只是记日志并安静返回，后台角标仍然工作。
     */
    public void notifyRefundApplied(RefundAppliedEvent event) {
        if (!isConfigured()) {
            log.info("[退款提醒] 微信订阅消息未配置（appid / secret / 模板 ID / 店长 openid），仅后台角标可用 refundNo={}",
                    event.refundNo());
            return;
        }
        if (alreadyNotified(event.refundNo())) {
            log.debug("[退款提醒] 该退款已推送过，跳过 refundNo={}", event.refundNo());
            return;
        }
        for (String openid : managerOpenids()) {
            if (pushTo(openid, event)) {
                return;
            }
        }
        log.warn("[退款提醒] 配置的店长均无可用订阅配额，本次仅后台角标 refundNo={}", event.refundNo());
    }

    // ==================== 内部 ====================

    private boolean pushTo(String openid, RefundAppliedEvent event) {
        LocalDateTime now = LocalDateTime.now();
        // 先扣配额：带 remain_count >= 1 条件，返回 0 说明没配额了
        if (quotaMapper.consumeOne(openid, refundTemplateId, now) == 0) {
            return false;
        }
        String err = null;
        try {
            String token = accessToken();
            String body = buildBody(openid, event);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(SEND_URL + "?access_token=" + token))
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json;charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(resp.body());
            int errcode = node.path("errcode").asInt(-1);
            if (errcode != 0) {
                err = "errcode=" + errcode + " errmsg=" + node.path("errmsg").asText();
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        if (err != null) {
            // 没送达就把配额还回去：店长的授权是稀缺资源，不该因为一次网络抖动被吃掉
            quotaMapper.grantOne(openid, refundTemplateId, now);
            log.warn("[退款提醒] 推送失败已退回配额 refundNo={} err={}", event.refundNo(), err);
            return false;
        }

        // 只有成功才写留痕（幂等锚点）；失败不写，避免挡住将来的补推
        NotifyLog ok = new NotifyLog();
        ok.setOpenid(openid);
        ok.setTemplateId(refundTemplateId);
        ok.setBizNo(event.refundNo());
        ok.setStatus(NotifyLog.STATUS_SUCCESS);
        ok.setCreatedAt(now);
        notifyLogMapper.insert(ok);
        log.info("[退款提醒] 已推送退款申请通知 refundNo={} amount={}", event.refundNo(), event.amount());
        return true;
    }

    private boolean alreadyNotified(String refundNo) {
        Long n = notifyLogMapper.selectCount(new LambdaQueryWrapper<NotifyLog>()
                .eq(NotifyLog::getBizNo, refundNo)
                .eq(NotifyLog::getTemplateId, refundTemplateId));
        return n != null && n > 0;
    }

    private String buildBody(String openid, RefundAppliedEvent event) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        // 字段名必须与你在公众平台申请到的模板字段一一对应，否则微信返回 47003。
        // 这里按「事项 / 金额 / 时间 / 备注」这套常见命名走；换模板时只改这一段。
        data.put("thing1", Map.of("value", bizText(event.bizType())));
        data.put("amount2", Map.of("value", "¥" + event.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()));
        data.put("time3", Map.of("value", LocalDateTime.now().format(FMT)));
        data.put("thing4", Map.of("value", truncate(event.reason())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", openid);
        body.put("template_id", refundTemplateId);
        // 点通知跳到钱包页：店长在小程序里也能看退款记录
        body.put("page", "subpages/wallet/wallet");
        body.put("data", data);
        return JSON.writeValueAsString(body);
    }

    /**
     * 取 access_token。微信侧有效期 7200 秒，缓存 110 分钟后重取。
     * 失败即抛 —— 调用方会捕获并把配额退回，不会吞掉问题。
     */
    private String accessToken() throws Exception {
        String cached = tokenCache.getIfPresent("token");
        if (cached != null) {
            return cached;
        }
        String url = TOKEN_URL + "?grant_type=client_credential&appid=" + appid + "&secret=" + secret;
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode node = JSON.readTree(resp.body());
        String token = node.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("获取 access_token 失败：" + resp.body());
        }
        tokenCache.put("token", token);
        return token;
    }

    private List<String> managerOpenids() {
        if (managerOpenids == null || managerOpenids.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(managerOpenids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String bizText(String bizType) {
        if ("CARD".equals(bizType)) {
            return "次卡退款";
        }
        if ("GOODS".equals(bizType)) {
            return "商品订单退款";
        }
        return "退款申请";
    }

    private String truncate(String s) {
        if (s == null || s.isBlank()) {
            return "顾客未填写";
        }
        String v = s.trim();
        return v.length() <= THING_MAX ? v : v.substring(0, THING_MAX);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
