package cn.petshop.mall.service;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台商品视图。
 *
 * <p>与会员端 {@link GoodsVO} 的区别：后台要看到<b>下架</b>商品、SKU 主键、以及库存，
 * 而会员端只关心"能卖的"。两者刻意不合并 —— 会员端一旦拿到 {@code status}，
 * 就会有人忍不住在前端做过滤，而"下架商品是否可见"应该是服务端单方面的决定。</p>
 */
@Data
public class AdminGoodsVO {
    private Long id;
    private String name;
    private String category;
    private List<String> images;
    private String description;
    /** 服务端从 SKU 最低价推导，不接受客户端传入 */
    private BigDecimal priceFrom;
    private String status;
    /** 该商品在架 SKU 的库存合计，列表页用来一眼看出"哪些要补货" */
    private Integer totalStock;
    private List<SkuVO> skus;

    @Data
    public static class SkuVO {
        private Long id;
        private String name;
        private BigDecimal price;
        private Integer stock;
    }
}
