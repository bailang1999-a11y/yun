package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigTableStore;
import com.xiyiyun.shop.persistence.entity.AdminStaffRecordEntity;
import com.xiyiyun.shop.persistence.entity.AdminSuperCredentialRecordEntity;
import com.xiyiyun.shop.persistence.entity.CaptchaSettingRecordEntity;
import com.xiyiyun.shop.persistence.entity.MemberApiCredentialRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentChannelRecordEntity;
import com.xiyiyun.shop.persistence.entity.PriceTemplateRecordEntity;
import com.xiyiyun.shop.persistence.entity.ProductMonitorStateRecordEntity;
import com.xiyiyun.shop.persistence.entity.SmsLoginSettingRecordEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;

/**
 * 批次6 / 任务C：系统配置读写的唯一出口。
 *
 * <h2>为什么单独一层</h2>
 * 原先 {@code InMemoryShopRepository} 直接持有 {@link ConfigPersistenceStore}，
 * 并在 30 多处散落地拼 {@code configPersistenceStore.systemSettings().get("xxx")}。
 * 配置项的 key、序列化格式、落库失败如何审计，全部混在一万行的仓储里。
 * 本类把这些收口，仓储只调「有语义的方法」，不再关心配置存在哪、以什么形状存。
 *
 * <h2>为批次7 留的余地</h2>
 * 批次7 要把 9 个挤在 {@code system_settings} KV/JSON 里的字段迁到独立表。
 * 因此本类<b>不暴露</b>泛化的 {@code get(key)} 给业务侧当主接口，而是为每个字段提供
 * <b>具名方法</b>（如 {@link #priceTemplatesJson()} / {@link #savePriceTemplatesJson(String)}）。
 * 迁移时只需改本类里对应那一对方法的实现（换成新表的 mapper），
 * 调用方一行都不用动，也不会有人再从 KV 里偷读同一份数据。
 * {@link #rawSetting(String)} / {@link #saveRawSetting(String, String)} 仍然保留，
 * 但仅供本类内部与「确实还没迁走的杂项」使用。
 *
 * <h2>批次7 / 任务A：已迁到独立表</h2>
 * 上面的余地按计划用掉了。9 个字段现在读写 007 新建的表（{@link ConfigTableStore}），
 * 具名方法的<b>签名和返回的 JSON 形状一字未改</b>，所以仓储与其它调用方一行都没动：
 * 本类在边界上做「表行 ↔ 原 JSON」的翻译。
 *
 * <h3>回退策略（只在新表整体没有该键时回退一次）</h3>
 * 读取顺序是「先新表，miss 才读老 KV」，但「miss」的判定分两种粒度：
 * <ul>
 *   <li><b>按行的键</b>（会员口令、会员 API 凭据、超管）：该行不存在才回退。
 *       行不存在 ⇒ 既没被 007 搬过、切换后也没写过，老 KV 是唯一副本；
 *       行存在 ⇒ 新表就是权威，绝不再看 KV。这样「改过的口令被老哈希顶回去」不可能发生。</li>
 *   <li><b>整表的键</b>（价格模板、支付渠道、后台员工）：语义单位是整个列表，
 *       「空表」既可能是没迁过、也可能是管理员真的删空了。靠 007 写入的
 *       {@link #CONFIG_TABLES_CUTOVER_KEY} 标记区分：标记存在即新表权威，空就是真的空，
 *       否则才回退读 KV。没有这个标记的话，管理员删完最后一个员工后，
 *       重启会从老 KV 把已删账号连口令一起复活——那是能登录的僵尸账号。</li>
 * </ul>
 * 商品监控状态不做按渠道回退：007 已把全部老状态搬过来，
 * 再按行回退只会让「删掉的渠道」反复复活。
 */
public class ConfigService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 批次7 待迁移字段的 key —— 全部集中在这里，迁移时一眼能数清。 */
    static final String PRICE_TEMPLATE_SETTING_KEY = "price.templates";
    static final String PAYMENT_CHANNEL_SETTING_KEY = "payment.channels";
    static final String ADMIN_STAFF_SETTING_KEY = "admin.staff.accounts";
    static final String SMS_LOGIN_SETTING_KEY = "sms.login.setting";
    static final String CAPTCHA_SETTING_KEY = "captcha.setting";
    static final String SUPER_ADMIN_USERNAME_KEY = "admin.super.username";
    static final String SUPER_ADMIN_PASSWORD_KEY = "admin.super.passwordHash";
    static final String SUPER_ADMIN_NICKNAME_KEY = "admin.super.nickname";
    private static final String USER_PASSWORD_KEY_PREFIX = "user.password.";
    private static final String MEMBER_CREDENTIAL_KEY_PREFIX = "member.credential.";
    private static final String OUTBOUND_PROTOCOL_SETTING_KEY = "outbound.protocol.settings";
    private static final String DEFAULT_PUBLIC_API_BASE_URL = "https://api.xiyi.co";
    /** 批次6 新增：商品监控状态。见 {@link #productMonitorStates()}。 */
    private static final String PRODUCT_MONITOR_STATE_KEY_PREFIX = "product.monitor.state.";
    /**
     * 007 迁移写入的切换标记。存在即表示「新表已是权威」。
     *
     * <p>它本身留在 system_settings 里是故意的：这是「迁移是否跑过」的事实，
     * 不属于任何业务表；而且 001 建库时也会写上，让全新库与升级库行为一致。
     */
    static final String CONFIG_TABLES_CUTOVER_KEY = "config.tables.cutover";

    private final ConfigPersistenceStore configPersistenceStore;
    private final AuditService auditService;
    /** 注册方式归一化，逻辑属于会员域，由仓储以方法引用注入。 */
    private final UnaryOperator<String> registrationTypeNormalizer;
    /** 默认会员组校验，需要读会员组数据，由仓储以方法引用注入。 */
    private final Function<Long, Long> defaultUserGroupValidator;

    private volatile SystemSettingItem systemSetting;

    ConfigService(
        ConfigPersistenceStore configPersistenceStore,
        AuditService auditService,
        SystemSettingItem defaultSystemSetting,
        UnaryOperator<String> registrationTypeNormalizer,
        Function<Long, Long> defaultUserGroupValidator
    ) {
        this.configPersistenceStore = configPersistenceStore;
        this.auditService = auditService;
        this.systemSetting = defaultSystemSetting;
        this.registrationTypeNormalizer = registrationTypeNormalizer;
        this.defaultUserGroupValidator = defaultUserGroupValidator;
    }

    boolean persistenceEnabled() {
        return configPersistenceStore != null;
    }

    // ---------------------------------------------------------------- 站点设置

    SystemSettingItem systemSetting() {
        return systemSetting;
    }

    synchronized SystemSettingItem updateSystemSetting(UpdateSystemSettingRequest request) {
        if (request == null) {
            return systemSetting;
        }
        systemSetting = new SystemSettingItem(
            defaultText(request.siteName(), systemSetting.siteName()),
            defaultText(request.logoUrl(), systemSetting.logoUrl()),
            defaultText(request.customerService(), systemSetting.customerService()),
            defaultText(request.companyName(), systemSetting.companyName()),
            defaultText(request.icpRecordNo(), systemSetting.icpRecordNo()),
            defaultText(request.policeRecordNo(), systemSetting.policeRecordNo()),
            defaultText(request.disclaimer(), systemSetting.disclaimer()),
            defaultText(request.paymentMode(), systemSetting.paymentMode()),
            request.autoRefundEnabled() == null ? systemSetting.autoRefundEnabled() : request.autoRefundEnabled(),
            defaultText(request.smsProvider(), systemSetting.smsProvider()),
            request.smsEnabled() == null ? systemSetting.smsEnabled() : request.smsEnabled(),
            Math.max(5, request.upstreamSyncSeconds() == null ? systemSetting.upstreamSyncSeconds() : request.upstreamSyncSeconds()),
            request.autoShelfEnabled() == null ? systemSetting.autoShelfEnabled() : request.autoShelfEnabled(),
            request.autoPriceEnabled() == null ? systemSetting.autoPriceEnabled() : request.autoPriceEnabled(),
            request.registrationEnabled() == null ? systemSetting.registrationEnabled() : request.registrationEnabled(),
            registrationTypeNormalizer.apply(defaultText(request.registrationType(), systemSetting.registrationType())),
            defaultUserGroupValidator.apply(request.defaultUserGroupId() == null ? systemSetting.defaultUserGroupId() : request.defaultUserGroupId()),
            request.notificationReceivers() == null ? systemSetting.notificationReceivers() : Map.copyOf(request.notificationReceivers()),
            request.wecomRobot() == null ? systemSetting.wecomRobot() : request.wecomRobot().validated()
        );
        persistSystemSetting(systemSetting);
        return systemSetting;
    }

    /** 启动时把库里的站点设置盖到内存默认值上；读失败只记审计，不阻断启动（语义与批次6 之前一致）。 */
    void loadPersistentSystemSetting() {
        if (configPersistenceStore == null) {
            return;
        }
        try {
            Map<String, String> settings = configPersistenceStore.systemSettings();
            if (settings.isEmpty()) {
                return;
            }
            systemSetting = new SystemSettingItem(
                defaultText(settings.get("siteName"), systemSetting.siteName()),
                defaultText(settings.get("logoUrl"), systemSetting.logoUrl()),
                defaultText(settings.get("customerService"), systemSetting.customerService()),
                defaultText(settings.get("companyName"), systemSetting.companyName()),
                defaultText(settings.get("icpRecordNo"), systemSetting.icpRecordNo()),
                defaultText(settings.get("policeRecordNo"), systemSetting.policeRecordNo()),
                defaultText(settings.get("disclaimer"), systemSetting.disclaimer()),
                defaultText(settings.get("paymentMode"), systemSetting.paymentMode()),
                booleanSetting(settings, "autoRefundEnabled", systemSetting.autoRefundEnabled()),
                defaultText(settings.get("smsProvider"), systemSetting.smsProvider()),
                booleanSetting(settings, "smsEnabled", systemSetting.smsEnabled()),
                intSetting(settings, "upstreamSyncSeconds", systemSetting.upstreamSyncSeconds(), 5),
                booleanSetting(settings, "autoShelfEnabled", systemSetting.autoShelfEnabled()),
                booleanSetting(settings, "autoPriceEnabled", systemSetting.autoPriceEnabled()),
                booleanSetting(settings, "registrationEnabled", systemSetting.registrationEnabled()),
                registrationTypeNormalizer.apply(defaultText(settings.get("registrationType"), systemSetting.registrationType())),
                longSetting(settings, "defaultUserGroupId", systemSetting.defaultUserGroupId()),
                Map.of("ops", defaultText(settings.get("notification.ops"), systemSetting.notificationReceivers().getOrDefault("ops", ""))),
                loadWeComRobotSetting(settings)
            );
        } catch (RuntimeException ex) {
            auditService.appendOperation("PERSISTENCE_READ_FALLBACK", "SYSTEM_SETTING", "GLOBAL", persistenceErrorMessage(ex));
        }
    }

    private WeComRobotSetting loadWeComRobotSetting(Map<String, String> settings) {
        String ciphertext = defaultText(settings.get("wecom.robot.webhook.ciphertext"), "");
        String nonce = defaultText(settings.get("wecom.robot.webhook.nonce"), "");
        String keyVersion = defaultText(settings.get("wecom.robot.webhook.keyVersion"), "");
        String webhookUrl = "";
        if (!ciphertext.isEmpty() && !nonce.isEmpty() && !keyVersion.isEmpty()) {
            webhookUrl = configPersistenceStore.decryptSecretFromSetting(ciphertext, nonce, keyVersion);
        }
        List<WeComNotificationEvent> events = new ArrayList<>();
        for (String value : defaultText(settings.get("wecom.robot.events"), "").split(",")) {
            try {
                if (!value.isBlank()) events.add(WeComNotificationEvent.valueOf(value.trim()));
            } catch (IllegalArgumentException ignored) {
                // Ignore values from newer versions during rollback or mixed-version startup.
            }
        }
        return new WeComRobotSetting(
            booleanSetting(settings, "wecom.robot.enabled", false), webhookUrl, events
        ).validated();
    }

    private void persistSystemSetting(SystemSettingItem item) {
        if (configPersistenceStore == null || item == null) {
            return;
        }
        try {
            configPersistenceStore.saveSystemSetting(item);
        } catch (RuntimeException ex) {
            auditService.appendOperation("PERSISTENCE_MIRROR_FAILED", "SYSTEM_SETTING", "GLOBAL", persistenceErrorMessage(ex));
            throw ex;
        }
    }

    // -------------------------------------- 批次7 已迁移字段（读写 007 新表）

    /** 新表是否已是权威数据源。见类注释「回退策略」。 */
    private boolean tablesAuthoritative() {
        return StringUtils.hasText(rawSetting(CONFIG_TABLES_CUTOVER_KEY));
    }

    private ConfigTableStore tables() {
        return configPersistenceStore == null ? null : configPersistenceStore.configTables();
    }

    String priceTemplatesJson() {
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(PRICE_TEMPLATE_SETTING_KEY);
        }
        List<PriceTemplateRecordEntity> rows = store.listPriceTemplates();
        if (rows.isEmpty() && !tablesAuthoritative()) {
            return rawSetting(PRICE_TEMPLATE_SETTING_KEY);
        }
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        rows.forEach(row -> {
            ObjectNode node = array.addObject();
            node.put("id", row.getTemplateId());
            node.put("name", row.getName());
            node.put("adjustMode", row.getAdjustMode());
            node.put("referencePrice", row.getReferencePrice());
            node.set("groupRates", readJsonArray(row.getGroupRates()));
            node.put("enabled", !Boolean.FALSE.equals(row.getEnabled()));
        });
        return array.toString();
    }

    void savePriceTemplatesJson(String json) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(PRICE_TEMPLATE_SETTING_KEY, json);
            return;
        }
        List<PriceTemplateRecordEntity> rows = new ArrayList<>();
        JsonNode array = readJsonArray(json);
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            if (!item.isObject() || !StringUtils.hasText(item.path("id").asText(""))) {
                continue;
            }
            PriceTemplateRecordEntity row = new PriceTemplateRecordEntity();
            row.setTemplateId(item.path("id").asText());
            row.setName(item.path("name").asText(""));
            row.setAdjustMode(defaultText(item.path("adjustMode").asText(""), "PERCENT"));
            row.setReferencePrice(decimalValue(item.path("referencePrice")));
            row.setGroupRates(jsonTextOrNull(item.get("groupRates")));
            row.setEnabled(!item.has("enabled") || item.path("enabled").asBoolean(true));
            // 顺序靠 sort_no 保留：JSON 数组的下标本身就是唯一的顺序信息，别丢。
            row.setSortNo((index + 1) * 10);
            rows.add(row);
        }
        mirrorToTable("PRICE_TEMPLATE", "LIST", () -> store.replacePriceTemplates(rows));
    }

    String paymentChannelsJson() {
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(PAYMENT_CHANNEL_SETTING_KEY);
        }
        List<PaymentChannelRecordEntity> rows = store.listPaymentChannels();
        if (rows.isEmpty() && !tablesAuthoritative()) {
            return rawSetting(PAYMENT_CHANNEL_SETTING_KEY);
        }
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        rows.forEach(row -> {
            ObjectNode node = array.addObject();
            node.put("id", row.getId());
            node.put("code", row.getCode());
            node.put("name", row.getName());
            node.put("type", row.getChannelType());
            node.set("terminals", readJsonArray(row.getTerminals()));
            node.put("status", row.getStatus());
            node.put("sort", row.getSortNo());
            node.set("config", readJsonObject(row.getConfigPublic()));
            putIfPresent(node, "configSecrets", row.getConfigSecrets());
            node.put("remark", defaultText(row.getRemark(), ""));
            node.put("createdAt", timestampText(row.getCreatedAt()));
            node.put("updatedAt", timestampText(row.getUpdatedAt()));
        });
        return array.toString();
    }

    void savePaymentChannelsJson(String json) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(PAYMENT_CHANNEL_SETTING_KEY, json);
            return;
        }
        List<PaymentChannelRecordEntity> rows = new ArrayList<>();
        for (JsonNode item : readJsonArray(json)) {
            if (!item.isObject() || !item.hasNonNull("id")) {
                continue;
            }
            PaymentChannelRecordEntity row = new PaymentChannelRecordEntity();
            row.setId(item.path("id").asLong());
            row.setCode(item.path("code").asText(""));
            row.setName(item.path("name").asText(""));
            row.setChannelType(item.path("type").asText(""));
            row.setTerminals(jsonTextOrNull(item.get("terminals")));
            row.setStatus(defaultText(item.path("status").asText(""), "ENABLED"));
            row.setSortNo(item.path("sort").asInt(0));
            row.setConfigPublic(jsonTextOrNull(item.get("config")));
            row.setConfigSecrets(jsonTextOrNull(item.get("configSecrets")));
            row.setRemark(item.path("remark").asText(""));
            row.setCreatedAt(parseTimestamp(item.path("createdAt").asText("")));
            rows.add(row);
        }
        mirrorToTable("PAYMENT_CHANNEL", "LIST", () -> store.replacePaymentChannels(rows));
    }

    String adminStaffJson() {
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(ADMIN_STAFF_SETTING_KEY);
        }
        List<AdminStaffRecordEntity> rows = store.listAdminStaff();
        if (rows.isEmpty() && !tablesAuthoritative()) {
            return rawSetting(ADMIN_STAFF_SETTING_KEY);
        }
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        rows.forEach(row -> {
            ObjectNode node = array.addObject();
            node.put("id", row.getId());
            node.put("account", row.getAccount());
            node.put("nickname", defaultText(row.getNickname(), ""));
            node.put("status", row.getStatus());
            node.set("permissions", readJsonArray(row.getPermissions()));
            node.put("createdAt", timestampText(row.getCreatedAt()));
            node.put("updatedAt", timestampText(row.getUpdatedAt()));
            node.put("passwordHash", row.getPasswordHash());
        });
        return array.toString();
    }

    void saveAdminStaffJson(String json) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(ADMIN_STAFF_SETTING_KEY, json);
            return;
        }
        List<AdminStaffRecordEntity> rows = new ArrayList<>();
        for (JsonNode item : readJsonArray(json)) {
            // password_hash 是 NOT NULL：没口令的条目本来就被 loadAdminStaff 过滤掉，
            // 这里同样跳过，避免因为脏数据整批写失败。
            if (!item.isObject() || !item.hasNonNull("id")
                || !StringUtils.hasText(item.path("account").asText(""))
                || !StringUtils.hasText(item.path("passwordHash").asText(""))) {
                continue;
            }
            AdminStaffRecordEntity row = new AdminStaffRecordEntity();
            row.setId(item.path("id").asLong());
            row.setAccount(item.path("account").asText());
            row.setNickname(item.path("nickname").asText(""));
            row.setStatus(defaultText(item.path("status").asText(""), "ENABLED"));
            row.setPermissions(jsonTextOrNull(item.get("permissions")));
            row.setPasswordHash(item.path("passwordHash").asText());
            row.setCreatedAt(parseTimestamp(item.path("createdAt").asText("")));
            rows.add(row);
        }
        mirrorToTable("ADMIN_STAFF", "LIST", () -> store.replaceAdminStaff(rows));
    }

    String smsLoginSettingJson() {
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(SMS_LOGIN_SETTING_KEY);
        }
        Optional<SmsLoginSettingRecordEntity> row = store.smsLoginSetting();
        if (row.isEmpty()) {
            return rawSetting(SMS_LOGIN_SETTING_KEY);
        }
        SmsLoginSettingRecordEntity entity = row.get();
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
        node.put("adminLoginEnabled", Boolean.TRUE.equals(entity.getAdminLoginEnabled()));
        node.put("h5LoginEnabled", Boolean.TRUE.equals(entity.getH5LoginEnabled()));
        node.put("webLoginEnabled", Boolean.TRUE.equals(entity.getWebLoginEnabled()));
        node.put("provider", entity.getProvider());
        node.put("adminMobile", defaultText(entity.getAdminMobile(), ""));
        node.put("codeLength", entity.getCodeLength());
        node.put("ttlSeconds", entity.getTtlSeconds());
        node.put("cooldownSeconds", entity.getCooldownSeconds());
        node.put("maxAttempts", entity.getMaxAttempts());
        node.set("genericConfig", readJsonObject(entity.getGenericConfigPublic()));
        putIfPresent(node, "genericConfigSecrets", entity.getGenericConfigSecrets());
        node.set("tencentConfig", readJsonObject(entity.getTencentConfigPublic()));
        putIfPresent(node, "tencentConfigSecrets", entity.getTencentConfigSecrets());
        node.set("aliyunConfig", readJsonObject(entity.getAliyunConfigPublic()));
        putIfPresent(node, "aliyunConfigSecrets", entity.getAliyunConfigSecrets());
        return node.toString();
    }

    void saveSmsLoginSettingJson(String json) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(SMS_LOGIN_SETTING_KEY, json);
            return;
        }
        JsonNode payload = readJsonObject(json);
        SmsLoginSettingRecordEntity entity = new SmsLoginSettingRecordEntity();
        entity.setId(1);
        entity.setEnabled(payload.path("enabled").asBoolean(false));
        entity.setAdminLoginEnabled(payload.path("adminLoginEnabled").asBoolean(false));
        entity.setH5LoginEnabled(payload.path("h5LoginEnabled").asBoolean(false));
        entity.setWebLoginEnabled(payload.path("webLoginEnabled").asBoolean(false));
        entity.setProvider(defaultText(payload.path("provider").asText(""), "MOCK"));
        entity.setAdminMobile(payload.path("adminMobile").asText(""));
        entity.setCodeLength(payload.path("codeLength").asInt(6));
        entity.setTtlSeconds(payload.path("ttlSeconds").asInt(300));
        entity.setCooldownSeconds(payload.path("cooldownSeconds").asInt(60));
        entity.setMaxAttempts(payload.path("maxAttempts").asInt(5));
        entity.setGenericConfigPublic(jsonTextOrNull(payload.get("genericConfig")));
        entity.setGenericConfigSecrets(jsonTextOrNull(payload.get("genericConfigSecrets")));
        entity.setTencentConfigPublic(jsonTextOrNull(payload.get("tencentConfig")));
        entity.setTencentConfigSecrets(jsonTextOrNull(payload.get("tencentConfigSecrets")));
        entity.setAliyunConfigPublic(jsonTextOrNull(payload.get("aliyunConfig")));
        entity.setAliyunConfigSecrets(jsonTextOrNull(payload.get("aliyunConfigSecrets")));
        mirrorToTable("SMS_LOGIN_SETTING", "GLOBAL", () -> store.saveSmsLoginSetting(entity));
    }

    String captchaSettingJson() {
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(CAPTCHA_SETTING_KEY);
        }
        Optional<CaptchaSettingRecordEntity> row = store.captchaSetting();
        if (row.isEmpty()) {
            return rawSetting(CAPTCHA_SETTING_KEY);
        }
        CaptchaSettingRecordEntity entity = row.get();
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("enabled", Boolean.TRUE.equals(entity.getEnabled()));
        node.put("adminLoginEnabled", Boolean.TRUE.equals(entity.getAdminLoginEnabled()));
        node.put("h5LoginEnabled", Boolean.TRUE.equals(entity.getH5LoginEnabled()));
        node.put("webLoginEnabled", Boolean.TRUE.equals(entity.getWebLoginEnabled()));
        node.put("provider", entity.getProvider());
        node.set("tencentConfig", readJsonObject(entity.getTencentConfigPublic()));
        putIfPresent(node, "tencentConfigSecrets", entity.getTencentConfigSecrets());
        node.set("turnstileConfig", readJsonObject(entity.getTurnstileConfigPublic()));
        putIfPresent(node, "turnstileConfigSecrets", entity.getTurnstileConfigSecrets());
        node.set("genericConfig", readJsonObject(entity.getGenericConfigPublic()));
        putIfPresent(node, "genericConfigSecrets", entity.getGenericConfigSecrets());
        // altchaConfig 复用 genericConfig 列（两者互斥，不会同时配置）
        node.set("altchaConfig", readJsonObject(entity.getGenericConfigPublic()));
        // 密文列同样要复用：hmac_key 命中敏感键判定后只存在于 secrets 列，
        // 漏掉这一行会让密钥永远读不回来，表现为「已保存却提示未配置」。
        putIfPresent(node, "altchaConfigSecrets", entity.getGenericConfigSecrets());
        return node.toString();
    }

    void saveCaptchaSettingJson(String json) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(CAPTCHA_SETTING_KEY, json);
            return;
        }
        JsonNode payload = readJsonObject(json);
        CaptchaSettingRecordEntity entity = new CaptchaSettingRecordEntity();
        entity.setId(1);
        entity.setEnabled(payload.path("enabled").asBoolean(false));
        entity.setAdminLoginEnabled(payload.path("adminLoginEnabled").asBoolean(false));
        entity.setH5LoginEnabled(payload.path("h5LoginEnabled").asBoolean(false));
        entity.setWebLoginEnabled(payload.path("webLoginEnabled").asBoolean(false));
        entity.setProvider(defaultText(payload.path("provider").asText(""), "TENCENT"));
        entity.setTencentConfigPublic(jsonTextOrNull(payload.get("tencentConfig")));
        entity.setTencentConfigSecrets(jsonTextOrNull(payload.get("tencentConfigSecrets")));
        entity.setTurnstileConfigPublic(jsonTextOrNull(payload.get("turnstileConfig")));
        entity.setTurnstileConfigSecrets(jsonTextOrNull(payload.get("turnstileConfigSecrets")));
        entity.setGenericConfigPublic(jsonTextOrNull(payload.get("genericConfig")));
        entity.setGenericConfigSecrets(jsonTextOrNull(payload.get("genericConfigSecrets")));
        // altchaConfig 存入 genericConfig 列（两者互斥，选了 ALTCHA 就不会配 GENERIC）
        if (payload.has("altchaConfig") && payload.get("altchaConfig") != null && !payload.get("altchaConfig").isNull()) {
            entity.setGenericConfigPublic(jsonTextOrNull(payload.get("altchaConfig")));
        }
        // hmac_key 是敏感键，加密后只出现在 altchaConfigSecrets 里；不落这一列，
        // 保存动作会静默丢弃密钥（public 列写成 {}，secrets 列保持 NULL）。
        if (payload.has("altchaConfigSecrets") && payload.get("altchaConfigSecrets") != null
            && !payload.get("altchaConfigSecrets").isNull()) {
            entity.setGenericConfigSecrets(jsonTextOrNull(payload.get("altchaConfigSecrets")));
        }
        mirrorToTable("CAPTCHA_SETTING", "GLOBAL", () -> store.saveCaptchaSetting(entity));
    }

    /**
     * 会员登录口令哈希。
     *
     * <p>按行回退：新表没有该用户的行，才读老 KV（见类注释）。新表有行就一律以它为准，
     * 因此「用户改过口令后被老哈希顶回去」这种最危险的回退不可能发生。
     */
    String userPasswordHash(Long userId) {
        if (userId == null) {
            return null;
        }
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(USER_PASSWORD_KEY_PREFIX + userId);
        }
        return store.userPasswordHash(userId)
            .filter(StringUtils::hasText)
            .orElseGet(() -> rawSetting(USER_PASSWORD_KEY_PREFIX + userId));
    }

    void saveUserPasswordHash(Long userId, String hash) {
        if (userId == null) {
            return;
        }
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(USER_PASSWORD_KEY_PREFIX + userId, hash);
            return;
        }
        mirrorToTable("USER_PASSWORD", String.valueOf(userId), () -> store.saveUserPasswordHash(userId, hash));
    }

    String memberCredentialJson(Long userId) {
        if (userId == null) {
            return null;
        }
        ConfigTableStore store = tables();
        if (store == null) {
            return rawSetting(MEMBER_CREDENTIAL_KEY_PREFIX + userId);
        }
        Optional<MemberApiCredentialRecordEntity> row = store.memberCredential(userId);
        if (row.isEmpty()) {
            return rawSetting(MEMBER_CREDENTIAL_KEY_PREFIX + userId);
        }
        return memberCredentialJson(row.get());
    }

    String memberCredentialJsonByAppKey(String appKey) {
        ConfigTableStore store = tables();
        if (store == null || !StringUtils.hasText(appKey)) {
            return null;
        }
        return store.memberCredentialByAppKey(appKey.trim()).map(this::memberCredentialJson).orElse(null);
    }

    private String memberCredentialJson(MemberApiCredentialRecordEntity entity) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("id", entity.getId());
        node.put("userId", entity.getUserId());
        node.put("appKey", entity.getAppKey());
        node.put("appSecretMasked", defaultText(entity.getAppSecretMasked(), ""));
        if (StringUtils.hasText(entity.getAppSecretCiphertext())) {
            node.put("appSecretCiphertext", entity.getAppSecretCiphertext());
            node.put("appSecretNonce", entity.getAppSecretNonce());
            node.put("appSecretKeyVersion", entity.getAppSecretKeyVersion());
            node.put("appSecretHash", entity.getAppSecretHash());
        }
        node.put("callbackUrl", defaultText(entity.getCallbackUrl(), ""));
        node.put("status", entity.getStatus());
        node.set("ipWhitelist", readJsonArray(entity.getIpWhitelist()));
        node.put("dailyLimit", entity.getDailyLimit());
        node.put("createdAt", timestampText(entity.getCreatedAt()));
        node.put("lastUsedAt", timestampText(entity.getLastUsedAt()));
        return node.toString();
    }

    String outboundProtocolSettingsJson() {
        return rawSetting(OUTBOUND_PROTOCOL_SETTING_KEY);
    }

    String outboundPublicBaseUrl() {
        String configured = readJsonObject(outboundProtocolSettingsJson()).path("baseUrl").asText("").trim();
        return StringUtils.hasText(configured) ? configured : DEFAULT_PUBLIC_API_BASE_URL;
    }

    void saveOutboundProtocolSettingsJson(String json) {
        saveRawSetting(OUTBOUND_PROTOCOL_SETTING_KEY, json);
    }

    void saveMemberCredentialJson(Long userId, String json) {
        if (userId == null) {
            return;
        }
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(MEMBER_CREDENTIAL_KEY_PREFIX + userId, json);
            return;
        }
        JsonNode payload = readJsonObject(json);
        MemberApiCredentialRecordEntity entity = new MemberApiCredentialRecordEntity();
        // 主键交给自增：KV 里的 id 由内存序列派生，不同用户可能重复，不能当身份。
        entity.setUserId(userId);
        entity.setAppKey(payload.path("appKey").asText(""));
        entity.setAppSecretMasked(payload.path("appSecretMasked").asText(""));
        entity.setAppSecretCiphertext(textOrNull(payload.path("appSecretCiphertext").asText("")));
        entity.setAppSecretNonce(textOrNull(payload.path("appSecretNonce").asText("")));
        entity.setAppSecretKeyVersion(textOrNull(payload.path("appSecretKeyVersion").asText("")));
        entity.setAppSecretHash(textOrNull(payload.path("appSecretHash").asText("")));
        entity.setCallbackUrl(textOrNull(payload.path("callbackUrl").asText("")));
        entity.setStatus(defaultText(payload.path("status").asText(""), "DISABLED"));
        entity.setIpWhitelist(jsonTextOrNull(payload.get("ipWhitelist")));
        entity.setDailyLimit(payload.path("dailyLimit").asInt(1000));
        entity.setCreatedAt(parseTimestamp(payload.path("createdAt").asText("")));
        entity.setLastUsedAt(parseTimestamp(payload.path("lastUsedAt").asText("")));
        mirrorToTable("MEMBER_API", String.valueOf(userId), () -> store.saveMemberCredential(entity));
    }

    /** 超管凭据一次读出，避免仓储再拼 key。 */
    Map<String, String> superAdminCredentials() {
        ConfigTableStore store = tables();
        Optional<AdminSuperCredentialRecordEntity> row = store == null
            ? Optional.empty()
            : store.superAdminCredential();
        Map<String, String> credentials = new LinkedHashMap<>();
        if (row.isPresent()) {
            AdminSuperCredentialRecordEntity entity = row.get();
            credentials.put("username", defaultText(entity.getUsername(), ""));
            credentials.put("passwordHash", defaultText(entity.getPasswordHash(), ""));
            credentials.put("nickname", defaultText(entity.getNickname(), ""));
            return credentials;
        }
        Map<String, String> all = rawSettings();
        credentials.put("username", defaultText(all.get(SUPER_ADMIN_USERNAME_KEY), ""));
        credentials.put("passwordHash", defaultText(all.get(SUPER_ADMIN_PASSWORD_KEY), ""));
        credentials.put("nickname", defaultText(all.get(SUPER_ADMIN_NICKNAME_KEY), ""));
        return credentials;
    }

    void saveSuperAdminCredentials(String username, String nickname, String passwordHash) {
        ConfigTableStore store = tables();
        if (store == null) {
            saveRawSetting(SUPER_ADMIN_USERNAME_KEY, username);
            saveRawSetting(SUPER_ADMIN_NICKNAME_KEY, nickname);
            saveRawSetting(SUPER_ADMIN_PASSWORD_KEY, passwordHash);
            return;
        }
        // 三行 KV 变成一行三列：改名和改口令不再可能只成功一半。
        mirrorToTable("SUPER_ADMIN", "CREDENTIALS", () -> store.saveSuperAdminCredential(username, nickname, passwordHash));
    }

    // --------------------------------------------------------- 商品监控状态

    /**
     * 读出所有渠道的监控状态（批次6 / 任务A 的缺陷修复）。
     *
     * <p>原实现 {@code productMonitorStates} 是纯内存 Map，重启即丢 {@code nextScanAt}，
     * 所有渠道会在启动瞬间同时判定「到期」并一起打上游。这里把状态落库，重启后按原计划继续。
     *
     * <p><b>批次7 已迁走</b>：批次6 因「不许新增迁移」只能塞进 {@code system_settings}，
     * 现在落在独立表 {@code product_monitor_states}（007 建表并搬迁了全部老状态）。
     * 不做按渠道回退老 KV：007 已经整体搬完，再逐行回退只会把删掉的渠道反复复活。
     */
    Map<Long, ProductMonitorState> productMonitorStates() {
        Map<Long, ProductMonitorState> states = new LinkedHashMap<>();
        ConfigTableStore store = tables();
        if (store == null) {
            return states;
        }
        try {
            store.listProductMonitorStates().forEach(row -> {
                if (row.getChannelId() == null) {
                    return;
                }
                states.put(row.getChannelId(), new ProductMonitorState(
                    row.getChannelId(),
                    row.getLastScanAt(),
                    row.getNextScanAt(),
                    defaultText(row.getLastResult(), "WAITING"),
                    defaultText(row.getLastMessage(), "等待首次扫描"),
                    row.getScanCount() == null ? 0 : row.getScanCount(),
                    row.getChangeCount() == null ? 0 : row.getChangeCount(),
                    // scanning 一律恢复为 false：进程已经换了，上一轮"正在扫描"不可能还在跑。
                    // 若原样恢复 true，该渠道会永远不再被判定到期，等于监控静默停摆。
                    // 表里也故意没有这一列——不存在能被错误恢复的持久化 scanning。
                    false
                ));
            });
        } catch (RuntimeException ex) {
            auditService.appendOperation("PERSISTENCE_READ_FALLBACK", "PRODUCT_MONITOR_STATE", "LIST", persistenceErrorMessage(ex));
        }
        return states;
    }

    void saveProductMonitorState(ProductMonitorState state) {
        ConfigTableStore store = tables();
        if (store == null || state == null || state.channelId() == null) {
            return;
        }
        try {
            ProductMonitorStateRecordEntity entity = new ProductMonitorStateRecordEntity();
            entity.setChannelId(state.channelId());
            entity.setLastScanAt(state.lastScanAt());
            entity.setNextScanAt(state.nextScanAt());
            entity.setLastResult(state.lastResult());
            entity.setLastMessage(state.lastMessage());
            entity.setScanCount(state.scanCount());
            entity.setChangeCount(state.changeCount());
            store.saveProductMonitorState(entity);
        } catch (RuntimeException ex) {
            // 监控状态镜像失败不该打断扫描本身，只记审计。
            auditService.appendOperation(
                "PERSISTENCE_MIRROR_FAILED",
                "PRODUCT_MONITOR_STATE",
                String.valueOf(state.channelId()),
                persistenceErrorMessage(ex)
            );
        }
    }

    /**
     * 渠道被删除时清空其监控状态。
     *
     * <p>批次6 只能写空串当墓碑（{@code SystemSettingRecordMapper} 没有 DELETE，
     * 那批次也不许加迁移）。迁到独立表后这里是<b>真删除</b>，不再留残行。
     */
    void forgetProductMonitorState(Long channelId) {
        ConfigTableStore store = tables();
        if (store == null || channelId == null) {
            return;
        }
        try {
            store.deleteProductMonitorState(channelId);
        } catch (RuntimeException ex) {
            auditService.appendOperation(
                "PERSISTENCE_MIRROR_FAILED",
                "PRODUCT_MONITOR_STATE",
                String.valueOf(channelId),
                persistenceErrorMessage(ex)
            );
        }
    }

    // ------------------------------------------------------------- 通用 KV

    String rawSetting(String key) {
        if (configPersistenceStore == null || !StringUtils.hasText(key)) {
            return null;
        }
        return configPersistenceStore.systemSettings().get(key);
    }

    Map<String, String> rawSettings() {
        if (configPersistenceStore == null) {
            return Map.of();
        }
        return configPersistenceStore.systemSettings();
    }

    void saveRawSetting(String key, String value) {
        if (configPersistenceStore == null || !StringUtils.hasText(key)) {
            return;
        }
        try {
            configPersistenceStore.saveRuntimeSetting(key, value);
        } catch (RuntimeException ex) {
            auditService.appendOperation("PERSISTENCE_MIRROR_FAILED", "SETTING", key, persistenceErrorMessage(ex));
            throw ex;
        }
    }

    Map<String, String> encryptSecretForSetting(String secret) {
        return configPersistenceStore.encryptSecretForSetting(secret);
    }

    String decryptSecretFromSetting(String ciphertext, String nonce) {
        return configPersistenceStore.decryptSecretFromSetting(ciphertext, nonce);
    }

    // ------------------------------------------ 表行 ↔ JSON 的翻译辅助（批次7）

    /**
     * 新表写入失败的统一处理：记审计后原样抛出。
     *
     * <p>沿用批次6 的约定——配置镜像失败必须让调用方看到（{@code PERSISTENCE_MIRROR_FAILED}
     * 之后 rethrow），否则管理员会以为改动保存成功了。监控状态是唯一的例外，
     * 它在自己的方法里吞掉异常，不走这里。
     */
    private void mirrorToTable(String resourceType, String resourceId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            auditService.appendOperation("PERSISTENCE_MIRROR_FAILED", resourceType, resourceId, persistenceErrorMessage(ex));
            throw ex;
        }
    }

    /** JSON 列读出来是文本；解析失败或为空时给空数组，读侧绝不因脏数据抛异常。 */
    private JsonNode readJsonArray(String raw) {
        if (!StringUtils.hasText(raw)) {
            return OBJECT_MAPPER.createArrayNode();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            return node.isArray() ? node : OBJECT_MAPPER.createArrayNode();
        } catch (JsonProcessingException ex) {
            return OBJECT_MAPPER.createArrayNode();
        }
    }

    private ObjectNode readJsonObject(String raw) {
        if (!StringUtils.hasText(raw)) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            return node instanceof ObjectNode object ? object : OBJECT_MAPPER.createObjectNode();
        } catch (JsonProcessingException ex) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /** 只有真的有内容才挂到 JSON 上：空的 {@code xxxSecrets} 键会让读侧误判「有密文但不完整」。 */
    private void putIfPresent(ObjectNode node, String field, String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(rawJson);
            if (parsed.isObject() && !parsed.isEmpty()) {
                node.set(field, parsed);
            }
        } catch (JsonProcessingException ex) {
            // 脏数据不挂，读侧按「没有密文」处理，好过整条配置读不出来。
        }
    }

    /** 写 JSON 列：空对象/空数组也照写，只有 null 才是 NULL；空字符串会被 MySQL 判为非法 JSON。 */
    private String jsonTextOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private BigDecimal decimalValue(JsonNode node) {
        if (node == null || !node.isNumber() && !StringUtils.hasText(node.asText(""))) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText("0"));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String timestampText(OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }

    // ---------------------------------------------------------------- 工具

    private OffsetDateTime parseTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private boolean booleanSetting(Map<String, String> settings, String key, boolean fallback) {
        String value = settings.get(key);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private int intSetting(Map<String, String> settings, String key, int fallback, int minimum) {
        String value = settings.get(key);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Math.max(minimum, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long longSetting(Map<String, String> settings, String key, Long fallback) {
        String value = settings.get(key);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
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
