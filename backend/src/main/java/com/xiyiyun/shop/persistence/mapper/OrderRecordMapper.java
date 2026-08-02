package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderRecordMapper extends BaseMapper<OrderRecordEntity> {
    @Select("SELECT id FROM orders WHERE order_no = #{orderNo} AND deleted_at IS NULL LIMIT 1")
    Long findIdByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
        SELECT id, order_no, user_id, buyer_account, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, external_max_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, delivery_card_ids_json, channel_attempts_json, recharge_account, recharge_fields_json,
               buyer_remark, admin_remark, request_id, upstream_order_no,
               paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE deleted_at IS NULL
        ORDER BY created_at DESC, id DESC
        """)
    List<OrderRecordEntity> selectActiveSnapshots();

    /**
     * 补偿任务候选批次。筛选、时间排序和 LIMIT 必须留在 SQL，避免定时任务加载历史全表。
     *
     * <p>新订单只在回调地址确实随下单请求发送时才把 callbackUrl 写进渠道尝试；
     * 因而 withoutCallback=true 时可直接排除 JSON 中存在非空 callbackUrl 的已提交尝试。
     * 旧订单没有该字段，会自然进入快速查单。
     */
    @Select("""
        <script>
        SELECT id, order_no, user_id, buyer_account, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, external_max_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, delivery_card_ids_json, channel_attempts_json, recharge_account, recharge_fields_json,
               buyer_remark, admin_remark, request_id, upstream_order_no,
               paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE deleted_at IS NULL
          AND goods_type = 'DIRECT'
          AND status IN ('PROCURING', 'DELIVERING')
          AND COALESCE(paid_at, created_at) &lt; #{deadline}
        <if test="withoutCallback">
          AND NOT EXISTS (
            SELECT 1
            FROM JSON_TABLE(
              COALESCE(channel_attempts_json, JSON_ARRAY()),
              '$[*]' COLUMNS (
                attempt_status VARCHAR(32) PATH '$.status',
                callback_url VARCHAR(2048) PATH '$.callbackUrl'
              )
            ) AS attempt
            WHERE attempt.attempt_status IN ('SUCCESS', 'PROCURING')
              AND NULLIF(TRIM(attempt.callback_url), '') IS NOT NULL
          )
        </if>
        ORDER BY COALESCE(paid_at, created_at) ASC, id ASC
        LIMIT #{limit}
        </script>
        """)
    List<OrderRecordEntity> selectUnsettledCandidates(
        @Param("deadline") java.time.OffsetDateTime deadline,
        @Param("limit") int limit,
        @Param("withoutCallback") boolean withoutCallback
    );

    /**
     * 批次8C：带筛选的<b>分页</b>订单快照。
     *
     * <h2>为什么必须下推到 SQL</h2>
     * 原路径是 {@code selectActiveSnapshots()} 全表捞出 → Java 里 filter/sort → 控制层
     * {@code subList} 切页。orders 每行带 5 个 JSON 列与 MEDIUMTEXT，行很重，
     * 于是「看起来分页的接口」实际是每次请求把整张订单表读进堆。
     * 订单表是随交易量无上界增长的，这条路径迟早 OOM，而且先崩的是管理后台列表页。
     *
     * <h2>关键字匹配为什么带 CASE</h2>
     * {@code OrderItem.deliveryMessage} 不是原样存的列：
     * {@code OrderPersistenceMapper.deliveryMessage} 在库里 {@code delivery_message} 为空时，
     * 会按状态回落成"订单已完成/订单处理失败/订单已退款/订单已关闭"，再回落 {@code delivery_status}。
     * 原 Java 过滤匹配的是<b>回落后</b>的值，所以搜"已完成"能命中那些库里消息为空的完成单。
     * 直接写 {@code delivery_message LIKE ...} 会漏掉这批，故此处用 CASE 复刻同一套回落，
     * 保证下推前后搜索结果一致。
     *
     * <h2>ESCAPE 的反斜杠要写四个</h2>
     * 关键字是用户输入，里面的 {@code %} 与 {@code _} 是 LIKE 元字符。
     * 调用方（{@code PersistentOrderStore.likeKeyword}）已把它们转义，
     * 这里必须显式声明 ESCAPE 字符，否则搜 "50%" 会退化成通配前缀匹配。
     *
     * <p>坑：本注解是 Java <b>文本块</b>，文本块仍然做转义处理。源码里写
     * {@code ESCAPE '\\'} 交给 MySQL 的是 {@code ESCAPE '\'}——MySQL 把那个反斜杠
     * 当成转义符吃掉了后面的收尾单引号，字符串字面量就没结束，整条语句变成语法错误。
     * 而这条 SQL 的调用点在 {@code InMemoryShopRepository} 里被
     * {@code catch (RuntimeException)} 兜到内存路径，异常只写进一条
     * PERSISTENCE_READ_FALLBACK 审计记录，不落 stdout，
     * 表现就是「接口 200、列表恒为空、日志里干干净净」。所以源码里写四个反斜杠，
     * 让 MySQL 收到 {@code ESCAPE '\\'}（一个字面反斜杠）。
     *
     * <h2>为什么用 &lt;if&gt; 而不是 {@code #{x} IS NULL OR ...}</h2>
     * 那种内联写法要求 MySQL 对一个未带 jdbcType 的绑定参数求 {@code ? IS NULL}，
     * 不可靠；参数为 null 时整个谓词不为真，会把所有行筛掉。
     * 动态 SQL 在 null 时直接<b>不生成</b>这段条件，语义确定。
     *
     * <p>排序键 {@code (created_at DESC, id DESC)} 与原实现一致，且 id 兜底保证
     * 同秒创建的订单在翻页时有稳定顺序（否则同一行可能在两页都出现或都不出现）。
     */
    @Select("""
        <script>
        SELECT id, order_no, user_id, buyer_account, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, external_max_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, delivery_card_ids_json, channel_attempts_json, recharge_account, recharge_fields_json,
               buyer_remark, admin_remark, request_id, upstream_order_no,
               paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE deleted_at IS NULL
        <if test="userId != null"> AND user_id = #{userId}</if>
        <if test="status != null"> AND LOWER(status) = #{status}</if>
        <if test="goodsType != null"> AND LOWER(goods_type) = #{goodsType}</if>
        <if test="createdFrom != null"> AND created_at &gt;= #{createdFrom}</if>
        <if test="keyword != null">
          AND (   LOWER(order_no) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(goods_name, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(source_platform_code, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(recharge_account, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(request_id, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(CASE
                     WHEN delivery_message IS NOT NULL AND TRIM(delivery_message) != '' THEN delivery_message
                     WHEN status = 'DELIVERED' THEN '订单已完成'
                     WHEN status = 'FAILED' THEN '订单处理失败'
                     WHEN status = 'REFUNDED' THEN '订单已退款'
                     WHEN status IN ('CANCELLED', 'CLOSED') THEN '订单已关闭'
                     ELSE COALESCE(delivery_status, '')
                   END) LIKE #{keyword} ESCAPE '\\\\')
        </if>
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<OrderRecordEntity> selectSnapshotPage(
        @Param("keyword") String keyword,
        @Param("status") String status,
        @Param("goodsType") String goodsType,
        @Param("createdFrom") java.time.OffsetDateTime createdFrom,
        @Param("userId") Long userId,
        @Param("limit") int limit,
        @Param("offset") long offset
    );

    /**
     * 批次8C：与 {@link #selectSnapshotPage} 使用<b>完全相同</b>的 WHERE，供分页返回 total。
     *
     * <p>两条 WHERE 必须逐字一致。一旦漂移，用户看到的就是
     * 「共 41 条」但翻到第 5 页空白，或者反过来最后一页的数据永远取不到。
     * 改动其中任何一条时，另一条要同步改。
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM orders
        WHERE deleted_at IS NULL
        <if test="userId != null"> AND user_id = #{userId}</if>
        <if test="status != null"> AND LOWER(status) = #{status}</if>
        <if test="goodsType != null"> AND LOWER(goods_type) = #{goodsType}</if>
        <if test="createdFrom != null"> AND created_at &gt;= #{createdFrom}</if>
        <if test="keyword != null">
          AND (   LOWER(order_no) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(goods_name, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(source_platform_code, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(recharge_account, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(COALESCE(request_id, '')) LIKE #{keyword} ESCAPE '\\\\'
               OR LOWER(CASE
                     WHEN delivery_message IS NOT NULL AND TRIM(delivery_message) != '' THEN delivery_message
                     WHEN status = 'DELIVERED' THEN '订单已完成'
                     WHEN status = 'FAILED' THEN '订单处理失败'
                     WHEN status = 'REFUNDED' THEN '订单已退款'
                     WHEN status IN ('CANCELLED', 'CLOSED') THEN '订单已关闭'
                     ELSE COALESCE(delivery_status, '')
                   END) LIKE #{keyword} ESCAPE '\\\\')
        </if>
        </script>
        """)
    long countSnapshots(
        @Param("keyword") String keyword,
        @Param("status") String status,
        @Param("goodsType") String goodsType,
        @Param("createdFrom") java.time.OffsetDateTime createdFrom,
        @Param("userId") Long userId
    );

    @Select("""
        SELECT id, order_no, user_id, buyer_account, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, external_max_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, delivery_card_ids_json, channel_attempts_json, recharge_account, recharge_fields_json,
               buyer_remark, admin_remark, request_id, upstream_order_no,
               paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE order_no = #{orderNo}
          AND deleted_at IS NULL
        LIMIT 1
        """)
    OrderRecordEntity findByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 批次8C：按 (user_id, request_id) 直接定位订单。
     *
     * <p>原来 {@code /api/member/orders/by-request/{requestId}} 是「拉出该用户的全部订单
     * → Java 里 filter requestId → findFirst」。会员方轮询这个接口查下单结果，
     * 于是每次轮询都要把该用户的历史订单整个读一遍，老用户越用越慢。
     *
     * <p>{@code uk_orders_user_request (user_id, request_id)} 是唯一键，
     * 所以这里是一次唯一索引等值查找，且天然只能命中一行。
     */
    @Select("""
        SELECT id, order_no, user_id, buyer_account, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, external_max_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, delivery_card_ids_json, channel_attempts_json, recharge_account, recharge_fields_json,
               buyer_remark, admin_remark, request_id, upstream_order_no,
               paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
          AND deleted_at IS NULL
        LIMIT 1
        """)
    OrderRecordEntity findByUserAndRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Update("""
        INSERT INTO orders (
            order_no, user_id, buyer_account, source_platform_id, source_platform_code, goods_id,
            goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount, pay_amount, external_max_amount,
            cost_amount, status, delivery_status, delivery_message, delivery_items_json, delivery_card_ids_json,
            channel_attempts_json, recharge_account, recharge_fields_json, buyer_remark, admin_remark,
            request_id, paid_at, delivered_at, closed_at, created_at
        ) VALUES (
            #{entity.orderNo}, #{entity.userId}, #{entity.buyerAccount}, #{entity.sourcePlatformId}, #{entity.sourcePlatformCode}, #{entity.goodsId},
            #{entity.goodsName}, #{entity.goodsType}, #{entity.orderIp}, #{entity.orderIpLocation}, #{entity.quantity}, #{entity.unitPrice}, #{entity.totalAmount}, #{entity.payAmount}, #{entity.externalMaxAmount},
            #{entity.costAmount}, #{entity.status}, #{entity.deliveryStatus}, #{entity.deliveryMessage}, #{entity.deliveryItemsJson}, #{entity.deliveryCardIdsJson},
            #{entity.channelAttemptsJson}, #{entity.rechargeAccount}, #{entity.rechargeFieldsJson}, #{entity.buyerRemark}, #{entity.adminRemark},
            #{entity.requestId}, #{entity.paidAt}, #{entity.deliveredAt}, #{entity.closedAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            buyer_account = VALUES(buyer_account),
            source_platform_code = VALUES(source_platform_code),
            goods_name = VALUES(goods_name),
            goods_type = VALUES(goods_type),
            order_ip = VALUES(order_ip),
            order_ip_location = VALUES(order_ip_location),
            quantity = VALUES(quantity),
            unit_price = VALUES(unit_price),
            total_amount = VALUES(total_amount),
            pay_amount = VALUES(pay_amount),
            external_max_amount = COALESCE(external_max_amount, VALUES(external_max_amount)),
            cost_amount = VALUES(cost_amount),
            status = VALUES(status),
            delivery_status = VALUES(delivery_status),
            delivery_message = VALUES(delivery_message),
            delivery_items_json = VALUES(delivery_items_json),
            delivery_card_ids_json = VALUES(delivery_card_ids_json),
            channel_attempts_json = VALUES(channel_attempts_json),
            recharge_account = VALUES(recharge_account),
            recharge_fields_json = VALUES(recharge_fields_json),
            buyer_remark = VALUES(buyer_remark),
            admin_remark = VALUES(admin_remark),
            paid_at = VALUES(paid_at),
            delivered_at = VALUES(delivered_at),
            closed_at = VALUES(closed_at)
        """)
    int upsertByOrderNo(@Param("entity") OrderRecordEntity entity);

    /**
     * 写入上游订单号（缺陷 A4 对账依据）。
     *
     * <p><b>为什么不放进 {@link #upsertByOrderNo} 的 ON DUPLICATE KEY UPDATE：</b>
     * orders 上有两个唯一索引（uk_orders_order_no 与 006 建的 uk_orders_upstream）。
     * ODKU 只要命中<b>任一</b>唯一索引就转 UPDATE，若新单带的上游单号与<b>另一笔</b>订单
     * 重复，ODKU 会把那笔无关订单的整行改写成本单数据 —— 静默串单。
     * 故这里用独立的、按 order_no 定位的 UPDATE：撞 uk_orders_upstream 时
     * 抛 DuplicateKeyException（重复采购的真实信号），而不是改错行。
     *
     * <p>另加 {@code upstream_order_no IS NULL} 条件做 write-once：上游单号一经落库
     * 不允许被后续快照覆盖，避免重试/对账把已记录的采购凭据冲掉。
     * 返回 0 表示"已有值或订单不存在"，调用方据此判断是否需要告警。
     */
    @Update("""
        UPDATE orders
        SET upstream_order_no = #{upstreamOrderNo}
        WHERE order_no = #{orderNo}
          AND deleted_at IS NULL
          AND upstream_order_no IS NULL
        """)
    int bindUpstreamOrderNo(
        @Param("orderNo") String orderNo,
        @Param("upstreamOrderNo") String upstreamOrderNo
    );

    @Select("SELECT upstream_order_no FROM orders WHERE order_no = #{orderNo} AND deleted_at IS NULL LIMIT 1")
    String selectUpstreamOrderNo(@Param("orderNo") String orderNo);

    @Update("""
        UPDATE orders
        SET external_max_amount = COALESCE(external_max_amount, #{externalMaxAmount})
        WHERE order_no = #{orderNo}
          AND user_id = #{userId}
          AND deleted_at IS NULL
        """)
    int saveExternalMaxAmount(
        @Param("orderNo") String orderNo,
        @Param("userId") Long userId,
        @Param("externalMaxAmount") BigDecimal externalMaxAmount
    );

    @Update("UPDATE orders SET delivery_card_ids_json = #{deliveryCardIdsJson} WHERE order_no = #{orderNo} AND deleted_at IS NULL")
    int updateDeliveryCardIds(
        @Param("orderNo") String orderNo,
        @Param("deliveryCardIdsJson") String deliveryCardIdsJson
    );

    @Delete("DELETE FROM order_status_logs WHERE order_no = #{orderNo}")
    int hardDeleteStatusLogsByOrderNo(@Param("orderNo") String orderNo);

    @Delete("DELETE FROM delivery_tasks WHERE order_no = #{orderNo}")
    int hardDeleteDeliveryTasksByOrderNo(@Param("orderNo") String orderNo);

    @Delete("DELETE FROM orders WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
