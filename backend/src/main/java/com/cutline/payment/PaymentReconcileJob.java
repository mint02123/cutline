package com.cutline.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cutline.payment.reconciler.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileJob.class);

    private final PaymentReconciler reconciler;

    PaymentReconcileJob(PaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${cutline.payment.reconciler.interval-ms:5000}")
    void run() {
        PaymentReconciler.Report report = reconciler.reconcile();
        if (report.resolved() + report.refunded() + report.stillUnknown() > 0) {
            log.info("payment reconcile: {}", report);
        }
    }
}
