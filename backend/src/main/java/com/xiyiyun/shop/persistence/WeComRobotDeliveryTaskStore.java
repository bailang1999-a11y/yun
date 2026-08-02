package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.WeComRobotMessageFactory;
import com.xiyiyun.shop.persistence.entity.WeComRobotDeliveryTaskEntity;
import com.xiyiyun.shop.persistence.mapper.WeComRobotDeliveryTaskMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeComRobotDeliveryTaskStore {
    private static final int MAX_BATCH_SIZE = 200;

    private final WeComRobotDeliveryTaskMapper mapper;

    public WeComRobotDeliveryTaskStore(WeComRobotDeliveryTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void registerOrderEvents(OrderItem order) {
        OffsetDateTime now = OffsetDateTime.now();
        for (WeComRobotMessageFactory.Notification notification : WeComRobotMessageFactory.from(order)) {
            mapper.registerOrderEvent(
                notification.eventId(), notification.event().name(), notification.orderNo(),
                notification.markdown(), now
            );
        }
    }

    @Transactional
    public WeComRobotDeliveryTaskEntity registerTest() {
        OffsetDateTime now = OffsetDateTime.now();
        String eventId = "wcm_test_" + UUID.randomUUID().toString().replace("-", "");
        WeComRobotDeliveryTaskEntity task = new WeComRobotDeliveryTaskEntity();
        task.setEventId(eventId);
        task.setEventType("TEST");
        task.setMarkdownContent(WeComRobotMessageFactory.testMessage());
        task.setState("PENDING");
        task.setAttemptCount(0);
        task.setNextAttemptAt(now);
        mapper.insert(task);
        return task;
    }

    @Transactional(readOnly = true)
    public List<WeComRobotDeliveryTaskEntity> findDue(OffsetDateTime now, int limit) {
        return mapper.selectDue(now, bounded(limit));
    }

    @Transactional(readOnly = true)
    public List<WeComRobotDeliveryTaskEntity> latest(int limit) {
        return mapper.selectLatest(bounded(limit));
    }

    @Transactional
    public boolean claim(Long id, OffsetDateTime now, OffsetDateTime leaseUntil) {
        return mapper.claim(id, now, leaseUntil) == 1;
    }

    @Transactional
    public boolean retry(Long id, OffsetDateTime nextAttemptAt, String error) {
        return mapper.retry(id, nextAttemptAt, text(error)) == 1;
    }

    @Transactional
    public boolean markSent(Long id, OffsetDateTime sentAt) {
        return mapper.markSent(id, sentAt) == 1;
    }

    @Transactional
    public boolean markDead(Long id, String error) {
        return mapper.markDead(id, text(error)) == 1;
    }

    @Transactional
    public boolean markCancelled(Long id, String reason) {
        return mapper.markCancelled(id, text(reason)) == 1;
    }

    @Transactional
    public int deleteByOrderNo(String orderNo) {
        return mapper.hardDeleteByOrderNo(orderNo == null ? "" : orderNo.trim());
    }

    private int bounded(int limit) {
        return Math.max(1, Math.min(limit, MAX_BATCH_SIZE));
    }

    private String text(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }
}
