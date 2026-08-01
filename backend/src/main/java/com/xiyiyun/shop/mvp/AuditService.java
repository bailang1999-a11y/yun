package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.AuditPersistenceStore;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.StringUtils;

/**
 * 批次6 / 任务B：审计与日志（操作日志 / 短信日志 / 开放接口日志）的唯一出口。
 *
 * <h2>为什么单独一层</h2>
 * {@code appendOperation} 是彻底的横切关注点 —— 仓储里 88 处调用，遍布下单、支付回调、
 * 退款、商品、供应商、持久化降级等所有业务路径。它本身与业务无关：分号 id、
 * 塞内存 Map、镜像到 DB、失败时不许递归写日志。这些机制留在一万行仓储里，
 * 每次读业务代码都要绕过它。
 *
 * <h2>调用点为什么几乎没动</h2>
 * 仓储保留了同名同签名的 {@code private void appendOperation(...)}，内部只有一行
 * {@code auditService.appendOperation(...)}。88 个调用点<b>一个都不用改</b>，
 * 语义严格不变（同样的 action / resourceType / resourceId / remark，同样的 "system" 操作者）。
 * 这样这次重构不会在 88 个业务分支上引入任何行为差异，
 * 真正的职责（id 分配、内存态、DB 镜像、读降级）已经全部搬到本类。
 *
 * <h2>失败处理保持原样</h2>
 * 操作日志镜像失败<b>静默吞掉</b>（否则「审计不可用」本身又会触发一条审计写入，无限递归）；
 * 短信/开放接口日志镜像失败则记一条 {@code PERSISTENCE_MIRROR_FAILED} 操作日志。
 */
public class AuditService {
    /** 与批次6 之前一致：内存态只做无持久化场景的兜底与读降级。 */
    private final Map<Long, OperationLogItem> operationLogs = new ConcurrentHashMap<>();
    private final Map<Long, SmsLogItem> smsLogs = new ConcurrentHashMap<>();
    private final Map<Long, OpenApiLogItem> openApiLogs = new ConcurrentHashMap<>();
    private final AtomicLong operationLogId = new AtomicLong(1);
    private final AtomicLong smsLogId = new AtomicLong(1);
    private final AtomicLong openApiLogId = new AtomicLong(1);

    private final AuditPersistenceStore auditPersistenceStore;

    AuditService(AuditPersistenceStore auditPersistenceStore) {
        this.auditPersistenceStore = auditPersistenceStore;
    }

    boolean persistenceEnabled() {
        return auditPersistenceStore != null;
    }

    // ------------------------------------------------------------ 操作日志

    void appendOperation(String action, String resourceType, String resourceId, String remark) {
        Long id = operationLogId.getAndIncrement();
        OperationLogItem log = new OperationLogItem(
            id,
            "system",
            action,
            resourceType,
            resourceId,
            remark,
            OffsetDateTime.now()
        );
        operationLogs.put(id, log);
        persistOperationLog(log);
    }

    List<OperationLogItem> listOperationLogs() {
        Optional<List<OperationLogItem>> persistent = persistentOperationLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return operationLogs.values().stream()
            .sorted(Comparator.comparing(OperationLogItem::createdAt).reversed())
            .toList();
    }

    private void persistOperationLog(OperationLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveOperationLog(log);
        } catch (RuntimeException ignored) {
            // Avoid recursive operation-log writes when the audit mirror itself is unavailable.
        }
    }

    /**
     * 批次8C：操作日志分页。降级语义与 {@link #listOperationLogs} 保持一致 ——
     * 持久层缺失（单元测试无 DB）或读失败时回落到内存 Map。
     *
     * <p>内存兜底走 {@code PageSlice.of}：此时数据只存在于有界的内存 Map 里，
     * 切页不构成风险；排序键与 SQL 的 {@code created_at DESC} 对齐，保证两条路径页序一致。
     */
    PageSlice<OperationLogItem> pageOperationLogs(int limit, long offset) {
        if (auditPersistenceStore != null) {
            try {
                return auditPersistenceStore.pageOperationLogs(limit, offset);
            } catch (RuntimeException ignored) {
                // 与 persistentOperationLogs 一致：审计镜像自身不可用时不再写审计日志，避免递归。
            }
        }
        return PageSlice.of(
            operationLogs.values().stream()
                .sorted(Comparator.comparing(OperationLogItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
    }

    private Optional<List<OperationLogItem>> persistentOperationLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(auditPersistenceStore.listOperationLogs());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ 短信日志

    /** 分配 id、落内存、镜像 DB，并把落定的日志返回给调用方。 */
    SmsLogItem appendSmsLog(
        String orderNo,
        String mobile,
        String templateType,
        String content,
        String status,
        String errorMessage,
        OffsetDateTime createdAt
    ) {
        Long id = smsLogId.getAndIncrement();
        SmsLogItem log = new SmsLogItem(id, orderNo, mobile, templateType, content, status, errorMessage, createdAt);
        smsLogs.put(id, log);
        persistSmsLog(log);
        return log;
    }

    boolean hasSmsLog(String orderNo, String templateType) {
        return smsLogs.values().stream()
            .anyMatch(log -> Objects.equals(log.orderNo(), orderNo) && Objects.equals(log.templateType(), templateType));
    }

    void removeSmsLogsByOrder(String orderNo) {
        smsLogs.entrySet().removeIf(entry -> Objects.equals(entry.getValue().orderNo(), orderNo));
    }

    List<SmsLogItem> listSmsLogs() {
        Optional<List<SmsLogItem>> persistent = persistentSmsLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return smsLogs.values().stream()
            .sorted(Comparator.comparing(SmsLogItem::createdAt).reversed())
            .toList();
    }

    /** 批次8C：短信日志分页。降级语义与 {@link #listSmsLogs} 一致，含读失败时的 PERSISTENCE_READ_FALLBACK 审计。 */
    PageSlice<SmsLogItem> pageSmsLogs(int limit, long offset) {
        if (auditPersistenceStore != null) {
            try {
                return auditPersistenceStore.pageSmsLogs(limit, offset);
            } catch (RuntimeException ex) {
                appendOperation("PERSISTENCE_READ_FALLBACK", "SMS_LOG", "LIST", persistenceErrorMessage(ex));
            }
        }
        return PageSlice.of(
            smsLogs.values().stream()
                .sorted(Comparator.comparing(SmsLogItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
    }

    private void persistSmsLog(SmsLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveSmsLog(log);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "SMS_LOG", String.valueOf(log.id()), persistenceErrorMessage(ex));
        }
    }

    private Optional<List<SmsLogItem>> persistentSmsLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(auditPersistenceStore.listSmsLogs());
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "SMS_LOG", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    // -------------------------------------------------------- 开放接口日志

    void appendOpenApiLog(Long userId, String appKey, String path, String status, String message) {
        Long id = openApiLogId.getAndIncrement();
        OpenApiLogItem log = new OpenApiLogItem(
            id,
            userId,
            appKey == null ? "" : appKey,
            path == null ? "" : path,
            status,
            message,
            OffsetDateTime.now()
        );
        openApiLogs.put(id, log);
        persistOpenApiLog(log);
    }

    List<OpenApiLogItem> listOpenApiLogs() {
        Optional<List<OpenApiLogItem>> persistent = persistentOpenApiLogs();
        if (persistent.isPresent()) {
            return persistent.get();
        }
        return openApiLogs.values().stream()
            .sorted(Comparator.comparing(OpenApiLogItem::createdAt).reversed())
            .toList();
    }

    /**
     * 批次8C：开放接口日志分页。降级语义与 {@link #listOpenApiLogs} 一致。
     *
     * <p>只服务<b>展示</b>路径。日限额统计仍走 {@link #allOpenApiLogSnapshots}，
     * 那里需要内存与库的全量并集，语义上不能替换成分页。
     */
    PageSlice<OpenApiLogItem> pageOpenApiLogs(int limit, long offset) {
        if (auditPersistenceStore != null) {
            try {
                return auditPersistenceStore.pageOpenApiLogs(limit, offset);
            } catch (RuntimeException ex) {
                appendOperation("PERSISTENCE_READ_FALLBACK", "OPEN_API_LOG", "LIST", persistenceErrorMessage(ex));
            }
        }
        return PageSlice.of(
            openApiLogs.values().stream()
                .sorted(Comparator.comparing(OpenApiLogItem::createdAt).reversed())
                .toList(),
            limit,
            offset
        );
    }

    /** 内存 + 持久化两份并集，用于开放接口日限额统计（语义与批次6 之前一致）。 */
    List<OpenApiLogItem> allOpenApiLogSnapshots() {
        Map<Long, OpenApiLogItem> snapshots = new LinkedHashMap<>();
        persistentOpenApiLogs().ifPresent(items -> items.forEach(item -> snapshots.put(item.id(), item)));
        openApiLogs.values().forEach(item -> snapshots.put(item.id(), item));
        return List.copyOf(snapshots.values());
    }

    private void persistOpenApiLog(OpenApiLogItem log) {
        if (auditPersistenceStore == null || log == null) {
            return;
        }
        try {
            auditPersistenceStore.saveOpenApiLog(log);
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_MIRROR_FAILED", "OPEN_API_LOG", String.valueOf(log.id()), persistenceErrorMessage(ex));
        }
    }

    private Optional<List<OpenApiLogItem>> persistentOpenApiLogs() {
        if (auditPersistenceStore == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(auditPersistenceStore.listOpenApiLogs());
        } catch (RuntimeException ex) {
            appendOperation("PERSISTENCE_READ_FALLBACK", "OPEN_API_LOG", "LIST", persistenceErrorMessage(ex));
            return Optional.empty();
        }
    }

    private String persistenceErrorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
