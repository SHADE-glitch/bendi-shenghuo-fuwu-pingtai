package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 修改店铺请求参数 DTO
 * <p>
 * 与 {@link ShopSaveRequest} 共用同一套列约束，但<b>全部字段可缺省</b>，
 * 以保持 {@code updateById} 的部分更新语义：{@code PUT /shops/{shopId}}
 * 只提交需要变更的列，未提交即不更新（前端
 * {@code frontend/src/views/Merchant/ShopList.vue} 的编辑表单即为此用法）。
 * </p>
 *
 * <h3>「全部可缺省」与「不能一个都不给」</h3>
 * <p>
 * MyBatis-Plus 的 {@code NOT_NULL} 更新策略会把 {@code null} 字段从 SET 子句里剔除。
 * 若请求体一个可更新字段都没给（例如 {@code {}}），{@code updateById} 会生成
 * <b>没有 SET 子句</b>的 {@code UPDATE tb_shop  WHERE id=?}，
 * 数据库抛 {@code BadSqlGrammarException: ... near 'WHERE id=6663'}
 * 并最终返回 {@code 99999 系统繁忙}（2026-10-06 已运行时复现）。
 * 因此「至少提交一个待更新字段」由 {@code ShopController} 显式守卫，
 * 而不是靠字段级注解表达——字段级 {@code @NotNull} 会破坏部分更新语义。
 * </p>
 *
 * <h3>不在本 DTO 中的字段（白名单效应）</h3>
 * <ul>
 *   <li>{@code id}：由路径参数 {@code /shops/{shopId}} 决定，请求体携带会被忽略；</li>
 *   <li>{@code ownerId}：{@code Shop.ownerId} 声明了
 *       {@code updateStrategy = FieldStrategy.NEVER}，且本 DTO 不接收该字段，
 *       因此<b>改归属</b>在本接口上不可能；</li>
 *   <li>{@code createTime}/{@code updateTime}：由数据库维护。</li>
 * </ul>
 *
 * @see ShopSaveRequest
 */
@Data
public class ShopUpdateRequest {

    /**
     * 商铺名称（{@code tb_shop.name} varchar(128)）
     */
    @Size(max = 128, message = "店铺名称长度不能超过 128")
    private String name;

    /**
     * 所在城市（{@code tb_shop.city} varchar(32)）
     */
    @Size(max = 32, message = "城市长度不能超过 32")
    private String city;

    /**
     * 商铺类型 id（{@code tb_shop.type_id} bigint unsigned）
     */
    @Positive(message = "店铺类型 id 必须为正数")
    private Long typeId;

    /**
     * 商铺图片 JSON 数组（{@code tb_shop.images} varchar(1024)）
     */
    @Size(max = 1024, message = "店铺图片字段长度不能超过 1024")
    private String images;

    /**
     * 商圈（{@code tb_shop.area} varchar(128)）
     */
    @Size(max = 128, message = "商圈长度不能超过 128")
    private String area;

    /**
     * 地址（{@code tb_shop.address} varchar(255)）
     */
    @Size(max = 255, message = "店铺地址长度不能超过 255")
    private String address;

    /**
     * 经度（{@code tb_shop.x} double unsigned）
     */
    @PositiveOrZero(message = "店铺经度不能为负数")
    private Double x;

    /**
     * 纬度（{@code tb_shop.y} double unsigned）
     */
    @PositiveOrZero(message = "店铺纬度不能为负数")
    private Double y;

    /**
     * 均价（{@code tb_shop.avg_price} bigint unsigned）
     */
    @PositiveOrZero(message = "店铺均价不能为负数")
    private Long avgPrice;

    /**
     * 销量（{@code tb_shop.sold} unsigned）
     */
    @PositiveOrZero(message = "店铺销量不能为负数")
    private Integer sold;

    /**
     * 评论数量（{@code tb_shop.comments} unsigned）
     */
    @PositiveOrZero(message = "店铺评论数不能为负数")
    private Integer comments;

    /**
     * 评分（{@code tb_shop.score} unsigned，乘 10 保存）
     */
    @PositiveOrZero(message = "店铺评分不能为负数")
    private Integer score;

    /**
     * 营业时间（{@code tb_shop.open_hours} varchar(32)）
     */
    @Size(max = 32, message = "营业时间长度不能超过 32")
    private String openHours;

    /**
     * 是否至少提交了一个可更新字段
     * <p>
     * 供 {@code ShopController} 守卫使用，防止生成无 SET 子句的非法 UPDATE。
     * </p>
     *
     * @return 至少一个业务字段非 {@code null} 时返回 {@code true}
     */
    public boolean hasAnyField() {
        return name != null || city != null || typeId != null || images != null
                || area != null || address != null || x != null || y != null
                || avgPrice != null || sold != null || comments != null
                || score != null || openHours != null;
    }
}