package cn.petshop.wallet.service;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UsableCardVO {
    private Long cardId;
    private String cardNo;
    private String name;
    private Integer remainTimes;
    private LocalDate validUntil;
}
