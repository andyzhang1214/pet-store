package cn.petshop.admin.member;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 后台会员视图。把「会员 + 钱包 + 次卡 + 宠物健康」拼成一处，供柜台在一个页面里答完顾客的问题。 */
@Data
public class AdminMemberVO {

    private Long id;
    private String nickname;
    private String phone;
    private Integer level;
    /** 等级名（由 member_level 解析；等级不存在时回落成 "L{n}"，让脏数据看得见） */
    private String levelName;
    private BigDecimal totalPaid;
    private LocalDateTime createdAt;

    private Wallet wallet;
    private List<CardRow> cards;
    private List<PetRow> pets;

    @Data
    public static class Wallet {
        private BigDecimal balance;
        private BigDecimal frozenAmount;
        private BigDecimal available;
        /** 当前可退本金（= Σ 余额流水里未退回的本金部分） */
        private BigDecimal refundablePrincipal;
    }

    @Data
    public static class CardRow {
        private Long id;
        private String cardNo;
        private String cardTypeName;
        private Integer totalTimes;
        private Integer remainTimes;
        private Integer preDeductCount;
        private LocalDate validUntil;
        private String status;
        private BigDecimal paidAmount;
    }

    @Data
    public static class PetRow {
        private Long id;
        private String name;
        private String type;
        private String size;
        private String hair;
        private LocalDate birthday;
        private BigDecimal weight;
        /** 最近一次疫苗 / 驱虫的下次应做日期 */
        private LocalDate nextVaccineDue;
        private LocalDate nextWormDue;
        /** SCHEDULED / DUE_SOON / OVERDUE / UNKNOWN（从未记录过） */
        private String vaccineStatus;
        private String wormStatus;
    }

    /** 健康看板的一行：某只宠物某一项该做了。 */
    @Data
    public static class HealthDue {
        private Long petId;
        private String petName;
        private String petType;
        private Long memberId;
        private String memberName;
        private String memberPhone;
        /** VACCINE / WORM */
        private String logType;
        private String logTypeLabel;
        private LocalDate lastDate;
        private LocalDate nextDue;
        /** 距到期天数：负数表示已逾期 */
        private Integer daysLeft;
        /** DUE_SOON / OVERDUE */
        private String status;
    }
}
