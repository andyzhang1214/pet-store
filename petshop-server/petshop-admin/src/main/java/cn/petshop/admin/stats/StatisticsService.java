package cn.petshop.admin.stats;

import cn.petshop.admin.stats.mapper.StatisticsMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据统计服务：把跨模块的聚合查询拼装成后台报表 VO。
 *
 * <h3>营收口径（写在这里而不是前端，是因为口径要跟服务端单测锁定，不能让展示层各自解释）</h3>
 * <ul>
 *   <li><b>服务营收</b>：settle_bill.total 按日汇总，含余额 / 现金 / 次卡三种结算方式。
 *       次卡结算的现金发生在售卡日，计入服务营收同时单列笔数，避免「次卡单是不是营收」的口径争议。</li>
 *   <li><b>商品营收</b>：goods_order 中 PAID + VERIFIED 的 total —— 已支付才算卖出去。</li>
 *   <li><b>充值</b>：balance_ledger 的 RECHARGE 流水。储值是负债不是收入，
 *       所以与营收分开展示，只作为门店现金流参考。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final StatisticsMapper statisticsMapper;

    public Overview overview(int days) {
        LocalDate today = LocalDate.now();
        // 趋势窗口从「days-1 天前的零点」到今天，生成的序列恒为 days 个点（含今天）
        LocalDateTime from = today.minusDays(days - 1L).atStartOfDay();

        Overview vo = new Overview();

        // ---------- 今日 KPI ----------
        List<Map<String, Object>> settles = statisticsMapper.settleDailyByPayType(today.atStartOfDay());
        Today td = vo.getToday();
        for (Map<String, Object> row : settles) {
            long cnt = ((Number) row.get("cnt")).longValue();
            BigDecimal amt = toAmount(row.get("amt"));
            td.setServiceRevenue(td.getServiceRevenue().add(amt));
            td.setServiceCount(td.getServiceCount() + cnt);
            if ("CARD".equals(row.get("pt"))) {
                td.setCardSettleCount(td.getCardSettleCount() + cnt);
            }
        }

        for (Map<String, Object> row : statisticsMapper.goodsDaily(today.atStartOfDay())) {
            td.setGoodsRevenue(td.getGoodsRevenue().add(toAmount(row.get("amt"))));
            td.setGoodsCount(td.getGoodsCount() + ((Number) row.get("cnt")).longValue());
        }

        Map<String, Object> recharge = statisticsMapper.rechargeSummary(today.atStartOfDay());
        td.setRechargePrincipal(toAmount(recharge.get("principal")));
        td.setRechargeGift(toAmount(recharge.get("gift")));
        td.setRechargeCount(((Number) recharge.get("cnt")).longValue());

        td.setNewMembers(statisticsMapper.newMembersSince(today.atStartOfDay()));

        Map<String, Long> apptByStatus = new LinkedHashMap<>();
        long apptTotal = 0;
        for (Map<String, Object> row : statisticsMapper.apptCountByStatus(today)) {
            long cnt = ((Number) row.get("cnt")).longValue();
            apptByStatus.put(String.valueOf(row.get("s")), cnt);
            apptTotal += cnt;
        }
        td.setApptByStatus(apptByStatus);
        td.setApptTotal(apptTotal);

        // ---------- 近 N 日趋势（服务 / 商品两条线，缺数据日补零） ----------
        Map<String, BigDecimal[]> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : statisticsMapper.settleDailyByPayType(from)) {
            byDay.computeIfAbsent(dayKey(row), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[0] =
                    byDay.get(dayKey(row))[0].add(toAmount(row.get("amt")));
        }
        for (Map<String, Object> row : statisticsMapper.goodsDaily(from)) {
            byDay.computeIfAbsent(dayKey(row), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                    byDay.get(dayKey(row))[1].add(toAmount(row.get("amt")));
        }
        List<TrendDay> trend = new ArrayList<>(days);
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            // 分组键与 SQL 的 DATE() 输出对齐（yyyy-MM-dd）；展示层再转成 MM-dd
            BigDecimal[] pair = byDay.getOrDefault(d.toString(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            TrendDay t = new TrendDay();
            t.setDate(d.format(DAY_FMT));
            t.setService(pair[0]);
            t.setGoods(pair[1]);
            trend.add(t);
        }
        vo.setTrend(trend);

        // ---------- 会员资产 ----------
        Map<String, Object> members = statisticsMapper.memberSummary();
        vo.getMembers().setTotal(((Number) members.get("total")).longValue());
        vo.getMembers().setBalanceTotal(toAmount(members.get("balance")));
        vo.getMembers().setFrozenTotal(toAmount(members.get("frozen")));

        return vo;
    }

    /** SQL 里 DATE(created_at) 返回的可能是 java.sql.Date / LocalDate / 字符串，统一归一成 yyyy-MM-dd。 */
    private String dayKey(Map<String, Object> row) {
        return String.valueOf(row.get("d")).substring(0, 10);
    }

    private BigDecimal toAmount(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(String.valueOf(v));
    }

    @Data
    public static class Overview {
        private Today today = new Today();
        private List<TrendDay> trend = new ArrayList<>();
        private Members members = new Members();
    }

    @Data
    public static class Today {
        /** 今日营收 = 服务结算 + 商品销售 */
        private BigDecimal serviceRevenue = BigDecimal.ZERO;
        private long serviceCount;
        private BigDecimal goodsRevenue = BigDecimal.ZERO;
        private long goodsCount;
        /** 次卡抵扣的服务结算单数（金额已计入 serviceRevenue） */
        private long cardSettleCount;
        private BigDecimal rechargePrincipal = BigDecimal.ZERO;
        private BigDecimal rechargeGift = BigDecimal.ZERO;
        private long rechargeCount;
        private long newMembers;
        private long apptTotal;
        private Map<String, Long> apptByStatus = new LinkedHashMap<>();

        /**
         * 今日总营收。写成标准 getter（get 前缀）才会被 Jackson 序列化 ——
         * 之前叫 totalRevenue()，接口 JSON 里就没有这个字段，前端「今日营收」恒显示 ¥0.00。
         */
        public BigDecimal getTotalRevenue() {
            return serviceRevenue.add(goodsRevenue);
        }
    }

    @Data
    public static class TrendDay {
        /** 展示用 MM-dd */
        private String date;
        private BigDecimal service = BigDecimal.ZERO;
        private BigDecimal goods = BigDecimal.ZERO;
    }

    @Data
    public static class Members {
        private long total;
        private BigDecimal balanceTotal = BigDecimal.ZERO;
        private BigDecimal frozenTotal = BigDecimal.ZERO;
    }
}
