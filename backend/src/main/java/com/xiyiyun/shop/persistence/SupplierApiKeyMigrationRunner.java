package com.xiyiyun.shop.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SupplierApiKeyMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(SupplierApiKeyMigrationRunner.class);

    private final ConfigPersistenceStore configPersistenceStore;

    public SupplierApiKeyMigrationRunner(ConfigPersistenceStore configPersistenceStore) {
        this.configPersistenceStore = configPersistenceStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacySupplierApiKeys() {
        int migrated = configPersistenceStore.migrateLegacySupplierApiKeys();
        if (migrated > 0) {
            log.info("Migrated {} legacy supplier api keys to encrypted storage", migrated);
        }
    }
}
