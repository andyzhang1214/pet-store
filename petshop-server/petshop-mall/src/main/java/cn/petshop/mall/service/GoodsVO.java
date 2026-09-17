package cn.petshop.mall.service;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 商品展示对象（含 SKU 列表）。价格一律取 SKU 现价，{@code priceFrom} 仅用于列表页"¥xx 起"展示。 */
@Data
public class GoodsVO {
    private Long id;
    private String name;
    private String category;
    private List<String> images;
    private String description;
    private BigDecimal priceFrom;
    private List<SkuVO> skus;

    @Data
    public static class SkuVO {
        private Long id;
        private String name;
        private BigDecimal price;
        private Integer stock;
    }
}
