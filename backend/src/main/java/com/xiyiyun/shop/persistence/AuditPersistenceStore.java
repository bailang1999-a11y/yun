package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.OpenApiLogItem;
import com.xiyiyun.shop.mvp.OperationLogItem;
import com.xiyiyun.shop.mvp.SmsLogItem;
import com.xiyiyun.shop.persistence.entity.OpenApiLogRecordEntity;
import com.xiyiyun.shop.persistence.entity.OperationLogRecordEntity;
import com.xiyiyun.shop.persistence.entity.SmsLogRecordEntity;
import com.xiyiyun.shop.persistence.mapper.OpenApiLogRecordMapper;
import com.xiyiyun.shop.persistence.mapper.OperationLogRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SmsLogRecordMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditPersistenceStore {
    private final OperationLogRecordMapper operationLogRecordMapper;
    private final SmsLogRecordMapper smsLogRecordMapper;
    private final OpenApiLogRecordMapper openApiLogRecordMapper;

    public AuditPersistenceStore(
        OperationLogRecordMapper operationLogRecordMapper,
        SmsLogRecordMapper smsLogRecordMapper,
        OpenApiLogRecordMapper openApiLogRecordMapper
    ) {
        this.operationLogRecordMapper = operationLogRecordMapper;
        this.smsLogRecordMapper = smsLogRecordMapper;
        this.openApiLogRecordMapper = openApiLogRecordMapper;
    }

    @Transactional
    public void saveOperationLog(OperationLogItem item) {
        operationLogRecordMapper.insertSnapshot(toEntity(item));
    }

    @Transactional
    public void saveSmsLog(SmsLogItem item) {
        smsLogRecordMapper.insertSnapshot(toEntity(item));
    }

    @Transactional
    public void saveOpenApiLog(OpenApiLogItem item) {
        openApiLogRecordMapper.insertSnapshot(toEntity(item));
    }

    @Transactional(readOnly = true)
    public List<OperationLogItem> listOperationLogs() {
        return operationLogRecordMapper.selectSnapshots().stream().map(this::toItem).toList();
    }

    @Transactional(readOnly = true)
    public List<SmsLogItem> listSmsLogs() {
        return smsLogRecordMapper.selectSnapshots().stream().map(this::toItem).toList();
    }

    @Transactional(readOnly = true)
    public List<OpenApiLogItem> listOpenApiLogs() {
        return openApiLogRecordMapper.selectSnapshots().stream().map(this::toItem).toList();
    }

    private OperationLogRecordEntity toEntity(OperationLogItem item) {
        OperationLogRecordEntity entity = new OperationLogRecordEntity();
        entity.setId(item.id());
        entity.setAdminName(item.operator());
        entity.setAction(item.action());
        entity.setResourceType(item.resourceType());
        entity.setResourceId(item.resourceId());
        entity.setAfterData(item.remark());
        entity.setCreatedAt(item.createdAt());
        return entity;
    }

    private SmsLogRecordEntity toEntity(SmsLogItem item) {
        SmsLogRecordEntity entity = new SmsLogRecordEntity();
        entity.setId(item.id());
        entity.setOrderNo(item.orderNo());
        entity.setMobile(item.mobile());
        entity.setTemplateType(item.templateType());
        entity.setContent(item.content());
        entity.setStatus(item.status());
        entity.setErrorMessage(item.errorMessage());
        entity.setCreatedAt(item.createdAt());
        return entity;
    }

    private OpenApiLogRecordEntity toEntity(OpenApiLogItem item) {
        OpenApiLogRecordEntity entity = new OpenApiLogRecordEntity();
        entity.setId(item.id());
        entity.setUserId(item.userId());
        entity.setAppKey(item.appKey());
        entity.setPath(item.path());
        entity.setStatus(item.status());
        entity.setMessage(item.message());
        entity.setCreatedAt(item.createdAt());
        return entity;
    }

    private OperationLogItem toItem(OperationLogRecordEntity entity) {
        return new OperationLogItem(entity.getId(), entity.getAdminName(), entity.getAction(), entity.getResourceType(), entity.getResourceId(), entity.getAfterData(), entity.getCreatedAt());
    }

    private SmsLogItem toItem(SmsLogRecordEntity entity) {
        return new SmsLogItem(entity.getId(), entity.getOrderNo(), entity.getMobile(), entity.getTemplateType(), entity.getContent(), entity.getStatus(), entity.getErrorMessage(), entity.getCreatedAt());
    }

    private OpenApiLogItem toItem(OpenApiLogRecordEntity entity) {
        return new OpenApiLogItem(entity.getId(), entity.getUserId(), entity.getAppKey(), entity.getPath(), entity.getStatus(), entity.getMessage(), entity.getCreatedAt());
    }
}
