package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.mvp.GoodsChannelItem;
import com.xiyiyun.shop.mvp.GroupRuleItem;
import com.xiyiyun.shop.mvp.RechargeFieldItem;
import com.xiyiyun.shop.mvp.SupplierItem;
import com.xiyiyun.shop.mvp.UserGroupItem;
import com.xiyiyun.shop.persistence.entity.GoodsChannelRecordEntity;
import com.xiyiyun.shop.persistence.entity.RechargeFieldRecordEntity;
import com.xiyiyun.shop.persistence.entity.SupplierRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserGroupRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CardKindRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GoodsChannelRecordMapper;
import com.xiyiyun.shop.persistence.mapper.GroupRuleRecordMapper;
import com.xiyiyun.shop.persistence.mapper.RechargeFieldRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SupplierRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SystemSettingRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserGroupRecordMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigPersistenceStoreTest {
    private final CardKindRecordMapper cardKindRecordMapper = mock(CardKindRecordMapper.class);
    private final RechargeFieldRecordMapper rechargeFieldRecordMapper = mock(RechargeFieldRecordMapper.class);
    private final SupplierRecordMapper supplierRecordMapper = mock(SupplierRecordMapper.class);
    private final GoodsChannelRecordMapper goodsChannelRecordMapper = mock(GoodsChannelRecordMapper.class);
    private final UserGroupRecordMapper userGroupRecordMapper = mock(UserGroupRecordMapper.class);
    private final GroupRuleRecordMapper groupRuleRecordMapper = mock(GroupRuleRecordMapper.class);
    private final SystemSettingRecordMapper systemSettingRecordMapper = mock(SystemSettingRecordMapper.class);
    private final ConfigPersistenceStore store = new ConfigPersistenceStore(
        cardKindRecordMapper,
        rechargeFieldRecordMapper,
        supplierRecordMapper,
        goodsChannelRecordMapper,
        userGroupRecordMapper,
        groupRuleRecordMapper,
        systemSettingRecordMapper
    );

    @Test
    void saveSupplierMirrorsSensitiveFieldsAsMaskedOnly() {
        SupplierItem supplier = new SupplierItem(
            9L,
            "上游A",
            "KASUSHOU_2",
            "https://supplier.example.com",
            "app-key",
            "se***et",
            "user-1",
            "app-1",
            "ak***ey",
            "https://callback.example.com",
            25,
            new BigDecimal("12.34"),
            "ENABLED",
            "主供应商",
            OffsetDateTime.parse("2026-05-02T10:00:00+08:00")
        );

        store.saveSupplier(supplier);

        ArgumentCaptor<SupplierRecordEntity> captor = ArgumentCaptor.forClass(SupplierRecordEntity.class);
        verify(supplierRecordMapper).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(9L);
        assertThat(captor.getValue().getAppSecretMasked()).isEqualTo("se***et");
        assertThat(captor.getValue().getApiKeyMasked()).isEqualTo("ak***ey");
        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("12.34");
    }

    @Test
    void listRechargeFieldsMapsSortAndRequiredAliases() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        RechargeFieldRecordEntity field = new RechargeFieldRecordEntity();
        field.setId(3L);
        field.setCode("account");
        field.setLabel("充值账号");
        field.setPlaceholder("请输入账号");
        field.setHelpText("请核对");
        field.setInputType("text");
        field.setRequired(true);
        field.setSort(20);
        field.setEnabled(true);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        when(rechargeFieldRecordMapper.selectActiveSnapshots()).thenReturn(List.of(field));

        List<RechargeFieldItem> fields = store.listRechargeFields();

        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).code()).isEqualTo("account");
        assertThat(fields.get(0).required()).isTrue();
        assertThat(fields.get(0).sort()).isEqualTo(20);
    }

    @Test
    void deleteSupplierAlsoDeletesBoundChannels() {
        store.deleteSupplier(8L);

        verify(supplierRecordMapper).softDelete(8L);
        verify(goodsChannelRecordMapper).softDeleteBySupplier(8L);
    }

    @Test
    void replaceGroupRulesDeletesOldTypeBeforeInsertingNewRules() {
        GroupRuleItem categoryRule = new GroupRuleItem(2L, "CATEGORY", 100L, null, "视频会员", "ALLOW");

        store.replaceGroupRules(2L, "CATEGORY", List.of(categoryRule));

        verify(groupRuleRecordMapper).deleteByGroupAndType(2L, "CATEGORY");
        ArgumentCaptor<com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity> captor =
            ArgumentCaptor.forClass(com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity.class);
        verify(groupRuleRecordMapper).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getGroupId()).isEqualTo(2L);
        assertThat(captor.getValue().getRuleType()).isEqualTo("CATEGORY");
        assertThat(captor.getValue().getTargetId()).isEqualTo(100L);
        assertThat(captor.getValue().getPermission()).isEqualTo("ALLOW");
    }

    @Test
    void listUserGroupsIncludesPersistedRules() {
        UserGroupRecordEntity group = new UserGroupRecordEntity();
        group.setId(4L);
        group.setName("批发客户");
        group.setDescription("批量采购");
        group.setDefaultGroup(false);
        group.setStatus("ENABLED");
        group.setOrderEnabled(false);
        group.setRealNameRequiredForOrder(true);
        com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity rule =
            new com.xiyiyun.shop.persistence.entity.GroupRuleRecordEntity();
        rule.setGroupId(4L);
        rule.setRuleType("PLATFORM");
        rule.setTargetCode("h5");
        rule.setPermission("DENY");
        when(userGroupRecordMapper.selectActiveSnapshots()).thenReturn(List.of(group));
        when(groupRuleRecordMapper.selectActiveSnapshots()).thenReturn(List.of(rule));

        List<UserGroupItem> groups = store.listUserGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).orderEnabled()).isFalse();
        assertThat(groups.get(0).realNameRequiredForOrder()).isTrue();
        assertThat(groups.get(0).rules()).hasSize(1);
        assertThat(groups.get(0).rules().get(0).permission()).isEqualTo("DENY");
    }

    @Test
    void listGoodsChannelsMapsProcurementPriority() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        GoodsChannelRecordEntity channel = new GoodsChannelRecordEntity();
        channel.setId(30L);
        channel.setGoodsId(100L);
        channel.setSupplierId(9L);
        channel.setSupplierName("上游A");
        channel.setSupplierGoodsId("remote-1");
        channel.setPriority(5);
        channel.setTimeoutSeconds(45);
        channel.setStatus("ENABLED");
        channel.setCreatedAt(now);
        when(goodsChannelRecordMapper.selectActiveSnapshots()).thenReturn(List.of(channel));

        List<GoodsChannelItem> channels = store.listGoodsChannels();

        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).goodsId()).isEqualTo(100L);
        assertThat(channels.get(0).priority()).isEqualTo(5);
        assertThat(channels.get(0).timeoutSeconds()).isEqualTo(45);
    }
}
