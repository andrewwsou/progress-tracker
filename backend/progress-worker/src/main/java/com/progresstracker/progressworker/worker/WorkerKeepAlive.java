package com.progresstracker.progressworker.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class WorkerKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(WorkerKeepAlive.class);

    @Value("${worker.enabled:true}")
    private boolean workerEnabled;

    @Value("${queue.enabled:true}")
    private boolean queueEnabled;

    @PostConstruct
    public void keepAlive() {
        if (!workerEnabled) {
            log.info("WorkerKeepAlive disabled (worker.enabled=false)");
            return;
        }

        if (queueEnabled) {
            return;
        }

        Thread t = new Thread(() -> {
            log.info("WorkerKeepAlive running (queue.enabled=false). Worker will stay alive.");
            while (true) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ignored) {}
            }
        }, "worker-keepalive");

        t.setDaemon(false);
        t.start();
    }
}
