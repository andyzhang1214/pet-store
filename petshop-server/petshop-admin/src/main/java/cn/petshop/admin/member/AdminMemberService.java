package cn.petshop.admin.member;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.enums.CardBizType;
import cn.petshop.common.enums.HealthLogType;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.MemberLevel;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.entity.PetHealthLog;
import cn.petshop.member.mapper.MemberLevelMapper;
import cn.petshop.member.mapper.MemberMapper;
import cn.petshop.member.mapper.PetHealthLogMapper;
import cn.petshop.member.mapper.PetMapper;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardLedger;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.mapper.CardLedgerMapper;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import cn.petshop.wallet.service.WalletVO;
import cn.petshop.wallet.service.BalanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台会员视图：检索、详情、调次、健康到期看板。
 *
 * <h3>为什么这个服务在 petshop-admin 而不是 petshop-member</h3>
 * 它要同时读会员、钱包（余额 / 次卡）与健康档案，而 petshop-member 不依赖 petshop-wallet。
 * petshop-admin 依赖全部业务模块，是唯一能同时看到这三块的地方 ——
 * 强行拆到各域再去聚合，只会多出一层没有业务含义的转发。
 *
 * <h3>健康看板为什么不做微信催办</h3>
 * 微信订阅消息是<b>一次性授权 + 一模板一授权</b>：现有的
 * {@code WxSubscribeService} 绑的是"退款申请提醒"模板与店长的 openid 配额，
 * 与"宠物疫苗到期提醒（发给顾客）"是两个模板、两套授权、两类接收人。
 * 在没有申请到对应模板 ID 之前做一个"点了说已发送、其实什么都没发"的按钮，
 * 比没有这个按钮更糟 —— 店员会以为顾客已经收到通知，从而不再打电话。
 * 所以本期看板只做<b>只读到期清单 + 会员联系方式</b>，让店员照着打电话；
 * 接入推送需要先把模板申请下来，届时在这里加一个发送方法即可。
 */
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    /** 看板默认提前多少天提醒（与 HealthLogType.DUE_SOON_DAYS 同口径，但可按门店需要放大） */
    private static final int DEFAULT_WINDOW_DAYS = 30;
    /** 一次性读入的健康流水上限，防止把整表拖进内存 */
    private static final int HEALTH_SCAN_LIMIT = 5000;

    private final MemberMapper memberMapper;
    private final MemberLevelMapper levelMapper;
    private final PetMapper petMapper;
    private final PetHealthLogMapper healthLogMapper;
    private final CardMapper cardMapper;
    private final CardTypeMapper cardTypeMapper;
    private final CardLedgerMapper cardLedgerMapper;
    private final BalanceService balanceService;
    private final ConfigAuditService auditService;

    // ================================================================ 检索

    /**
     * 会员检索：手机号 / 昵称 / 宠物名。
     *
     * <p>宠物名要先反查 member_id 再并入条件：柜台最常听顾客说"我家团子"，而不是报会员号。
     * 这里刻意不做"宠物名模糊命中就返回该宠物"的细分 —— 一个会员可能有多只同名宠物，
     * 缩小到会员粒度再由详情页展开更不容易认错人。</p>
     */
    public IPage<AdminMemberVO> search(Long storeId, String keyword, long page, long size) {
        LambdaQueryWrapper<Member> q = new LambdaQueryWrapper<Member>()
                .eq(Member::getStoreId, storeId)
                .orderByDesc(Member::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            List<Long> byPet = petMapper.selectList(new LambdaQueryWrapper<Pet>()
                            .eq(Pet::getStoreId, storeId)
                            .like(Pet::getName, kw))
                    .stream().map(Pet::getMemberId).distinct().toList();
            q.and(w -> {
                w.like(Member::getPhone, kw).or().like(Member::getNickname, kw);
                if (!byPet.isEmpty()) {
                    w.or().in(Member::getId, byPet);
                }
            });
        }
        Page<Member> p = memberMapper.selectPage(new Page<>(page, capSize(size)), q);
        return p.convert(this::toRow);
    }

    /** 列表用的轻量视图：不算余额与卡，避免一页 20 条打出上百次查询。 */
    private AdminMemberVO toRow(Member m) {
        AdminMemberVO vo = new AdminMemberVO();
        vo.setId(m.getId());
        vo.setNickname(m.getNickname());
        vo.setPhone(m.getPhone());
        vo.setLevel(m.getLevel());
        vo.setLevelName(levelName(1L, m.getLevel()));
        vo.setTotalPaid(m.getTotalPaid());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }

    // ================================================================ 详情

    public AdminMemberVO detail(Long memberId) {
        Member m = requireMember(memberId);
        AdminMemberVO vo = new AdminMemberVO();
        vo.setId(m.getId());
        vo.setNickname(m.getNickname());
        vo.setPhone(m.getPhone());
        vo.setLevel(m.getLevel());
        vo.setLevelName(levelName(m.getStoreId(), m.getLevel()));
        vo.setTotalPaid(m.getTotalPaid());
        vo.setCreatedAt(m.getCreatedAt());

        WalletVO w = balanceService.wallet(memberId);
        AdminMemberVO.Wallet wallet = new AdminMemberVO.Wallet();
        wallet.setBalance(w.getBalance());
        wallet.setFrozenAmount(w.getFrozenAmount());
        wallet.setAvailable(w.getAvailable());
        wallet.setRefundablePrincipal(w.getRefundablePrincipal());
        vo.setWallet(wallet);

        vo.setCards(cardsOf(memberId));
        vo.setPets(petsOf(memberId));
        return vo;
    }

    private List<AdminMemberVO.CardRow> cardsOf(Long memberId) {
        List<Card> cards = cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getMemberId, memberId)
                .orderByDesc(Card::getId));
        if (cards.isEmpty()) {
            return List.of();
        }
        List<Long> typeIds = cards.stream().map(Card::getCardTypeId).distinct().toList();
        Map<Long, String> typeNames = new HashMap<>();
        for (CardType t : cardTypeMapper.selectList(new LambdaQueryWrapper<CardType>().in(CardType::getId, typeIds))) {
            typeNames.put(t.getId(), t.getName());
        }
        return cards.stream().map(c -> {
            AdminMemberVO.CardRow row = new AdminMemberVO.CardRow();
            row.setId(c.getId());
            row.setCardNo(c.getCardNo());
            row.setCardTypeName(typeNames.getOrDefault(c.getCardTypeId(), "卡型#" + c.getCardTypeId()));
            row.setTotalTimes(c.getTotalTimes());
            row.setRemainTimes(c.getRemainTimes());
            row.setPreDeductCount(c.getPreDeductCount());
            row.setValidUntil(c.getValidUntil());
            row.setStatus(c.getStatus());
            row.setPaidAmount(c.getPaidAmount());
            return row;
        }).toList();
    }

    private List<AdminMemberVO.PetRow> petsOf(Long memberId) {
        List<Pet> pets = petMapper.selectList(new LambdaQueryWrapper<Pet>()
                .eq(Pet::getMemberId, memberId)
                .orderByAsc(Pet::getId));
        if (pets.isEmpty()) {
            return List.of();
        }
        Map<Long, PetHealthLog> vaccine = latestLogs(pets.stream().map(Pet::getId).toList(), HealthLogType.VACCINE);
        Map<Long, PetHealthLog> worm = latestLogs(pets.stream().map(Pet::getId).toList(), HealthLogType.WORM);
        LocalDate today = LocalDate.now();
        return pets.stream().map(p -> {
            AdminMemberVO.PetRow row = new AdminMemberVO.PetRow();
            row.setId(p.getId());
            row.setName(p.getName());
            row.setType(p.getType());
            row.setSize(p.getSize());
            row.setHair(p.getHair());
            row.setBirthday(p.getBirthday());
            row.setWeight(p.getWeight());
            row.setNextVaccineDue(dueOf(vaccine.get(p.getId())));
            row.setNextWormDue(dueOf(worm.get(p.getId())));
            row.setVaccineStatus(dueStatus(dueOf(vaccine.get(p.getId())), today));
            row.setWormStatus(dueStatus(dueOf(worm.get(p.getId())), today));
            return row;
        }).toList();
    }

    // ================================================================ 调次

    /**
     * 后台调次（补次 / 扣次）。
     *
     * <p>与库存盘点同一套逻辑：<b>原因必填</b>，且用「读到什么就断言什么」的 CAS
     * 而不是 {@code remain = remain + delta}。调次是人工判断后的一次改写，
     * 如果两个人同时基于同一个旧值提交，简单累加会让其中一次调整凭空消失。</p>
     *
     * <p>补次可能让剩余次数超过原总次数（例如补偿一次）。这时同时把 {@code total_times} 抬上去，
     * 保持 {@code 0 <= remain <= total} 这个不变量 —— 别处（退款判定、卡类型次数约束）
     * 都按 {@code total - remain = 已用次数} 理解，破坏它会算出一个负的已用次数。</p>
     */
    @Transactional
    public Map<String, Object> adjustCardTimes(Long cardId, int delta, String remark, String operator) {
        if (cardId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "次卡 ID 不能为空");
        }
        if (delta == 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "调整次数不能为 0");
        }
        if (remark == null || remark.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请填写调次原因（次卡调整会直接改变顾客权益）");
        }
        Card card = cardMapper.selectById(cardId);
        if (card == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "次卡不存在");
        }
        if (!Card.STATUS_USING.equals(card.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT,
                    "只有使用中的次卡可以调次（当前状态：" + card.getStatus() + "）");
        }
        int beforeRemain = card.getRemainTimes() == null ? 0 : card.getRemainTimes();
        int beforeTotal = card.getTotalTimes() == null ? 0 : card.getTotalTimes();
        int newRemain = beforeRemain + delta;
        if (newRemain < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "扣减后剩余次数会变成负数（当前剩余 " + beforeRemain + " 次，本次 " + delta + "）");
        }
        int newTotal = Math.max(beforeTotal, newRemain);

        if (cardMapper.casAdjustTimes(cardId, beforeRemain, beforeTotal, newRemain, newTotal) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT,
                    "这张卡刚刚被其他操作改动过（或已核销 / 已退款），请刷新后重试");
        }

        CardLedger ledger = CardLedger.of(cardId, CardBizType.ADJUST, delta, null, operator);
        ledger.setRemark(remark.trim());
        cardLedgerMapper.insert(ledger);

        auditService.single(ConfigAuditLog.TARGET_CARD, cardId, ConfigAuditLog.ACTION_ADJUST,
                "次卡剩余次数", String.valueOf(beforeRemain), String.valueOf(newRemain), remark, operator);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardId", cardId);
        out.put("remainBefore", beforeRemain);
        out.put("remainAfter", newRemain);
        out.put("totalAfter", newTotal);
        out.put("delta", delta);
        return out;
    }

    /** 某张卡的流水（购买 / 核销 / 预扣 / 退款 / 手工调次）。 */
    public List<CardLedger> cardLedgers(Long cardId, long limit) {
        return new ArrayList<>(cardLedgerMapper.selectList(new LambdaQueryWrapper<CardLedger>()
                .eq(CardLedger::getCardId, cardId)
                .orderByDesc(CardLedger::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200))));
    }

    // ============================================================ 健康看板

    /**
     * 到期看板：把所有宠物最近一次疫苗 / 驱虫的下次应做日期与今天比较，
     * 取出「已逾期」与「窗口期内即将到期」的，按到期日升序（最急的在最前）。
     *
     * @param windowDays 提前提醒天数，默认 30；传 0 表示只看已逾期
     * @param onlyOverdue 只看已逾期
     */
    public List<AdminMemberVO.HealthDue> healthBoard(Long storeId, Integer windowDays, boolean onlyOverdue) {
        int window = windowDays == null ? DEFAULT_WINDOW_DAYS : Math.max(0, Math.min(windowDays, 365));
        LocalDate today = LocalDate.now();

        // 一次把两类流水读进来，在内存里按 (petId, type) 取最新 ——
        // 逐只宠物查两次会变成 2N 次查询，看板上百只宠物就直接打满连接池
        List<PetHealthLog> logs = healthLogMapper.selectList(new LambdaQueryWrapper<PetHealthLog>()
                .eq(PetHealthLog::getStoreId, storeId)
                .in(PetHealthLog::getLogType, List.of(HealthLogType.VACCINE, HealthLogType.WORM))
                .isNotNull(PetHealthLog::getNextDue)
                .orderByDesc(PetHealthLog::getLogDate)
                .orderByDesc(PetHealthLog::getId)
                .last("LIMIT " + HEALTH_SCAN_LIMIT));
        if (logs.isEmpty()) {
            return List.of();
        }

        Map<String, PetHealthLog> latest = new LinkedHashMap<>();
        for (PetHealthLog log : logs) {
            // 已按日期倒序，第一个遇到的就是最新的
            latest.putIfAbsent(log.getPetId() + "|" + log.getLogType(), log);
        }

        List<Long> petIds = latest.values().stream().map(PetHealthLog::getPetId).distinct().toList();
        Map<Long, Pet> petById = new HashMap<>();
        for (Pet pet : petMapper.selectBatchIds(petIds)) {
            petById.put(pet.getId(), pet);
        }
        Map<Long, Member> memberById = new HashMap<>();
        List<Long> memberIds = petById.values().stream().map(Pet::getMemberId).distinct().toList();
        if (!memberIds.isEmpty()) {
            for (Member m : memberMapper.selectBatchIds(memberIds)) {
                memberById.put(m.getId(), m);
            }
        }

        List<AdminMemberVO.HealthDue> out = new ArrayList<>();
        for (PetHealthLog log : latest.values()) {
            Pet pet = petById.get(log.getPetId());
            if (pet == null) {
                // 宠物已被逻辑删除：到期提醒没有意义（人已经不来了），跳过而不是显示一行没有名字的记录
                continue;
            }
            LocalDate due = log.getNextDue();
            long daysLeft = ChronoUnit.DAYS.between(today, due);
            boolean overdue = daysLeft < 0;
            if (onlyOverdue && !overdue) {
                continue;
            }
            if (!overdue && daysLeft > window) {
                continue;
            }
            Member m = memberById.get(pet.getMemberId());
            AdminMemberVO.HealthDue row = new AdminMemberVO.HealthDue();
            row.setPetId(pet.getId());
            row.setPetName(pet.getName());
            row.setPetType(pet.getType());
            row.setMemberId(pet.getMemberId());
            row.setMemberName(m == null ? null : m.getNickname());
            row.setMemberPhone(m == null ? null : m.getPhone());
            row.setLogType(log.getLogType().name());
            row.setLogTypeLabel(vaccineOrWorm(log.getLogType()));
            row.setLastDate(log.getLogDate());
            row.setNextDue(due);
            row.setDaysLeft((int) daysLeft);
            row.setStatus(overdue ? "OVERDUE" : "DUE_SOON");
            out.add(row);
        }
        // 逾期最久的排最前：店员从上往下打，不会漏掉最该联系的那只
        out.sort(Comparator.comparing(AdminMemberVO.HealthDue::getNextDue));
        return out;
    }

    // ================================================================ 内部

    /** 批量取某类健康流水的"每只宠物最新一条"。 */
    private Map<Long, PetHealthLog> latestLogs(List<Long> petIds, HealthLogType type) {
        if (petIds.isEmpty()) {
            return Map.of();
        }
        List<PetHealthLog> logs = healthLogMapper.selectList(new LambdaQueryWrapper<PetHealthLog>()
                .in(PetHealthLog::getPetId, petIds)
                .eq(PetHealthLog::getLogType, type)
                .orderByDesc(PetHealthLog::getLogDate)
                .orderByDesc(PetHealthLog::getId));
        Map<Long, PetHealthLog> out = new HashMap<>();
        for (PetHealthLog log : logs) {
            out.putIfAbsent(log.getPetId(), log);
        }
        return out;
    }

    private LocalDate dueOf(PetHealthLog log) {
        return log == null ? null : log.getNextDue();
    }

    private String dueStatus(LocalDate due, LocalDate today) {
        if (due == null) {
            return "UNKNOWN";
        }
        if (due.isBefore(today)) {
            return "OVERDUE";
        }
        return ChronoUnit.DAYS.between(today, due) <= HealthLogType.DUE_SOON_DAYS ? "DUE_SOON" : "SCHEDULED";
    }

    private String vaccineOrWorm(HealthLogType type) {
        return type == HealthLogType.VACCINE ? "疫苗" : "驱虫";
    }

    private Member requireMember(Long memberId) {
        if (memberId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "会员 ID 不能为空");
        }
        Member m = memberMapper.selectById(memberId);
        if (m == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "会员不存在");
        }
        return m;
    }

    private String levelName(Long storeId, Integer level) {
        if (level == null) {
            return null;
        }
        MemberLevel cfg = levelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStoreId, storeId)
                .eq(MemberLevel::getLevel, level));
        // 等级配置被删掉时回落成 "L2"：让"会员指向了一个不存在的等级"这件事在界面上暴露，
        // 而不是显示成空白让人以为界面坏了
        return cfg == null ? "L" + level : cfg.getName();
    }

    private long capSize(long size) {
        return size <= 0 ? 20 : Math.min(size, 100);
    }
}
