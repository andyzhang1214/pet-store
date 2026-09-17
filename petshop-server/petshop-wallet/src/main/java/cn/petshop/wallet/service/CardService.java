package cn.petshop.wallet.service;

import cn.petshop.common.enums.CardBizType;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.util.NoUtil;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardLedger;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.mapper.CardLedgerMapper;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CardService {

    private final CardMapper cardMapper;
    private final CardTypeMapper cardTypeMapper;
    private final CardLedgerMapper cardLedgerMapper;

    @Transactional
    public void preDeduct(Long cardId, Long memberId, String bizNo) {
        if (cardMapper.preDeduct(cardId, memberId, LocalDate.now()) == 0) {
            throw new BizException(ErrorCode.BIZ_CARD_NOT_USABLE);
        }
        cardLedgerMapper.insert(CardLedger.of(cardId, CardBizType.PRE_DEDUCT, 1, bizNo, null));
    }

    @Transactional
    public void release(Long cardId, String bizNo) {
        if (cardMapper.release(cardId) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "次卡无预扣可返还");
        }
        cardLedgerMapper.insert(CardLedger.of(cardId, CardBizType.RELEASE, 1, bizNo, null));
    }

    @Transactional
    public void confirm(Long cardId, String bizNo) {
        if (cardMapper.confirm(cardId) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "次卡无预扣可核销");
        }
        Card card = cardMapper.selectById(cardId);
        if (card.getRemainTimes() <= 0) {
            Card update = new Card();
            update.setId(cardId);
            update.setStatus(Card.STATUS_EXHAUSTED);
            cardMapper.updateById(update);
        }
        cardLedgerMapper.insert(CardLedger.of(cardId, CardBizType.CONFIRM, 1, bizNo, null));
    }

    /**
     * 次卡购买。
     *
     * ⚠️ TODO（对应审查 M-8）：本方法只落卡记录，**不做任何收款**，也没有对应的支付单。
     * 目前没有任何 controller 暴露它，属于半成品。在补齐"下单 → 微信支付 → 回调发卡"
     * 的闭环之前，严禁把它直接挂到会员接口上，否则等于免费发卡。
     * 补齐方式建议：复用 PayService（新增 bizType = CARD_PURCHASE），由支付回调触发发卡。
     */
    @Transactional
    public Card purchase(Long memberId, Long cardTypeId, String operator) {
        CardType type = cardTypeMapper.selectById(cardTypeId);
        if (type == null || !"ENABLED".equals(type.getStatus())) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "次卡类型不存在或已停售");
        }
        Card card = new Card();
        card.setCardNo(NoUtil.cardNo());
        card.setStoreId(type.getStoreId());
        card.setMemberId(memberId);
        card.setCardTypeId(cardTypeId);
        card.setTotalTimes(type.getTimes());
        card.setRemainTimes(type.getTimes());
        card.setPreDeductCount(0);
        if ("FIXED".equals(type.getValidMode()) && type.getValidUntil() != null) {
            card.setValidUntil(type.getValidUntil());
        } else {
            card.setValidUntil(LocalDate.now().plusMonths(type.getValidMonths() == null ? 12 : type.getValidMonths()));
        }
        card.setStatus(Card.STATUS_USING);
        // 购买实付落库：退款以此为准，避免卡类型后续改价导致退错金额
        card.setPaidAmount(type.getPrice());
        cardMapper.insert(card);
        cardLedgerMapper.insert(CardLedger.of(card.getId(), CardBizType.PURCHASE, type.getTimes(), card.getCardNo(), operator));
        return card;
    }

    /** 会员名下全部次卡（含已用尽 / 已退款），按 id 倒序。 */
    public List<Card> listByMember(Long memberId) {
        return cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getMemberId, memberId)
                .orderByDesc(Card::getId));
    }

    /** 会员名下次卡（含卡类型名与可退标识），卡类型批量预取避免 N+1。 */
    public List<MemberCardVO> listForMember(Long memberId) {
        List<Card> cards = listByMember(memberId);
        Set<Long> typeIds = cards.stream().map(Card::getCardTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CardType> typeMap = typeIds.isEmpty() ? Map.of()
                : cardTypeMapper.selectBatchIds(typeIds).stream()
                        .collect(Collectors.toMap(CardType::getId, t -> t, (a, b) -> a));
        return cards.stream().map(c -> {
            MemberCardVO vo = new MemberCardVO();
            vo.setId(c.getId());
            vo.setCardNo(c.getCardNo());
            CardType type = typeMap.get(c.getCardTypeId());
            vo.setName(type == null ? "次卡" : type.getName());
            vo.setTotalTimes(c.getTotalTimes());
            vo.setRemainTimes(c.getRemainTimes());
            vo.setPreDeductCount(c.getPreDeductCount());
            vo.setValidUntil(c.getValidUntil());
            vo.setStatus(c.getStatus());
            vo.setPaidAmount(c.getPaidAmount());
            vo.setRefundable(isRefundable(c));
            return vo;
        }).toList();
    }

    public Card getOwnedCard(Long memberId, String cardNo) {
        Card card = cardMapper.selectOne(new LambdaQueryWrapper<Card>()
                .eq(Card::getCardNo, cardNo));
        if (card == null || !card.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "次卡不存在");
        }
        return card;
    }

    /**
     * 是否满足整卡退款条件：<b>一次都没用过</b>（remain = total）且无预扣。
     *
     * 有意<b>不把"未过期"作为条件</b>：顾客没消费的钱应当可退，且退款本身是审核制，
     * 门店若认定"过期作废"，在审核环节驳回即可。把过期卡直接挡在门外，顾客连申请入口都没有，
     * 在单用途预付卡的监管口径下更容易被认定为无效格式条款。
     * （若产品最终决定"过期不退"，这里加回 valid_until 判断即可，前端展示会自动跟随。）
     */
    public boolean isRefundable(Card card) {
        if (card == null || card.getRemainTimes() == null) {
            return false;
        }
        boolean statusOk = Card.STATUS_USING.equals(card.getStatus())
                || Card.STATUS_EXPIRED.equals(card.getStatus());
        return statusOk
                && card.getRemainTimes().equals(card.getTotalTimes())
                && (card.getPreDeductCount() == null || card.getPreDeductCount() == 0);
    }

    /**
     * 次卡退款：置卡为已退款并记卡流水。真正的金额出账由 RefundService 在同一事务内完成，
     * 这里只负责卡状态与卡账本的 CAS —— 拿不到 CAS 说明这张卡已经不可退，直接失败让外层事务回滚。
     */
    @Transactional
    public Card markRefunded(Long memberId, String cardNo, String refundNo, String operator, String reason) {
        Card card = getOwnedCard(memberId, cardNo);
        if (!isRefundable(card)) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该次卡已使用或已失效，不支持线上退款");
        }
        if (cardMapper.refundCascade(card.getId(), memberId) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "次卡状态已变更，请刷新后重试");
        }
        CardLedger ledger = CardLedger.of(card.getId(), CardBizType.REFUND, -card.getRemainTimes(), refundNo, operator);
        ledger.setRemark(reason);
        cardLedgerMapper.insert(ledger);
        card.setStatus(Card.STATUS_REFUNDED);
        card.setRemainTimes(0);
        card.setPreDeductCount(0);
        return card;
    }

    public List<UsableCardVO> usableCards(Long memberId, Long serviceId, Long specId) {
        List<Card> cards = cardMapper.selectList(new LambdaQueryWrapper<Card>()
                .eq(Card::getMemberId, memberId)
                .eq(Card::getStatus, Card.STATUS_USING)
                .ge(Card::getValidUntil, LocalDate.now()));
        // 卡类型批量预取，避免每张卡一次 selectById 的 N+1（与审查 M-4 同类问题）
        Set<Long> typeIds = cards.stream().map(Card::getCardTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CardType> typeMap = typeIds.isEmpty() ? Map.of()
                : cardTypeMapper.selectBatchIds(typeIds).stream()
                        .collect(Collectors.toMap(CardType::getId, t -> t, (a, b) -> a));
        return cards.stream()
                .filter(c -> c.getRemainTimes() - c.getPreDeductCount() >= 1)
                .filter(c -> matchesType(typeMap.get(c.getCardTypeId()), serviceId, specId))
                .map(c -> {
                    UsableCardVO vo = new UsableCardVO();
                    vo.setCardId(c.getId());
                    vo.setCardNo(c.getCardNo());
                    CardType type = typeMap.get(c.getCardTypeId());
                    vo.setName(type == null ? "次卡" : type.getName());
                    vo.setRemainTimes(c.getRemainTimes() - c.getPreDeductCount());
                    vo.setValidUntil(c.getValidUntil());
                    return vo;
                })
                .toList();
    }

    private boolean matchesType(CardType type, Long serviceId, Long specId) {
        if (type == null) {
            return false;
        }
        boolean serviceOk = idListContains(type.getServiceIds(), serviceId);
        boolean specOk = type.getSpecIds() == null || type.getSpecIds().isBlank()
                || idListContains(type.getSpecIds(), specId);
        return serviceOk && specOk;
    }

    private boolean idListContains(String jsonIds, Long id) {
        if (jsonIds == null || jsonIds.isBlank() || jsonIds.equals("[]")) {
            return true;
        }
        String body = jsonIds.replace("[", "").replace("]", "").replace(" ", "");
        if (body.isEmpty()) {
            return true;
        }
        for (String part : body.split(",")) {
            if (part.equals(String.valueOf(id))) {
                return true;
            }
        }
        return false;
    }
}
