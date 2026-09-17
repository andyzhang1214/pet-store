package cn.petshop.booking.controller;

import cn.petshop.common.context.StoreContext;

import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.mapper.ServiceMapper;
import cn.petshop.booking.service.PriceMatchService;
import cn.petshop.booking.service.SlotService;
import cn.petshop.common.web.R;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.PetService;
import cn.petshop.wallet.service.UsableCardVO;
import cn.petshop.wallet.service.CardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingController {

    private final ServiceMapper serviceMapper;
    private final SlotService slotService;
    private final PriceMatchService priceMatchService;
    private final PetService petService;
    private final CardService cardService;

    @GetMapping("/services")
    public R<List<ServiceItem>> services(@RequestParam(required = false) String petType) {
        LambdaQueryWrapper<ServiceItem> q = new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getStatus, "ENABLED")
                .orderByAsc(ServiceItem::getId);
        return R.ok(serviceMapper.selectList(q));
    }

    @GetMapping("/services/{id}/quote")
    public R<Map<String, Object>> quote(@org.springframework.web.bind.annotation.PathVariable Long id,
                                        @RequestParam Long petId) {
        Long memberId = cn.petshop.common.context.MemberContext.require();
        Pet pet = petService.getOwnedPet(memberId, petId);
        PriceMatchService.MatchedPrice matched = priceMatchService.match(id, pet);
        List<UsableCardVO> cards = cardService.usableCards(memberId, id, matched.getSpecId());
        return R.ok(Map.of(
                "specId", matched.getSpecId(),
                "specName", matched.getSpecName(),
                "price", matched.getPrice(),
                "usableCards", cards
        ));
    }

    @GetMapping("/slots")
    public R<List<SlotService.SlotVO>> slots(@RequestParam String date,
                                             @RequestParam Long serviceId) {
        cn.petshop.common.context.MemberContext.require();
        return R.ok(slotService.availableSlots(StoreContext.DEFAULT_STORE_ID, serviceId, java.time.LocalDate.parse(date)));
    }
}
