package cn.petshop.member.service;

import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.MemberLevel;
import cn.petshop.member.mapper.MemberLevelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class MemberLevelService {

    private final MemberLevelMapper levelMapper;

    /**
     * 等级权益关闭（enabled=0）或未配置折扣时返回 null，折扣引擎跳过。
     */
    public BigDecimal discountFor(Long storeId, Integer level) {
        if (level == null) {
            return null;
        }
        MemberLevel cfg = levelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStoreId, storeId)
                .eq(MemberLevel::getLevel, level));
        if (cfg == null || cfg.getEnabled() == null || cfg.getEnabled() != 1) {
            return null;
        }
        return cfg.getGeneralDiscount();
    }

    public BigDecimal discountForMember(Long storeId, Member member) {
        return member == null ? null : discountFor(storeId, member.getLevel());
    }
}
