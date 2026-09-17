package cn.petshop;

import cn.petshop.common.enums.LedgerBizType;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.wallet.entity.BalanceLedger;
import cn.petshop.wallet.mapper.BalanceLedgerMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.WalletVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WalletFlowTest {

    @Autowired
    private BalanceService balanceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PetService petService;
    @Autowired
    private BalanceLedgerMapper ledgerMapper;

    private Member newMember() {
        return memberService.findOrCreate("mock-wallet-" + System.nanoTime(), 1L);
    }

    @Test
    void rechargeFreezeConsumeKeepsLedgerInvariant() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100"), new BigDecimal("20"), "PY-TEST-1");

        balanceService.freeze(m.getId(), new BigDecimal("30"), "AP-TEST-1");
        WalletVO mid = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("120").compareTo(mid.getBalance()));
        assertEquals(0, new BigDecimal("30").compareTo(mid.getFrozenAmount()));
        assertEquals(0, new BigDecimal("90").compareTo(mid.getAvailable()));

        balanceService.consumeFrozen(m.getId(), new BigDecimal("30"), new BigDecimal("25"), "ST-TEST-1");
        WalletVO after = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("95").compareTo(after.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getFrozenAmount()));

        List<BalanceLedger> ledgers = ledgerMapper.selectList(new LambdaQueryWrapper<BalanceLedger>()
                .eq(BalanceLedger::getMemberId, m.getId())
                .orderByAsc(BalanceLedger::getId));
        assertEquals(4, ledgers.size());
        BigDecimal ledgerSum = ledgers.stream()
                .map(l -> l.getPrincipalAmt().add(l.getGiftAmt()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, after.getBalance().compareTo(ledgerSum), "余额应等于流水本金+赠送金之和");
        assertEquals(LedgerBizType.RECHARGE, ledgers.get(0).getBizType());
        assertEquals(LedgerBizType.FREEZE, ledgers.get(1).getBizType());
        assertEquals(LedgerBizType.UNFREEZE, ledgers.get(2).getBizType());
        assertEquals(LedgerBizType.CONSUME, ledgers.get(3).getBizType());
        assertEquals(0, new BigDecimal("95").compareTo(ledgers.get(3).getBalanceAfter()));
    }

    @Test
    void freezeInsufficientBalanceFails() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("10"), BigDecimal.ZERO, "PY-TEST-2");
        assertThrows(cn.petshop.common.web.BizException.class,
                () -> balanceService.freeze(m.getId(), new BigDecimal("50"), "AP-TEST-2"));
        WalletVO vo = balanceService.wallet(m.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getFrozenAmount()));
    }

    @Test
    void consumeFrozenMoreThanFrozenTakesFromAvailable() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("200"), BigDecimal.ZERO, "PY-TEST-3");
        balanceService.freeze(m.getId(), new BigDecimal("100"), "AP-TEST-3");
        balanceService.consumeFrozen(m.getId(), new BigDecimal("100"), new BigDecimal("150"), "ST-TEST-3");
        WalletVO vo = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("50").compareTo(vo.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getFrozenAmount()));
    }

    @Test
    void refundablePrincipalFollowsRatio() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100"), new BigDecimal("20"), "PY-TEST-4");
        WalletVO vo = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.getRefundablePrincipal()));
    }

    @Test
    void petCrudRequiresSpecFields() {
        Member m = newMember();
        Pet pet = new Pet();
        pet.setName("雪球");
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(new BigDecimal("4.20"));
        Pet saved = petService.create(m.getId(), pet);
        assertNotNull(saved.getId());
        assertEquals(1, petService.listByMember(m.getId()).size());

        Pet bad = new Pet();
        bad.setName("缺毛长");
        bad.setType("CAT");
        assertThrows(cn.petshop.common.web.BizException.class,
                () -> petService.create(m.getId(), bad));
    }
}
