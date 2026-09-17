package cn.petshop.mall.service;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.SkuMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 商品目录：只读。下单与订单相关逻辑在 {@link MallOrderService}。 */
@Service
@RequiredArgsConstructor
public class MallService {

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;

    /** 上架商品列表（含 SKU）。下架商品对会员不可见。 */
    public List<GoodsVO> listOnSale() {
        List<Goods> goods = goodsMapper.selectList(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getStatus, Goods.STATUS_ON_SALE)
                .orderByAsc(Goods::getId));
        if (goods.isEmpty()) {
            return List.of();
        }
        List<Long> goodsIds = goods.stream().map(Goods::getId).toList();
        // 一次查全部 SKU 再按 goodsId 分组，避免 N+1
        Map<Long, List<Sku>> skuByGoods = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                        .in(Sku::getGoodsId, goodsIds)
                        .orderByAsc(Sku::getId))
                .stream()
                .collect(Collectors.groupingBy(Sku::getGoodsId));
        return goods.stream()
                .map(g -> toVO(g, skuByGoods.getOrDefault(g.getId(), List.of())))
                .toList();
    }

    public GoodsVO detail(Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null || !Goods.STATUS_ON_SALE.equals(goods.getStatus())) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "商品不存在或已下架");
        }
        List<Sku> skus = skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getGoodsId, goodsId)
                .orderByAsc(Sku::getId));
        return toVO(goods, skus);
    }

    private GoodsVO toVO(Goods goods, List<Sku> skus) {
        GoodsVO vo = new GoodsVO();
        vo.setId(goods.getId());
        vo.setName(goods.getName());
        vo.setCategory(goods.getCategory());
        vo.setImages(parseImages(goods.getImages()));
        vo.setDescription(goods.getDescription());
        vo.setPriceFrom(goods.getPriceFrom());
        vo.setSkus(skus.stream().map(s -> {
            GoodsVO.SkuVO sv = new GoodsVO.SkuVO();
            sv.setId(s.getId());
            sv.setName(s.getName());
            sv.setPrice(s.getPrice());
            sv.setStock(s.getStock());
            return sv;
        }).toList());
        return vo;
    }

    /**
     * images 字段是 TEXT，历史上没有约定格式。这里同时兼容 JSON 数组与逗号分隔，
     * 避免因为一条脏数据让整个列表页 500。
     */
    private List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(s.split(","))) {
            String v = part.trim().replaceAll("^\"|\"$", "");
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}
