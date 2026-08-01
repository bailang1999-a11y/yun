package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.persistence.entity.AdminStaffRecordEntity;
import com.xiyiyun.shop.persistence.entity.AdminSuperCredentialRecordEntity;
import com.xiyiyun.shop.persistence.entity.CaptchaSettingRecordEntity;
import com.xiyiyun.shop.persistence.entity.MemberApiCredentialRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentChannelRecordEntity;
import com.xiyiyun.shop.persistence.entity.PriceTemplateRecordEntity;
import com.xiyiyun.shop.persistence.entity.ProductMonitorStateRecordEntity;
import com.xiyiyun.shop.persistence.entity.SmsLoginSettingRecordEntity;
import com.xiyiyun.shop.persistence.entity.UserCredentialRecordEntity;
import com.xiyiyun.shop.persistence.mapper.AdminStaffRecordMapper;
import com.xiyiyun.shop.persistence.mapper.AdminSuperCredentialRecordMapper;
import com.xiyiyun.shop.persistence.mapper.CaptchaSettingRecordMapper;
import com.xiyiyun.shop.persistence.mapper.MemberApiCredentialRecordMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentChannelRecordMapper;
import com.xiyiyun.shop.persistence.mapper.PriceTemplateRecordMapper;
import com.xiyiyun.shop.persistence.mapper.ProductMonitorStateRecordMapper;
import com.xiyiyun.shop.persistence.mapper.SmsLoginSettingRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserCredentialRecordMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批次7 / 任务A：007 迁移新建的 8 张配置表的读写出口。
 *
 * <h2>为什么不塞进 ConfigPersistenceStore</h2>
 * {@link ConfigPersistenceStore} 已经背了 7 个 mapper。再加 8 个就是又一个上帝类。
 * 本类只负责「新表的行级读写」，不含任何业务判断：JSON ↔ 领域对象的转换留在
 * {@code ConfigService}，那里才有默认值、裁剪范围、审计等语义。
 *
 * <h2>整表替换语义</h2>
 * 价格模板 / 支付渠道 / 后台员工原本是「一个 JSON 数组整体重写」。切成行表后
 * 单纯 upsert 会让被删除的元素永久残留（员工残留意味着已删账号还能登录）。
 * 所以这三张表提供 {@code replace*} 方法：一个事务里 upsert 全量 + 删除不在快照里的行。
 */
@Service
public class ConfigTableStore {
    private final UserCredentialRecordMapper userCredentialRecordMapper;
    private final AdminStaffRecordMapper adminStaffRecordMapper;
    private final AdminSuperCredentialRecordMapper adminSuperCredentialRecordMapper;
    private final PaymentChannelRecordMapper paymentChannelRecordMapper;
    private final PriceTemplateRecordMapper priceTemplateRecordMapper;
    private final SmsLoginSettingRecordMapper smsLoginSettingRecordMapper;
    private final CaptchaSettingRecordMapper captchaSettingRecordMapper;
    private final MemberApiCredentialRecordMapper memberApiCredentialRecordMapper;
    private final ProductMonitorStateRecordMapper productMonitorStateRecordMapper;

    public ConfigTableStore(
        UserCredentialRecordMapper userCredentialRecordMapper,
        AdminStaffRecordMapper adminStaffRecordMapper,
        AdminSuperCredentialRecordMapper adminSuperCredentialRecordMapper,
        PaymentChannelRecordMapper paymentChannelRecordMapper,
        PriceTemplateRecordMapper priceTemplateRecordMapper,
        SmsLoginSettingRecordMapper smsLoginSettingRecordMapper,
        CaptchaSettingRecordMapper captchaSettingRecordMapper,
        MemberApiCredentialRecordMapper memberApiCredentialRecordMapper,
        ProductMonitorStateRecordMapper productMonitorStateRecordMapper
    ) {
        this.userCredentialRecordMapper = userCredentialRecordMapper;
        this.adminStaffRecordMapper = adminStaffRecordMapper;
        this.adminSuperCredentialRecordMapper = adminSuperCredentialRecordMapper;
        this.paymentChannelRecordMapper = paymentChannelRecordMapper;
        this.priceTemplateRecordMapper = priceTemplateRecordMapper;
        this.smsLoginSettingRecordMapper = smsLoginSettingRecordMapper;
        this.captchaSettingRecordMapper = captchaSettingRecordMapper;
        this.memberApiCredentialRecordMapper = memberApiCredentialRecordMapper;
        this.productMonitorStateRecordMapper = productMonitorStateRecordMapper;
    }

    // ------------------------------------------------------------ 会员口令

    @Transactional(readOnly = true)
    public Optional<String> userPasswordHash(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        UserCredentialRecordEntity record = userCredentialRecordMapper.selectByUserId(userId);
        return record == null ? Optional.empty() : Optional.ofNullable(record.getPasswordHash());
    }

    @Transactional
    public void saveUserPasswordHash(Long userId, String passwordHash) {
        userCredentialRecordMapper.upsertPasswordHash(userId, passwordHash);
    }

    // ------------------------------------------------------------ 后台员工

    @Transactional(readOnly = true)
    public List<AdminStaffRecordEntity> listAdminStaff() {
        return adminStaffRecordMapper.selectAllSnapshots();
    }

    @Transactional
    public void replaceAdminStaff(List<AdminStaffRecordEntity> snapshot) {
        List<AdminStaffRecordEntity> items = snapshot == null ? List.of() : snapshot;
        items.forEach(adminStaffRecordMapper::upsertSnapshot);
        adminStaffRecordMapper.deleteMissing(items.stream().map(AdminStaffRecordEntity::getId).toList());
    }

    // -------------------------------------------------------------- 超管

    @Transactional(readOnly = true)
    public Optional<AdminSuperCredentialRecordEntity> superAdminCredential() {
        return Optional.ofNullable(adminSuperCredentialRecordMapper.selectSingleton());
    }

    @Transactional
    public void saveSuperAdminCredential(String username, String nickname, String passwordHash) {
        adminSuperCredentialRecordMapper.upsertSingleton(username, nickname, passwordHash);
    }

    // ------------------------------------------------------------ 支付渠道

    @Transactional(readOnly = true)
    public List<PaymentChannelRecordEntity> listPaymentChannels() {
        return paymentChannelRecordMapper.selectAllSnapshots();
    }

    @Transactional
    public void replacePaymentChannels(List<PaymentChannelRecordEntity> snapshot) {
        List<PaymentChannelRecordEntity> items = snapshot == null ? List.of() : snapshot;
        items.forEach(paymentChannelRecordMapper::upsertSnapshot);
        paymentChannelRecordMapper.deleteMissing(items.stream().map(PaymentChannelRecordEntity::getId).toList());
    }

    // ------------------------------------------------------------ 价格模板

    @Transactional(readOnly = true)
    public List<PriceTemplateRecordEntity> listPriceTemplates() {
        return priceTemplateRecordMapper.selectAllSnapshots();
    }

    @Transactional
    public void replacePriceTemplates(List<PriceTemplateRecordEntity> snapshot) {
        List<PriceTemplateRecordEntity> items = snapshot == null ? List.of() : snapshot;
        items.forEach(priceTemplateRecordMapper::upsertSnapshot);
        priceTemplateRecordMapper.deleteMissing(items.stream().map(PriceTemplateRecordEntity::getTemplateId).toList());
    }

    // -------------------------------------------------- 短信登录 / 验证码配置

    @Transactional(readOnly = true)
    public Optional<SmsLoginSettingRecordEntity> smsLoginSetting() {
        return Optional.ofNullable(smsLoginSettingRecordMapper.selectSingleton());
    }

    @Transactional
    public void saveSmsLoginSetting(SmsLoginSettingRecordEntity entity) {
        smsLoginSettingRecordMapper.upsertSingleton(entity);
    }

    @Transactional(readOnly = true)
    public Optional<CaptchaSettingRecordEntity> captchaSetting() {
        return Optional.ofNullable(captchaSettingRecordMapper.selectSingleton());
    }

    @Transactional
    public void saveCaptchaSetting(CaptchaSettingRecordEntity entity) {
        captchaSettingRecordMapper.upsertSingleton(entity);
    }

    // ------------------------------------------------------ 会员 API 凭据

    @Transactional(readOnly = true)
    public Optional<MemberApiCredentialRecordEntity> memberCredential(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(memberApiCredentialRecordMapper.selectByUserId(userId));
    }

    @Transactional(readOnly = true)
    public Optional<MemberApiCredentialRecordEntity> memberCredentialByAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(memberApiCredentialRecordMapper.selectByAppKey(appKey.trim()));
    }

    @Transactional
    public void saveMemberCredential(MemberApiCredentialRecordEntity entity) {
        memberApiCredentialRecordMapper.upsertSnapshot(entity);
    }

    // ------------------------------------------------------ 商品监控状态

    @Transactional(readOnly = true)
    public List<ProductMonitorStateRecordEntity> listProductMonitorStates() {
        return productMonitorStateRecordMapper.selectAllSnapshots();
    }

    @Transactional
    public void saveProductMonitorState(ProductMonitorStateRecordEntity entity) {
        productMonitorStateRecordMapper.upsertSnapshot(entity);
    }

    /** 真删除。批次6 只能写空串墓碑，因为 KV mapper 没有 DELETE。 */
    @Transactional
    public void deleteProductMonitorState(Long channelId) {
        productMonitorStateRecordMapper.deleteByChannelId(channelId);
    }
}
