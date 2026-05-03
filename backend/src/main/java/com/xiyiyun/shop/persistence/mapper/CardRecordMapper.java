package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.CardRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CardRecordMapper extends BaseMapper<CardRecordEntity> {
    @Update("""
        INSERT INTO cards (
            id, goods_id, card_kind_id, batch_no, card_ciphertext, card_nonce, card_key_version,
            card_hash, card_preview, status, locked_order_id, sold_order_id, sold_at, created_at
        ) VALUES (
            #{entity.id}, #{entity.goodsId}, #{entity.cardKindId}, #{entity.batchNo}, #{entity.cardCiphertext}, #{entity.cardNonce}, #{entity.cardKeyVersion},
            #{entity.cardHash}, #{entity.cardPreview}, #{entity.status}, #{entity.lockedOrderId}, #{entity.soldOrderId}, #{entity.soldAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            card_preview = VALUES(card_preview),
            status = VALUES(status)
        """)
    int upsertImportedCard(@Param("entity") CardRecordEntity entity);

    @Select("""
        SELECT *
        FROM cards
        WHERE goods_id = #{goodsId}
          AND status = 'UNSOLD'
          AND deleted_at IS NULL
        ORDER BY id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<CardRecordEntity> lockAvailableCardsByGoodsId(
        @Param("goodsId") Long goodsId,
        @Param("limit") int limit
    );

    @Select("""
        SELECT *
        FROM cards
        WHERE card_kind_id = #{cardKindId}
          AND status = 'UNSOLD'
          AND deleted_at IS NULL
        ORDER BY id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<CardRecordEntity> lockAvailableCardsByCardKindId(
        @Param("cardKindId") Long cardKindId,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE cards
        SET status = 'SOLD',
            sold_order_id = #{orderId},
            sold_at = #{soldAt}
        WHERE id = #{cardId}
          AND status = 'UNSOLD'
          AND deleted_at IS NULL
        """)
    int markSold(
        @Param("cardId") Long cardId,
        @Param("orderId") Long orderId,
        @Param("soldAt") OffsetDateTime soldAt
    );
}
