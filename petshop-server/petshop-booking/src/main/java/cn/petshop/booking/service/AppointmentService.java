package cn.petshop.booking.service;

import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.entity.ServiceItem;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.entity.TimeSlot;
import cn.petshop.booking.mapper.AppointmentMapper;
import cn.petshop.booking.mapper.ServiceMapper;
import cn.petshop.common.enums.ApptStatus;
import cn.petshop.common.enums.GuaranteeType;
import cn.petshop.common.enums.ServiceCategory;
import cn.petshop.common.util.NoUtil;
import cn.petshop.common.util.VerifyCodeUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.HealthArchiveService;
import cn.petshop.member.service.PetService;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentMapper apptMapper;
    private final ServiceMapper serviceMapper;
    private final cn.petshop.booking.mapper.TimeSlotMapper timeSlotMapper;
    private final SlotService slotService;
    private final PriceMatchService priceMatchService;
    private final PetService petService;
    private final BalanceService balanceService;
    private final CardService cardService;
    private final HealthArchiveService healthArchiveService;

    @Data
    public static class CreateCmd {
        private Long serviceId;
        private Long petId;
        private LocalDate slotDate;
        private LocalTime startTime;
        private String guaranteeType;
        private Long cardId;
        private String remark;
    }

    @Data
    public static class ApptVO {
        private Long id;
        private String apptNo;
        private Long serviceId;
        private String serviceName;
        private Long petId;
        private String petName;
        private LocalDate slotDate;
        private LocalTime startTime;
        private BigDecimal price;
        private String guaranteeType;
        private BigDecimal guaranteeAmount;
        private String status;
        private String verifyCode;
        private LocalDateTime cancelDeadline;
        private Integer vaccinePending;
    }

    @Transactional
    public ApptVO create(Long memberId, CreateCmd cmd) {
        ServiceItem svc = serviceMapper.selectById(cmd.getServiceId());
        if (svc == null || !"ENABLED".equals(svc.getStatus())) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "服务不存在或已停用");
        }
        Pet pet = petService.getOwnedPet(memberId, cmd.getPetId());
        StoreConfig cfg = slotService.storeConfig(svc.getStoreId());

        LocalDate date = cmd.getSlotDate();
        if (date.isBefore(LocalDate.now())
                || date.isAfter(LocalDate.now().plusDays(cfg.getBookableDays()))) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "预约日期超出可约范围");
        }
        LocalTime startTime = cmd.getStartTime();
        if (startTime == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "预约时段不能为空");
        }
        // 时段必须落在门店网格上（与 availableSlots 用同一个 gridStarts 数据源）。
        // 否则 lockOrCreate 会懒创建一个"孤儿时段"：列表里看不见（格子只渲染网格点）
        // 却真占容量，把时段排期搅乱（对应审查 P3-6）。
        if (!slotService.gridStarts(cfg.getBusinessHours(), svc.getDurationSlots()).contains(startTime)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "预约时段不在可约时间内");
        }
        // 已过去的时段：前端只把它标成 past，后端必须同等拒绝，不能只靠界面自觉。
        if (LocalDateTime.of(date, startTime).isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "预约时段已过，请选择之后的时段");
        }

        PriceMatchService.MatchedPrice matched = priceMatchService.match(svc.getId(), pet);

        TimeSlot slot = slotService.lockOrCreate(svc, date, startTime);
        if (slot.getBookedCount() >= slot.getCapacity()) {
            throw new BizException(ErrorCode.BIZ_SLOT_FULL);
        }
        if (apptMapper.countActiveBySlotAndPet(slot.getId(), pet.getId()) > 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "同一宠物在该时段已有预约");
        }

        GuaranteeType guarantee;
        try {
            guarantee = GuaranteeType.valueOf(cmd.getGuaranteeType());
        } catch (IllegalArgumentException | NullPointerException e) {
            // 非法枚举值应返回 400 参数错误，而不是被全局兜底成 500（对应审查 MINOR-3）
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "担保方式不合法");
        }
        if (guarantee == GuaranteeType.NONE && !Boolean.TRUE.equals(cfg.getNoGuaranteeEnabled())) {
            throw new BizException(ErrorCode.BIZ_NO_GUARANTEE_DISABLED);
        }
        String apptNo = NoUtil.apptNo();
        if (guarantee == GuaranteeType.BALANCE) {
            balanceService.freeze(memberId, matched.getPrice(), apptNo);
        } else if (guarantee == GuaranteeType.CARD) {
            if (cmd.getCardId() == null) {
                throw new BizException(ErrorCode.BIZ_CARD_GUARANTEE_REQUIRED);
            }
            cardService.preDeduct(cmd.getCardId(), memberId, apptNo);
        }

        Appointment appt = new Appointment();
        appt.setApptNo(apptNo);
        appt.setStoreId(svc.getStoreId());
        appt.setMemberId(memberId);
        appt.setPetId(pet.getId());
        appt.setServiceId(svc.getId());
        appt.setSpecId(matched.getSpecId());
        appt.setSlotId(slot.getId());
        appt.setPrice(matched.getPrice());
        appt.setGuaranteeType(guarantee.name());
        appt.setGuaranteeAmount(guarantee == GuaranteeType.NONE ? BigDecimal.ZERO : matched.getPrice());
        appt.setCardId(guarantee == GuaranteeType.CARD ? cmd.getCardId() : null);
        appt.setStatus(ApptStatus.PENDING.name());
        appt.setVaccinePending(
                ServiceCategory.BOARD.name().equals(svc.getCategory())
                        && healthArchiveService.isVaccineOutdated(pet.getId()) ? 1 : 0);
        appt.setRemark(cmd.getRemark());
        appt.setCancelDeadline(LocalDateTime.of(date, cmd.getStartTime())
                .minusHours(cfg.getCancelHours()));
        appt.setCreatedAt(LocalDateTime.now());
        appt.setUpdatedAt(LocalDateTime.now());
        insertWithUniqueVerifyCode(appt);

        slotMapperIncr(slot.getId());
        slotService.invalidate(date, svc.getId());
        return toVO(appt, svc, pet);
    }

    @Transactional
    public void cancel(Long memberId, Long apptId) {
        Appointment appt = apptMapper.selectById(apptId);
        if (appt == null || !appt.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "预约不存在");
        }
        if (!ApptStatus.PENDING.name().equals(appt.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "当前状态不可取消");
        }
        if (LocalDateTime.now().isAfter(appt.getCancelDeadline())) {
            throw new BizException(ErrorCode.BIZ_APPT_NOT_CANCELLABLE);
        }
        // 先 CAS 抢占 PENDING → CANCELLED，再做副作用（对应审查 P1-4）。
        // 之前是「先读状态 → 副作用 → 最后 updateById 改状态」：BALANCE/CARD 担保靠下游
        // unfreeze/release 的条件更新碰巧安全，但 NONE 担保下 releaseGuarantee 是空操作，
        // 并发双击会重复 slotMapperDecr → booked_count 偏小 → 时段超容。
        // CAS 抢占后，后到的事务拿到 0 行直接抛错回滚，decr 不可能执行两次。
        if (apptMapper.casStatus(apptId, ApptStatus.PENDING.name(), ApptStatus.CANCELLED.name()) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "取消失败，预约状态刚被其他人变更，请刷新");
        }
        appt.setStatus(ApptStatus.CANCELLED.name());
        releaseGuarantee(appt, appt.getApptNo() + "-C");
        slotMapperDecr(appt.getSlotId());
        TimeSlot slot = slotRef(appt.getSlotId());
        slotService.invalidate(slot.getSlotDate(), appt.getServiceId());
    }

    @Transactional
    public void adminNoShow(Long apptId) {
        Appointment appt = apptMapper.selectById(apptId);
        if (appt == null || !ApptStatus.PENDING.name().equals(appt.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "仅待到店预约可记爽约");
        }
        StoreConfig cfg = slotService.storeConfig(appt.getStoreId());
        String policy = cfg == null ? StoreConfig.NO_SHOW_RECORD : cfg.getNoShowPolicy();
        String bizNo = appt.getApptNo() + "-NS";
        // 先 CAS 抢占 PENDING → NO_SHOW 再做担保处置（对应审查 P1-4，理由同 cancel）：
        // NONE 担保 + RECORD 政策下没有任何条件更新会失败，非 CAS 写法会被并发双击重复 decr。
        if (apptMapper.casStatus(apptId, ApptStatus.PENDING.name(), ApptStatus.NO_SHOW.name()) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "记爽约失败，预约状态刚被其他人变更，请刷新");
        }
        appt.setStatus(ApptStatus.NO_SHOW.name());
        if (GuaranteeType.BALANCE.name().equals(appt.getGuaranteeType())) {
            BigDecimal frozen = appt.getGuaranteeAmount();
            switch (policy) {
                case StoreConfig.NO_SHOW_DEDUCT_ALL -> balanceService.consumeFrozen(appt.getMemberId(), frozen, frozen, bizNo);
                case StoreConfig.NO_SHOW_DEDUCT_HALF -> balanceService.consumeFrozen(
                        appt.getMemberId(), frozen, frozen.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP), bizNo);
                default -> balanceService.unfreeze(appt.getMemberId(), frozen, bizNo);
            }
        } else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())
                && StoreConfig.NO_SHOW_CARD_CONFIRM.equals(policy)) {
            cardService.confirm(appt.getCardId(), bizNo);
        } else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())) {
            cardService.release(appt.getCardId(), bizNo);
        }
        slotMapperDecr(appt.getSlotId());
        TimeSlot slot = slotRef(appt.getSlotId());
        slotService.invalidate(slot.getSlotDate(), appt.getServiceId());
    }

    @Transactional
    public Appointment checkinByVerifyCode(String code) {
        if (!VerifyCodeUtil.validate(code) || apptMapper.casArrivedByVerifyCode(code) == 0) {
            throw new BizException(ErrorCode.BIZ_VERIFY_CODE_INVALID);
        }
        Appointment appt = apptMapper.selectByVerifyCode(code);
        TimeSlot slot = slotRef(appt.getSlotId());
        slotService.invalidate(slot.getSlotDate(), appt.getServiceId());
        return appt;
    }

    /**
     * 顾客在小程序端自助确认到店（预约当天可点，见 AppointmentMapper.casArrivedByIdAndMember）。
     * 与店铺扫码核销殊途同归：都只做 PENDING → ARRIVED，担保照常冻结，结算仍只能由后台发起。
     */
    @Transactional
    public void confirmArrival(Long memberId, Long apptId) {
        Appointment appt = apptMapper.selectById(apptId);
        if (appt == null || !appt.getMemberId().equals(memberId)) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "预约不存在");
        }
        if (!ApptStatus.PENDING.name().equals(appt.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "当前状态无需确认到店");
        }
        LocalDate slotDate = slotRef(appt.getSlotId()).getSlotDate();
        if (slotDate.isAfter(LocalDate.now())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "预约当天才能自助确认到店");
        }
        if (apptMapper.casArrivedByIdAndMember(apptId, memberId) == 0) {
            // 归属/日期检查都过了还剩 0 行，说明并发下状态刚好被别人改掉
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "确认失败，请刷新后重试");
        }
        slotService.invalidate(slotDate, appt.getServiceId());
    }

    /** 自动确认任务用：取 slot_date 早于 date 的待到店预约 id（分批扫描）。 */
    public List<Long> listPendingIdsBefore(LocalDate date, int limit) {
        return apptMapper.listPendingIdsBeforeDate(date, limit);
    }

    /**
     * 自动确认任务用：把逾期未确认的预约置为 ARRIVED（兜底，防止单据悬着）。
     * 注意取舍：这意味着爽约处理窗口只有预约当天（当天可标记未到 → NO_SHOW），
     * 次日起一律视为已到店，由店铺正常结算。
     */
    @Transactional
    public boolean autoConfirmById(Long id) {
        if (apptMapper.casArrivedById(id) == 0) {
            return false;
        }
        Appointment appt = apptMapper.selectById(id);
        if (appt != null) {
            TimeSlot slot = slotRef(appt.getSlotId());
            slotService.invalidate(slot.getSlotDate(), appt.getServiceId());
        }
        return true;
    }

    public Appointment loadOrThrow(Long apptId) {
        Appointment appt = apptMapper.selectById(apptId);
        if (appt == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "预约不存在");
        }
        return appt;
    }

    /** 状态机 CAS 抢占：仅当当前状态为 from 时迁移到 to，返回受影响行数（0 表示已被并发方抢走）。 */
    public int casStatus(Long apptId, ApptStatus from, ApptStatus to) {
        return apptMapper.casStatus(apptId, from.name(), to.name());
    }

    public List<Appointment> listMine(Long memberId, String status) {
        LambdaQueryWrapper<Appointment> q = new LambdaQueryWrapper<Appointment>()
                .eq(Appointment::getMemberId, memberId)
                .orderByDesc(Appointment::getId);
        if (status != null && !status.isBlank()) {
            q.eq(Appointment::getStatus, status);
        }
        return apptMapper.selectList(q);
    }

    public List<Appointment> listByDate(Long storeId, LocalDate date, String status) {
        // 先按日期取 slotId 列表，再参数化 IN 查询，避免 inSql 字符串拼接（对应审查 MINOR-2）
        List<Long> slotIds = timeSlotMapper.selectIdsByDate(date);
        if (slotIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Appointment> q = new LambdaQueryWrapper<Appointment>()
                .eq(Appointment::getStoreId, storeId)
                .in(Appointment::getSlotId, slotIds)
                .orderByAsc(Appointment::getId);
        if (status != null && !status.isBlank()) {
            q.eq(Appointment::getStatus, status);
        }
        return apptMapper.selectList(q);
    }

    void releaseGuarantee(Appointment appt, String bizNo) {
        if (GuaranteeType.BALANCE.name().equals(appt.getGuaranteeType())) {
            balanceService.unfreeze(appt.getMemberId(), appt.getGuaranteeAmount(), bizNo);
        } else if (GuaranteeType.CARD.name().equals(appt.getGuaranteeType())) {
            cardService.release(appt.getCardId(), bizNo);
        }
    }

    public void updateStatus(Appointment appt, ApptStatus status) {
        Appointment patch = new Appointment();
        patch.setId(appt.getId());
        patch.setStatus(status.name());
        patch.setUpdatedAt(LocalDateTime.now());
        apptMapper.updateById(patch);
        appt.setStatus(status.name());
    }

    private void insertWithUniqueVerifyCode(Appointment appt) {
        for (int i = 0; i < 5; i++) {
            appt.setVerifyCode(VerifyCodeUtil.generate());
            try {
                apptMapper.insert(appt);
                return;
            } catch (DuplicateKeyException e) {
                // 核销码撞唯一索引，重试
            }
        }
        throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "核销码生成失败，请重试");
    }

    private void slotMapperIncr(Long slotId) {
        timeSlotMapper.incrBooked(slotId);
    }

    private void slotMapperDecr(Long slotId) {
        timeSlotMapper.decrBooked(slotId);
    }

    private TimeSlot slotRef(Long slotId) {
        return timeSlotMapper.selectById(slotId);
    }

    private ApptVO toVO(Appointment appt, ServiceItem svc, Pet pet) {
        return buildVO(appt, svc, pet == null ? null : pet.getName(), slotRef(appt.getSlotId()));
    }

    /**
     * 列表批量组装：按 serviceId / petId / slotId 各预取一次（3 次查询），
     * 替代原先「每条预约 3 次查询」的 N+1 实现（对应审查 M-4）。
     */
    public List<ApptVO> toVOList(List<Appointment> appts) {
        if (appts == null || appts.isEmpty()) {
            return List.of();
        }
        Set<Long> serviceIds = appts.stream().map(Appointment::getServiceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> petIds = appts.stream().map(Appointment::getPetId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> slotIds = appts.stream().map(Appointment::getSlotId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, ServiceItem> svcMap = serviceIds.isEmpty() ? Map.of()
                : serviceMapper.selectBatchIds(serviceIds).stream()
                        .collect(Collectors.toMap(ServiceItem::getId, s -> s, (a, b) -> a));
        Map<Long, String> petNames = petService.namesIgnoreDeleted(petIds);
        Map<Long, TimeSlot> slotMap = slotIds.isEmpty() ? Map.of()
                : timeSlotMapper.selectBatchIds(slotIds).stream()
                        .collect(Collectors.toMap(TimeSlot::getId, s -> s, (a, b) -> a));

        return appts.stream()
                .map(a -> buildVO(a, svcMap.get(a.getServiceId()), petNames.get(a.getPetId()),
                        slotMap.get(a.getSlotId())))
                .toList();
    }

    /** 单条组装（内部走批量路径，避免两套逻辑分叉）。 */
    public ApptVO toVO(Appointment appt) {
        return toVOList(List.of(appt)).get(0);
    }

    private ApptVO buildVO(Appointment appt, ServiceItem svc, String petName, TimeSlot slot) {
        ApptVO vo = new ApptVO();
        vo.setId(appt.getId());
        vo.setApptNo(appt.getApptNo());
        vo.setServiceId(appt.getServiceId());
        vo.setServiceName(svc == null ? null : svc.getName());
        vo.setPetId(appt.getPetId());
        vo.setPetName(petName);
        if (slot != null) {
            vo.setSlotDate(slot.getSlotDate());
            vo.setStartTime(slot.getStartTime());
        }
        vo.setPrice(appt.getPrice());
        vo.setGuaranteeType(appt.getGuaranteeType());
        vo.setGuaranteeAmount(appt.getGuaranteeAmount());
        vo.setStatus(appt.getStatus());
        vo.setVerifyCode(appt.getVerifyCode());
        vo.setCancelDeadline(appt.getCancelDeadline());
        vo.setVaccinePending(appt.getVaccinePending());
        return vo;
    }
}
