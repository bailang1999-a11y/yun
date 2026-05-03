package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.CardKindItem;
import com.xiyiyun.shop.mvp.GoodsChannelItem;
import com.xiyiyun.shop.mvp.GroupRuleItem;
import com.xiyiyun.shop.mvp.RechargeFieldItem;
import com.xiyiyun.shop.mvp.SupplierItem;
import com.xiyiyun.shop.mvp.SystemSettingItem;
import com.xiyiyun.shop.mvp.UserGroupItem;
import com.xiyiyun.shop.persistence.entity.CardKindRecordEntity;
import com.xiyiyun.shop.persistence.entity.GoodsChannelRecordEntity;
import com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity;
import com.xiyiyun.shop.persistence.entity.RechargeFieldRecordEntity;
import com.xiyiyun.shop.persistence.entity.SupplierRecordEntity;
import com.xiyiyun.shop.persistence.entity.SystemSettingRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserGroupRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CardKindRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GoodsChannelRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GroupRuleRecordMapper;
import com.xiyiyun.shop.persistence.mapper.RechargeFieldRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SupplierRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SystemSettingRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserGroupRecordMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigPersistenceStore {
    private final CardKindRecordMapper cardKindRecordMapper;
    private final RechargeFieldRecordMapper rechargeFieldRecordMapper;
    private final SupplierRecordMapper supplierRecordMapper;
    private final GoodsChannelRecordMapper goodsChannelRecordMapper;
    private final UserGroupRecordMapper userGroupRecordMapper;
    private final GroupRuleRecordMapper groupRuleRecordMapper;
    private final SystemSettingRecordMapper systemSettingRecordMapper;

    public ConfigPersistenceStore(
        CardKindRecordMapper cardKindRecordMapper,
        RechargeFieldRecordMapper rechargeFieldRecordMapper,
        SupplierRecordMapper supplierRecordMapper,
        GoodsChannelRecordMapper goodsChannelRecordMapper,
        UserGroupRecordMapper userGroupRecordMapper,
        GroupRuleRecordMapper groupRuleRecordMapper,
        SystemSettingRecordMapper systemSettingRecordMapper
    ) {
        this.cardKindRecordMapper = cardKindRecordMapper;
        this.rechargeFieldRecordMapper = rechargeFieldRecordMapper;
        this.supplierRecordMapper = supplierRecordMapper;
        this.goodsChannelRecordMapper = goodsChannelRecordMapper;
        this.userGroupRecordMapper = userGroupRecordMapper;
        this.groupRuleRecordMapper = groupRuleRecordMapper;
        this.systemSettingRecordMapper = systemSettingRecordMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, String> systemSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        systemSettingRecordMapper.selectAllSettings().forEach(item -> settings.put(item.getSettingKey(), item.getSettingValue()));
        return settings;
    }

    @Transactional
    public void saveSystemSetting(SystemSettingItem item) {
        if (item == null) {
            return;
        }
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("siteName", item.siteName());
        settings.put("logoUrl", item.logoUrl());
        settings.put("customerService", item.customerService());
        settings.put("companyName", item.companyName());
        settings.put("icpRecordNo", item.icpRecordNo());
        settings.put("policeRecordNo", item.policeRecordNo());
        settings.put("disclaimer", item.disclaimer());
        settings.put("paymentMode", item.paymentMode());
        settings.put("autoRefundEnabled", String.valueOf(item.autoRefundEnabled()));
        settings.put("smsProvider", item.smsProvider());
        settings.put("smsEnabled", String.valueOf(item.smsEnabled()));
        settings.put("upstreamSyncSeconds", String.valueOf(item.upstreamSyncSeconds()));
        settings.put("autoShelfEnabled", String.valueOf(item.autoShelfEnabled()));
        settings.put("autoPriceEnabled", String.valueOf(item.autoPriceEnabled()));
        settings.put("registrationEnabled", String.valueOf(item.registrationEnabled()));
        settings.put("registrationType", item.registrationType());
        settings.put("defaultUserGroupId", String.valueOf(item.defaultUserGroupId()));
        settings.put("notification.ops", item.notificationReceivers() == null ? "" : item.notificationReceivers().getOrDefault("ops", ""));
        settings.forEach((key, value) -> systemSettingRecordMapper.upsertSetting(toSystemSettingRecord(key, value)));
    }

    @Transactional
    public void saveCardKind(CardKindItem item) {
        cardKindRecordMapper.upsertSnapshot(toCardKindRecord(item));
    }

    @Transactional(readOnly = true)
    public List<CardKindItem> listCardKinds() {
        return cardKindRecordMapper.selectActiveSnapshots().stream()
            .map(this::toCardKindItem)
            .toList();
    }

    @Transactional
    public void saveRechargeField(RechargeFieldItem item) {
        rechargeFieldRecordMapper.upsertSnapshot(toRechargeFieldRecord(item));
    }

    @Transactional
    public void deleteRechargeField(Long id) {
        rechargeFieldRecordMapper.softDelete(id);
    }

    @Transactional(readOnly = true)
    public List<RechargeFieldItem> listRechargeFields() {
        return rechargeFieldRecordMapper.selectActiveSnapshots().stream()
            .map(this::toRechargeFieldItem)
            .toList();
    }

    @Transactional
    public void saveSupplier(SupplierItem item) {
        supplierRecordMapper.upsertSnapshot(toSupplierRecord(item));
    }

    @Transactional
    public void deleteSupplier(Long id) {
        supplierRecordMapper.softDelete(id);
        goodsChannelRecordMapper.softDeleteBySupplier(id);
    }

    @Transactional(readOnly = true)
    public List<SupplierItem> listSuppliers() {
        return supplierRecordMapper.selectActiveSnapshots().stream()
            .map(this::toSupplierItem)
            .toList();
    }

    @Transactional
    public void saveGoodsChannel(GoodsChannelItem item) {
        goodsChannelRecordMapper.upsertSnapshot(toGoodsChannelRecord(item));
    }

    @Transactional
    public void deleteGoodsChannel(Long id) {
        goodsChannelRecordMapper.softDelete(id);
    }

    @Transactional(readOnly = true)
    public List<GoodsChannelItem> listGoodsChannels() {
        return goodsChannelRecordMapper.selectActiveSnapshots().stream()
            .map(this::toGoodsChannelItem)
            .toList();
    }

    @Transactional
    public void saveUserGroup(UserGroupItem item) {
        userGroupRecordMapper.upsertSnapshot(toUserGroupRecord(item));
    }

    @Transactional
    public void replaceGroupRules(Long groupId, String ruleType, List<GroupRuleItem> rules) {
        groupRuleRecordMapper.deleteByGroupAndType(groupId, ruleType);
        rules.forEach(rule -> groupRuleRecordMapper.upsertSnapshot(toGroupRuleRecord(rule)));
    }

    @Transactional(readOnly = true)
    public List<UserGroupItem> listUserGroups() {
        List<GroupRuleItem> rules = listGroupRules();
        return userGroupRecordMapper.selectActiveSnapshots().stream()
            .map(record -> toUserGroupItem(record, rules.stream()
                .filter(rule -> record.getId().equals(rule.groupId()))
                .toList()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<GroupRuleItem> listGroupRules() {
        return groupRuleRecordMapper.selectActiveSnapshots().stream()
            .map(this::toGroupRuleItem)
            .toList();
    }

    private SystemSettingRecordEntity toSystemSettingRecord(String key, String value) {
        SystemSettingRecordEntity entity = new SystemSettingRecordEntity();
        entity.setSettingKey(key);
        entity.setSettingValue(value == null ? "" : value);
        return entity;
    }

    private CardKindRecordEntity toCardKindRecord(CardKindItem item) {
        CardKindRecordEntity entity = new CardKindRecordEntity();
        entity.setId(item.id());
        entity.setName(item.name());
        entity.setType(item.type());
        entity.setCost(item.cost() == null ? BigDecimal.ZERO : item.cost());
        return entity;
    }

    private CardKindItem toCardKindItem(CardKindRecordEntity entity) {
        return new CardKindItem(entity.getId(), entity.getName(), entity.getType(), entity.getCost());
    }

    private RechargeFieldRecordEntity toRechargeFieldRecord(RechargeFieldItem item) {
        RechargeFieldRecordEntity entity = new RechargeFieldRecordEntity();
        entity.setId(item.id());
        entity.setCode(item.code());
        entity.setLabel(item.label());
        entity.setPlaceholder(item.placeholder());
        entity.setHelpText(item.helpText());
        entity.setInputType(item.inputType());
        entity.setRequired(Boolean.TRUE.equals(item.required()));
        entity.setSort(item.sort());
        entity.setEnabled(Boolean.TRUE.equals(item.enabled()));
        entity.setCreatedAt(item.createdAt());
        entity.setUpdatedAt(item.updatedAt());
        return entity;
    }

    private RechargeFieldItem toRechargeFieldItem(RechargeFieldRecordEntity entity) {
        return new RechargeFieldItem(
            entity.getId(), entity.getCode(), entity.getLabel(), entity.getPlaceholder(),
            entity.getHelpText(), entity.getInputType(), entity.getRequired(), entity.getSort(),
            entity.getEnabled(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private SupplierRecordEntity toSupplierRecord(SupplierItem item) {
        SupplierRecordEntity entity = new SupplierRecordEntity();
        entity.setId(item.id());
        entity.setName(item.name());
        entity.setPlatformType(item.platformType());
        entity.setBaseUrl(item.baseUrl());
        entity.setAppKey(item.appKey());
        entity.setAppSecretMasked(item.appSecretMasked());
        entity.setUserId(item.userId());
        entity.setAppId(item.appId());
        entity.setApiKeyMasked(item.apiKeyMasked());
        entity.setCallbackUrl(item.callbackUrl());
        entity.setTimeoutSeconds(item.timeoutSeconds());
        entity.setBalance(item.balance() == null ? BigDecimal.ZERO : item.balance());
        entity.setStatus(item.status());
        entity.setRemark(item.remark());
        entity.setLastSyncAt(item.lastSyncAt());
        return entity;
    }

    private SupplierItem toSupplierItem(SupplierRecordEntity entity) {
        return new SupplierItem(
            entity.getId(), entity.getName(), entity.getPlatformType(), entity.getBaseUrl(),
            entity.getAppKey(), entity.getAppSecretMasked(), entity.getUserId(), entity.getAppId(),
            entity.getApiKeyMasked(), entity.getCallbackUrl(), entity.getTimeoutSeconds(),
            entity.getBalance(), entity.getStatus(), entity.getRemark(), entity.getLastSyncAt()
        );
    }

    private GoodsChannelRecordEntity toGoodsChannelRecord(GoodsChannelItem item) {
        GoodsChannelRecordEntity entity = new GoodsChannelRecordEntity();
        entity.setId(item.id());
        entity.setGoodsId(item.goodsId());
        entity.setSupplierId(item.supplierId());
        entity.setSupplierName(item.supplierName());
        entity.setSupplierGoodsId(item.supplierGoodsId());
        entity.setPriority(item.priority());
        entity.setTimeoutSeconds(item.timeoutSeconds());
        entity.setStatus(item.status());
        entity.setCreatedAt(item.createdAt());
        return entity;
    }

    private GoodsChannelItem toGoodsChannelItem(GoodsChannelRecordEntity entity) {
        return new GoodsChannelItem(
            entity.getId(), entity.getGoodsId(), entity.getSupplierId(), entity.getSupplierName(),
            entity.getSupplierGoodsId(), entity.getPriority(), entity.getTimeoutSeconds(),
            entity.getStatus(), entity.getCreatedAt()
        );
    }

    private UserGroupRecordEntity toUserGroupRecord(UserGroupItem item) {
        UserGroupRecordEntity entity = new UserGroupRecordEntity();
        entity.setId(item.id());
        entity.setName(item.name());
        entity.setDescription(item.description());
        entity.setDefaultGroup(item.defaultGroup());
        entity.setStatus(item.status());
        entity.setOrderEnabled(item.orderEnabled());
        entity.setRealNameRequiredForOrder(item.realNameRequiredForOrder());
        return entity;
    }

    private UserGroupItem toUserGroupItem(UserGroupRecordEntity entity, List<GroupRuleItem> rules) {
        return new UserGroupItem(
            entity.getId(), entity.getName(), entity.getDescription(), Boolean.TRUE.equals(entity.getDefaultGroup()),
            0, entity.getStatus(), entity.getOrderEnabled() == null || entity.getOrderEnabled(),
            Boolean.TRUE.equals(entity.getRealNameRequiredForOrder()), rules
        );
    }

    private GroupRuleRecordEntity toGroupRuleRecord(GroupRuleItem item) {
        GroupRuleRecordEntity entity = new GroupRuleRecordEntity();
        entity.setGroupId(item.groupId());
        entity.setRuleType(item.ruleType());
        entity.setTargetId(item.targetId());
        entity.setTargetCode(item.targetCode());
        entity.setPermission(item.permission());
        return entity;
    }

    private GroupRuleItem toGroupRuleItem(GroupRuleRecordEntity entity) {
        return new GroupRuleItem(
            entity.getGroupId(), entity.getRuleType(), entity.getTargetId(), entity.getTargetCode(), "", entity.getPermission()
        );
    }
}
