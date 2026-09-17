package cn.petshop.wallet.controller;

import cn.petshop.common.context.MemberContext;
import cn.petshop.common.web.R;
import cn.petshop.wallet.entity.BalanceLedger;
import cn.petshop.wallet.mapper.BalanceLedgerMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import cn.petshop.wallet.service.MemberCardVO;
import cn.petshop.wallet.service.WalletVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final BalanceService balanceService;
    private final BalanceLedgerMapper ledgerMapper;
    private final CardService cardService;

    @GetMapping
    public R<WalletVO> wallet() {
        return R.ok(balanceService.wallet(MemberContext.require()));
    }

    /**
     * 我的次卡（含已用尽 / 已退款），供钱包页展示与发起退款申请。
     * 可退标识 refundable 由服务端判定，前端只做展示。
     */
    @GetMapping("/cards")
    public R<List<MemberCardVO>> cards() {
        return R.ok(cardService.listForMember(MemberContext.require()));
    }

    private static final long MAX_PAGE_SIZE = 100;

    @GetMapping("/ledger")
    public R<IPage<BalanceLedger>> ledger(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "20") long size) {
        Long memberId = MemberContext.require();
        // 分页参数收敛：page 至少 1，size 上限 100，防止 size=100000 拖垮数据库（对应审查 MINOR-8）
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return R.ok(ledgerMapper.selectPage(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<BalanceLedger>()
                        .eq(BalanceLedger::getMemberId, memberId)
                        .orderByDesc(BalanceLedger::getId)));
    }
}
