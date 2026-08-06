package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.AgisoRejectedOrderEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgisoRejectedOrderMapper extends BaseMapper<AgisoRejectedOrderEntity> {
    @Update("""
        INSERT INTO agiso_rejected_orders (
            display_order_no, protocol, user_id, buyer_account, external_order_no, product_no,
            goods_name, goods_type, quantity, external_max_amount, expected_amount,
            recharge_account, recharge_fields_json, callback_url, reject_code, reject_reason,
            attempt_count, state, rejected_at
        ) VALUES (
            #{displayOrderNo}, 'AGISO', #{userId}, #{buyerAccount}, #{externalOrderNo}, #{productNo},
            #{goodsName}, #{goodsType}, #{quantity}, #{externalMaxAmount}, #{expectedAmount},
            #{rechargeAccount}, #{rechargeFieldsJson}, #{callbackUrl}, #{rejectCode}, #{rejectReason},
            1, 'ACTIVE', #{rejectedAt}
        )
        ON DUPLICATE KEY UPDATE
            buyer_account = VALUES(buyer_account), product_no = VALUES(product_no),
            goods_name = VALUES(goods_name), goods_type = VALUES(goods_type), quantity = VALUES(quantity),
            external_max_amount = VALUES(external_max_amount), expected_amount = VALUES(expected_amount),
            recharge_account = VALUES(recharge_account), recharge_fields_json = VALUES(recharge_fields_json),
            callback_url = VALUES(callback_url), reject_code = VALUES(reject_code),
            reject_reason = VALUES(reject_reason), attempt_count = attempt_count + 1,
            state = 'ACTIVE', resolved_order_no = NULL, resolved_at = NULL, rejected_at = VALUES(rejected_at)
        """)
    int upsert(
        @Param("displayOrderNo") String displayOrderNo,
        @Param("userId") Long userId,
        @Param("buyerAccount") String buyerAccount,
        @Param("externalOrderNo") String externalOrderNo,
        @Param("productNo") Long productNo,
        @Param("goodsName") String goodsName,
        @Param("goodsType") String goodsType,
        @Param("quantity") Integer quantity,
        @Param("externalMaxAmount") BigDecimal externalMaxAmount,
        @Param("expectedAmount") BigDecimal expectedAmount,
        @Param("rechargeAccount") String rechargeAccount,
        @Param("rechargeFieldsJson") String rechargeFieldsJson,
        @Param("callbackUrl") String callbackUrl,
        @Param("rejectCode") String rejectCode,
        @Param("rejectReason") String rejectReason,
        @Param("rejectedAt") OffsetDateTime rejectedAt
    );

    @Update("""
        UPDATE agiso_rejected_orders
        SET state = 'RESOLVED', resolved_order_no = #{orderNo}, resolved_at = #{resolvedAt}
        WHERE protocol = 'AGISO' AND user_id = #{userId} AND external_order_no = #{externalOrderNo}
          AND state = 'ACTIVE'
        """)
    int resolve(@Param("userId") Long userId, @Param("externalOrderNo") String externalOrderNo,
                @Param("orderNo") String orderNo, @Param("resolvedAt") OffsetDateTime resolvedAt);

    @Select("""
        <script>
        SELECT id, display_order_no, protocol, user_id, buyer_account, external_order_no, product_no,
               goods_name, goods_type, quantity, external_max_amount, expected_amount, recharge_account,
               recharge_fields_json, callback_url, reject_code, reject_reason, attempt_count, state,
               resolved_order_no, rejected_at, resolved_at, created_at, updated_at
        FROM agiso_rejected_orders
        WHERE state = 'ACTIVE'
        <if test="search != null and search != ''">
          AND (display_order_no LIKE CONCAT('%', #{search}, '%')
            OR external_order_no LIKE CONCAT('%', #{search}, '%')
            OR CAST(product_no AS CHAR) = #{search}
            OR goods_name LIKE CONCAT('%', #{search}, '%')
            OR recharge_account LIKE CONCAT('%', #{search}, '%'))
        </if>
        <if test="goodsType != null and goodsType != ''">AND goods_type = #{goodsType}</if>
        <if test="createdFrom != null">AND rejected_at &gt;= #{createdFrom}</if>
        ORDER BY rejected_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<AgisoRejectedOrderEntity> selectActivePage(
        @Param("search") String search, @Param("goodsType") String goodsType,
        @Param("createdFrom") OffsetDateTime createdFrom, @Param("limit") int limit, @Param("offset") long offset
    );

    @Select("""
        <script>
        SELECT COUNT(*) FROM agiso_rejected_orders WHERE state = 'ACTIVE'
        <if test="search != null and search != ''">
          AND (display_order_no LIKE CONCAT('%', #{search}, '%')
            OR external_order_no LIKE CONCAT('%', #{search}, '%')
            OR CAST(product_no AS CHAR) = #{search}
            OR goods_name LIKE CONCAT('%', #{search}, '%')
            OR recharge_account LIKE CONCAT('%', #{search}, '%'))
        </if>
        <if test="goodsType != null and goodsType != ''">AND goods_type = #{goodsType}</if>
        <if test="createdFrom != null">AND rejected_at &gt;= #{createdFrom}</if>
        </script>
        """)
    long countActive(@Param("search") String search, @Param("goodsType") String goodsType,
                     @Param("createdFrom") OffsetDateTime createdFrom);

    @Select("""
        SELECT id, display_order_no, protocol, user_id, buyer_account, external_order_no, product_no,
               goods_name, goods_type, quantity, external_max_amount, expected_amount, recharge_account,
               recharge_fields_json, callback_url, reject_code, reject_reason, attempt_count, state,
               resolved_order_no, rejected_at, resolved_at, created_at, updated_at
        FROM agiso_rejected_orders
        WHERE display_order_no = #{displayOrderNo} AND state = 'ACTIVE' LIMIT 1
        """)
    AgisoRejectedOrderEntity findActiveByDisplayOrderNo(@Param("displayOrderNo") String displayOrderNo);

    @Select("""
        <script>
        SELECT COALESCE(SUM(external_max_amount), 0) AS total
        FROM agiso_rejected_orders WHERE state = 'ACTIVE'
        <if test="search != null and search != ''">
          AND (display_order_no LIKE CONCAT('%', #{search}, '%')
            OR external_order_no LIKE CONCAT('%', #{search}, '%')
            OR CAST(product_no AS CHAR) = #{search}
            OR goods_name LIKE CONCAT('%', #{search}, '%')
            OR recharge_account LIKE CONCAT('%', #{search}, '%'))
        </if>
        <if test="goodsType != null and goodsType != ''">AND goods_type = #{goodsType}</if>
        <if test="createdFrom != null">AND rejected_at &gt;= #{createdFrom}</if>
        </script>
        """)
    BigDecimal sumExternalAmount(@Param("search") String search, @Param("goodsType") String goodsType,
                                 @Param("createdFrom") OffsetDateTime createdFrom);

    @Select("""
        <script>
        SELECT COUNT(*) FROM agiso_rejected_orders WHERE state = 'ACTIVE' AND external_max_amount IS NULL
        <if test="search != null and search != ''">
          AND (display_order_no LIKE CONCAT('%', #{search}, '%')
            OR external_order_no LIKE CONCAT('%', #{search}, '%')
            OR CAST(product_no AS CHAR) = #{search}
            OR goods_name LIKE CONCAT('%', #{search}, '%')
            OR recharge_account LIKE CONCAT('%', #{search}, '%'))
        </if>
        <if test="goodsType != null and goodsType != ''">AND goods_type = #{goodsType}</if>
        <if test="createdFrom != null">AND rejected_at &gt;= #{createdFrom}</if>
        </script>
        """)
    long countMissingExternalAmount(@Param("search") String search, @Param("goodsType") String goodsType,
                                    @Param("createdFrom") OffsetDateTime createdFrom);
}
