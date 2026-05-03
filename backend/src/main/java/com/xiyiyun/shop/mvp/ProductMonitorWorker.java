package com.xiyiyun.shop.mvp;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductMonitorWorker {
    private final InMemoryShopRepository repository;
    private volatile boolean running;
    private Thread worker;

    public ProductMonitorWorker(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::runLoop, "product-monitor-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runLoop() {
        while (running) {
            try {
                List<Long> dueChannels = repository.dueProductMonitorChannelIds(OffsetDateTime.now());
                for (Long channelId : dueChannels) {
                    if (!running) break;
                    repository.scanProductMonitorChannel(channelId, false);
                }
                Thread.sleep(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
