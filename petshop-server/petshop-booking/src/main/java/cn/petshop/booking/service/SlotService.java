package cn.petshop.booking.service;

import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.entity.TimeSlot;
import cn.petshop.booking.mapper.StoreConfigMapper;
import cn.petshop.booking.mapper.TimeSlotMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SlotService {

    private final TimeSlotMapper slotMapper;
    private final StoreConfigMapper storeConfigMapper;
    private final cn.petshop.booking.mapper.ServiceMapper serviceMapper;

    private final Cache<String, List<SlotVO>> remainingCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(3))
            .maximumSize(2_000)
            .build();

    @Data
    public static class SlotVO {
        private String startTime;
        private int capacity;
        private int remaining;
        private boolean full;
        private boolean past;
    }

    public StoreConfig storeConfig(Long storeId) {
        return storeConfigMapper.selectByStoreId(storeId);
    }

    /**
     * 懒创建 + 行锁：首个预约时才落时段行，capacity = min(门店时段容量, 服务同时接待量) 固化；
     * 并发插入冲突由唯一键兜底，随后统一走 FOR UPDATE 取锁。
     */
    public TimeSlot lockOrCreate(ServiceItem svc, LocalDate slotDate, LocalTime startTime) {
        StoreConfig cfg = storeConfig(svc.getStoreId());
        int capacity = Math.min(cfg.getSlotCapacity(), svc.getCapacity());
        TimeSlot exists = slotMapper.findByNaturalKey(svc.getStoreId(), svc.getId(), slotDate, startTime);
        if (exists == null) {
            TimeSlot fresh = new TimeSlot();
            fresh.setStoreId(svc.getStoreId());
            fresh.setServiceId(svc.getId());
            fresh.setSlotDate(slotDate);
            fresh.setStartTime(startTime);
            fresh.setCapacity(capacity);
            fresh.setBookedCount(0);
            fresh.setCreatedAt(LocalDateTime.now());
            try {
                slotMapper.insert(fresh);
            } catch (DuplicateKeyException e) {
                // 并发首单：另一事务已创建，直接取锁
            }
        }
        TimeSlot locked = slotMapper.lockByNaturalKey(svc.getStoreId(), svc.getId(), slotDate, startTime);
        if (locked.getCapacity() > capacity) {
            // 容量收敛必须落库，否则本次判断正确但下一个请求仍读到旧容量（对应审查 MINOR-7）
            slotMapper.syncCapacity(locked.getId(), capacity);
            locked.setCapacity(capacity);
        }
        return locked;
    }

    public List<SlotVO> availableSlots(Long storeId, Long serviceId, LocalDate date) {
        String key = date + ":" + serviceId;
        List<SlotVO> cached = remainingCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        ServiceItem svc = serviceRef(serviceId);
        StoreConfig cfg = storeConfig(storeId);
        List<TimeSlot> rows = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getStoreId, storeId)
                .eq(TimeSlot::getServiceId, serviceId)
                .eq(TimeSlot::getSlotDate, date));
        List<SlotVO> result = new ArrayList<>();
        for (LocalTime start : gridStarts(cfg.getBusinessHours(), svc.getDurationSlots())) {
            SlotVO vo = new SlotVO();
            vo.setStartTime(start.toString());
            int capacity = Math.min(cfg.getSlotCapacity(), svc.getCapacity());
            vo.setCapacity(capacity);
            int booked = rows.stream()
                    .filter(r -> r.getStartTime().equals(start))
                    .mapToInt(TimeSlot::getBookedCount)
                    .sum();
            vo.setRemaining(Math.max(0, capacity - booked));
            vo.setFull(vo.getRemaining() <= 0);
            vo.setPast(date.equals(LocalDate.now()) && !start.isAfter(LocalTime.now()));
            result.add(vo);
        }
        remainingCache.put(key, result);
        return result;
    }

    public void invalidate(LocalDate date, Long serviceId) {
        remainingCache.invalidate(date + ":" + serviceId);
    }

    public List<LocalTime> gridStarts(String businessHours, int durationSlots) {
        String[] parts = businessHours.split("-");
        LocalTime open = LocalTime.parse(parts[0].trim());
        LocalTime close = LocalTime.parse(parts[1].trim());
        int stepMinutes = Math.max(1, durationSlots) * 60;
        List<LocalTime> starts = new ArrayList<>();
        LocalTime cursor = open;
        while (!cursor.plusMinutes(stepMinutes).isAfter(close)) {
            starts.add(cursor);
            cursor = cursor.plusMinutes(stepMinutes);
        }
        return starts;
    }

    private ServiceItem serviceRef(Long serviceId) {
        ServiceItem svc = serviceMapper.selectById(serviceId);
        if (svc == null) {
            throw new cn.petshop.common.web.BizException(cn.petshop.common.web.ErrorCode.BIZ_NOT_FOUND, "服务不存在");
        }
        return svc;
    }
}
