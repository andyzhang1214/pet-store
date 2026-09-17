package cn.petshop.member.service;

import cn.petshop.common.context.StoreContext;

import cn.petshop.common.enums.HealthLogType;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.PetHealthLog;
import cn.petshop.member.mapper.PetHealthLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HealthArchiveService {

    public static final String DUE_OK = "SCHEDULED";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String DUE_OVERDUE = "OVERDUE";

    private final PetHealthLogMapper logMapper;
    private final PetService petService;

    @Data
    public static class AppendCmd {
        private Long petId;
        private HealthLogType logType;
        private LocalDate logDate;
        private LocalDate nextDue;
        private BigDecimal weightValue;
        private String source;
        private String bizNo;
        private String operator;
        private String remark;
    }

    /**
     * 会员提交入口。原实现仅在 source == STAFF 时才校验归属，逻辑反了——任何会员都能给
     * 他人宠物写健康流水（IDOR）。现改为：此入口一律校验宠物归属（对应审查 NIT）。
     * 店员代录应走独立的内部入口并携带操作人身份，而不是放宽此处的归属校验。
     */
    @Transactional
    public void append(Long memberId, AppendCmd cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "健康流水参数缺失");
        }
        petService.getOwnedPet(memberId, cmd.getPetId());
        appendInternal(cmd);
    }

    public void appendServiceLog(Long petId, HealthLogType type, LocalDate logDate, LocalDate nextDue,
                                 BigDecimal weightValue, String bizNo, String operator) {
        AppendCmd cmd = new AppendCmd();
        cmd.setPetId(petId);
        cmd.setLogType(type);
        cmd.setLogDate(logDate);
        cmd.setNextDue(nextDue);
        cmd.setWeightValue(weightValue);
        cmd.setSource(PetHealthLog.SOURCE_SERVICE);
        cmd.setBizNo(bizNo);
        cmd.setOperator(operator);
        appendInternal(cmd);
    }

    private void appendInternal(AppendCmd cmd) {
        if (cmd.getPetId() == null || cmd.getLogType() == null || cmd.getLogDate() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "健康流水必填项缺失");
        }
        // 幂等兜底（对应审查 P3-3）：带来源单号（结算单号）的写入只允许落一条。
        // 结算重试 / 消息重放会在同一张单上重复触发写入，多写一条就会让"下次到期"与趋势失真。
        // 快路径先查一次；并发下仍可能两个事务同时通过检查，由 uk_health_biz 唯一键兜底，
        // 撞键时视为"已经写过了"而不是 500。
        String bizNo = cmd.getBizNo() == null || cmd.getBizNo().isBlank() ? null : cmd.getBizNo().trim();
        if (bizNo != null && logMapper.selectCount(new LambdaQueryWrapper<PetHealthLog>()
                .eq(PetHealthLog::getPetId, cmd.getPetId())
                .eq(PetHealthLog::getLogType, cmd.getLogType())
                .eq(PetHealthLog::getBizNo, bizNo)) > 0) {
            return;
        }
        PetHealthLog log = new PetHealthLog();
        log.setStoreId(StoreContext.DEFAULT_STORE_ID);
        log.setPetId(cmd.getPetId());
        log.setLogType(cmd.getLogType());
        log.setLogDate(cmd.getLogDate());
        log.setNextDue(cmd.getNextDue());
        log.setWeightValue(cmd.getWeightValue());
        log.setSource(cmd.getSource());
        log.setBizNo(bizNo);
        log.setOperator(cmd.getOperator());
        log.setRemark(cmd.getRemark());
        log.setCreatedAt(java.time.LocalDateTime.now());
        try {
            logMapper.insert(log);
        } catch (DuplicateKeyException e) {
            // 并发写同一条（唯一键 uk_health_biz）：另一端已经写好了，本次静默跳过。
            // 这里能安全吞掉异常是因为目标库是 MySQL / H2 —— 唯一键冲突不会让整个事务失效，
            // 后续语句照常执行；若将来换到会中止事务的库（如 PostgreSQL），这里要改成 REQUIRES_NEW。
            if (bizNo == null) {
                throw e;
            }
        }
    }

    public boolean isVaccineOutdated(Long petId) {
        PetHealthLog latest = logMapper.selectLatest(petId, HealthLogType.VACCINE.name());
        return latest == null || latest.getNextDue() == null || latest.getNextDue().isBefore(LocalDate.now());
    }

    @Data
    public static class ArchiveVO {
        private DueItem vaccine;
        private DueItem worm;
        private List<BigDecimal> weightTrend;
        private CareItem lastBath;
        private CareItem lastGroom;
        private List<PetHealthLog> recentLogs;
    }

    @Data
    public static class DueItem {
        private LocalDate lastDate;
        private LocalDate nextDue;
        private String status;
        private long daysLeft;
    }

    @Data
    public static class CareItem {
        private LocalDate lastDate;
        private long daysAgo;
    }

    public ArchiveVO archive(Long memberId, Long petId) {
        petService.getOwnedPet(memberId, petId);
        return buildArchive(petId);
    }

    public ArchiveVO buildArchive(Long petId) {
        ArchiveVO vo = new ArchiveVO();
        vo.setVaccine(dueItem(petId, HealthLogType.VACCINE));
        vo.setWorm(dueItem(petId, HealthLogType.WORM));
        vo.setWeightTrend(logMapper.selectList(new LambdaQueryWrapper<PetHealthLog>()
                        .eq(PetHealthLog::getPetId, petId)
                        .eq(PetHealthLog::getLogType, HealthLogType.WEIGHT)
                        .orderByDesc(PetHealthLog::getLogDate)
                        .last("LIMIT 5"))
                .stream().map(PetHealthLog::getWeightValue).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList()));
        vo.setLastBath(careItem(petId, HealthLogType.BATH));
        vo.setLastGroom(careItem(petId, HealthLogType.GROOM));
        vo.setRecentLogs(logMapper.selectList(new LambdaQueryWrapper<PetHealthLog>()
                .eq(PetHealthLog::getPetId, petId)
                .orderByDesc(PetHealthLog::getLogDate)
                .last("LIMIT 3")));
        return vo;
    }

    private DueItem dueItem(Long petId, HealthLogType type) {
        PetHealthLog latest = logMapper.selectLatest(petId, type.name());
        if (latest == null) {
            return null;
        }
        DueItem item = new DueItem();
        item.setLastDate(latest.getLogDate());
        item.setNextDue(latest.getNextDue());
        if (latest.getNextDue() == null) {
            item.setStatus(DUE_OK);
            return item;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), latest.getNextDue());
        item.setDaysLeft(days);
        if (days < 0) {
            item.setStatus(DUE_OVERDUE);
        } else if (days <= HealthLogType.DUE_SOON_DAYS) {
            item.setStatus(DUE_SOON);
        } else {
            item.setStatus(DUE_OK);
        }
        return item;
    }

    private CareItem careItem(Long petId, HealthLogType type) {
        PetHealthLog latest = logMapper.selectLatest(petId, type.name());
        if (latest == null) {
            return null;
        }
        CareItem item = new CareItem();
        item.setLastDate(latest.getLogDate());
        item.setDaysAgo(ChronoUnit.DAYS.between(latest.getLogDate(), LocalDate.now()));
        return item;
    }
}
