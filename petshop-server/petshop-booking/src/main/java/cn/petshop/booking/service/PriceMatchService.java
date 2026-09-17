package cn.petshop.booking.service;

import cn.petshop.common.context.StoreContext;

import cn.petshop.booking.entity.ServicePrice;
import cn.petshop.booking.entity.Spec;
import cn.petshop.booking.mapper.ServicePriceMapper;
import cn.petshop.booking.mapper.SpecMapper;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Pet;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceMatchService {

    private final SpecMapper specMapper;
    private final ServicePriceMapper priceMapper;

    @Data
    public static class MatchedPrice {
        private Long specId;
        private String specName;
        private BigDecimal price;
    }

    /**
     * 规格档匹配：取与宠物（类型/体型/毛长）约束一致且约束最多的档位。
     */
    public MatchedPrice match(Long serviceId, Pet pet) {
        List<Spec> specs = specMapper.selectList(new LambdaQueryWrapper<Spec>()
                .eq(Spec::getStoreId, StoreContext.DEFAULT_STORE_ID)
                .eq(Spec::getPetType, pet.getType()));
        Spec best = null;
        int bestConstraints = -1;
        for (Spec spec : specs) {
            if (spec.getSize() != null && !spec.getSize().equals(pet.getSize())) {
                continue;
            }
            if (spec.getHair() != null && !spec.getHair().equals(pet.getHair())) {
                continue;
            }
            int constraints = (spec.getSize() != null ? 1 : 0) + (spec.getHair() != null ? 1 : 0);
            if (constraints > bestConstraints) {
                best = spec;
                bestConstraints = constraints;
            }
        }
        if (best == null) {
            throw new BizException(ErrorCode.BIZ_SPEC_NOT_MATCH);
        }
        ServicePrice price = priceMapper.selectOne(new LambdaQueryWrapper<ServicePrice>()
                .eq(ServicePrice::getServiceId, serviceId)
                .eq(ServicePrice::getSpecId, best.getId()));
        if (price == null) {
            throw new BizException(ErrorCode.BIZ_SPEC_NOT_MATCH);
        }
        MatchedPrice mp = new MatchedPrice();
        mp.setSpecId(best.getId());
        mp.setSpecName(best.getName());
        mp.setPrice(price.getPrice());
        return mp;
    }
}
