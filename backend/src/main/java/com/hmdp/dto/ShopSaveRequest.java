package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 新增店铺请求参数 DTO
 * <p>
 * 采用<b>白名单 + 与 DDL 对齐的必填/取值约束</b>设计，替代此前直接把
 * {@link com.hmdp.entity.Shop} 实体当作请求体接收的做法。
 * </p>
 *
 * <h3>为什么必须是 DTO 而不是实体</h3>
 * <p>
 * {@code Shop} 实体零校验注解，因此 {@code POST /shops} 上原有的 {@code @Valid}
 * 是<b>死注解</b>；而 {@code tb_shop} 有 9 个 {@code NOT NULL} 且<b>无 DEFAULT</b> 的列
 * （{@code name/type_id/images/address/x/y/sold/comments/score}），
 * MyBatis-Plus 的 {@code NOT_NULL} 插入策略会把请求体里没传的字段从 INSERT 列里剔除，
 * 于是缺任一列都会在数据库层抛 {@code Field 'xxx' doesn't have a default value}，
 * 最终落到兜底分支返回 {@code 99999 系统繁忙}，客户端无从得知缺哪个字段
 * （2026-10-06 已运行时复现）。
 * </p>
 *
 * <h3>字段来源</h3>
 * <p>
 * 全部列名、类型与长度取自线上表结构（{@code V1__Init_Database.sql} 建 {@code tb_shop}，
 * {@code city} 由后续迁移追加），因此每个 {@code @Size}/{@code @PositiveOrZero}
 * 都能追溯到一条具体列定义，不含自创业务规则：
 * <ul>
 *   <li>{@code @Size} 对齐 {@code varchar} 长度，避免
 *       {@code MysqlDataTruncation: Data too long for column} 变成 99999；</li>
 *   <li>{@code @PositiveOrZero} 对齐 {@code UNSIGNED} 列，避免负值被数据库拒绝
 *       （实测 {@code x=-1} / {@code sold=-1} 均返回 99999）；</li>
 *   <li>{@code @Positive} 对齐 {@code bigint unsigned} 主外键列。</li>
 * </ul>
 * </p>
 *
 * <h3>不在本 DTO 中的字段（白名单效应）</h3>
 * <ul>
 *   <li>{@code id}：数据库自增；</li>
 *   <li>{@code ownerId}：归属商家由服务端从当前登录态注入，
 *       请求体夹带会被静默忽略（项目未开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}）；</li>
 *   <li>{@code createTime}/{@code updateTime}：由数据库维护；</li>
 *   <li>{@code distance}：查询期计算字段，非持久化。</li>
 * </ul>
 *
 * @see ShopUpdateRequest
 */
@Data
public class ShopSaveRequest {

    /**
     * 商铺名称（{@code tb_shop.name} NOT NULL varchar(128)）
     */
    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 128, message = "店铺名称长度不能超过 128")
    private String name;

    /**
     * 所在城市（{@code tb_shop.city} varchar(32)，可空）
     */
    @Size(max = 32, message = "城市长度不能超过 32")
    private String city;

    /**
     * 商铺类型 id（{@code tb_shop.type_id} NOT NULL bigint unsigned）
     */
    @NotNull(message = "店铺类型不能为空")
    @Positive(message = "店铺类型 id 必须为正数")
    private Long typeId;

    /**
     * 商铺图片 JSON 数组（{@code tb_shop.images} NOT NULL varchar(1024)）
     */
    @NotNull(message = "店铺图片不能为空")
    @Size(max = 1024, message = "店铺图片字段长度不能超过 1024")
    private String images;

    /**
     * 商圈（{@code tb_shop.area} varchar(128)，可空）
     */
    @Size(max = 128, message = "商圈长度不能超过 128")
    private String area;

    /**
     * 地址（{@code tb_shop.address} NOT NULL varchar(255)）
     */
    @NotBlank(message = "店铺地址不能为空")
    @Size(max = 255, message = "店铺地址长度不能超过 255")
    private String address;

    /**
     * 经度（{@code tb_shop.x} NOT NULL double unsigned）
     */
    @NotNull(message = "店铺经度不能为空")
    @PositiveOrZero(message = "店铺经度不能为负数")
    private Double x;

    /**
     * 纬度（{@code tb_shop.y} NOT NULL double unsigned）
     */
    @NotNull(message = "店铺纬度不能为空")
    @PositiveOrZero(message = "店铺纬度不能为负数")
    private Double y;

    /**
     * 均价（{@code tb_shop.avg_price} bigint unsigned，可空）
     */
    @PositiveOrZero(message = "店铺均价不能为负数")
    private Long avgPrice;

    /**
     * 销量（{@code tb_shop.sold} NOT NULL unsigned，新店为 0）
     */
    @NotNull(message = "店铺销量不能为空")
    @PositiveOrZero(message = "店铺销量不能为负数")
    private Integer sold;

    /**
     * 评论数量（{@code tb_shop.comments} NOT NULL unsigned，新店为 0）
     */
    @NotNull(message = "店铺评论数不能为空")
    @PositiveOrZero(message = "店铺评论数不能为负数")
    private Integer comments;

    /**
     * 评分（{@code tb_shop.score} NOT NULL unsigned，乘 10 保存）
     */
    @NotNull(message = "店铺评分不能为空")
    @PositiveOrZero(message = "店铺评分不能为负数")
    private Integer score;

    /**
     * 营业时间（{@code tb_shop.open_hours} varchar(32)，可空）
     */
    @Size(max = 32, message = "营业时间长度不能超过 32")
    private String openHours;
}
