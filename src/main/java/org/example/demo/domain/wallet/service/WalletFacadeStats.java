package org.example.demo.domain.wallet.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class WalletFacadeStats {
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong operationTimeoutCount = new AtomicLong();
    private final ConcurrentLinkedQueue<Long> insertMsValues = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> updateBalanceMsValues = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> findMsValues = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> getLockMsValues = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> redisTotalMsValues = new ConcurrentLinkedQueue<>();

    public void recordRetry() {
        retryCount.incrementAndGet();
    }

    public void recordOperationTimeout() {
        operationTimeoutCount.incrementAndGet();
    }

    public void recordInsertMs(long insertMs) {
        insertMsValues.add(insertMs);
    }

    public void recordUpdateBalanceMs(long updateBalanceMs) {
        updateBalanceMsValues.add(updateBalanceMs);
    }

    public void recordFindMs(long findMs) {
        findMsValues.add(findMs);
    }

    public void recordGetLockMs(long getLockMs) {
        getLockMsValues.add(getLockMs);
    }

    public void recordRedisTotalMs(long redisTotalMs) {
        redisTotalMsValues.add(redisTotalMs);
    }

    public long retryCount() {
        return retryCount.get();
    }

    public long operationTimeoutCount() {
        return operationTimeoutCount.get();
    }

    public long insertMinMs() {
        return summarize(insertMsValues).minMs();
    }

    public long insertMidMs() {
        return summarize(insertMsValues).midMs();
    }

    public double insertAvgMs() {
        return summarize(insertMsValues).avgMs();
    }

    public long insertMaxMs() {
        return summarize(insertMsValues).maxMs();
    }

    public long updateBalanceMinMs() {
        return summarize(updateBalanceMsValues).minMs();
    }

    public long updateBalanceMidMs() {
        return summarize(updateBalanceMsValues).midMs();
    }

    public double updateBalanceAvgMs() {
        return summarize(updateBalanceMsValues).avgMs();
    }

    public long updateBalanceMaxMs() {
        return summarize(updateBalanceMsValues).maxMs();
    }

    public long findMinMs() {
        return summarize(findMsValues).minMs();
    }

    public long findMidMs() {
        return summarize(findMsValues).midMs();
    }

    public double findAvgMs() {
        return summarize(findMsValues).avgMs();
    }

    public long findMaxMs() {
        return summarize(findMsValues).maxMs();
    }

    public long getLockMinMs() {
        return summarize(getLockMsValues).minMs();
    }

    public long getLockMidMs() {
        return summarize(getLockMsValues).midMs();
    }

    public double getLockAvgMs() {
        return summarize(getLockMsValues).avgMs();
    }

    public long getLockMaxMs() {
        return summarize(getLockMsValues).maxMs();
    }

    public long redisTotalMinMs() {
        return summarize(redisTotalMsValues).minMs();
    }

    public long redisTotalMidMs() {
        return summarize(redisTotalMsValues).midMs();
    }

    public double redisTotalAvgMs() {
        return summarize(redisTotalMsValues).avgMs();
    }

    public long redisTotalMaxMs() {
        return summarize(redisTotalMsValues).maxMs();
    }

    public void reset() {
        retryCount.set(0L);
        operationTimeoutCount.set(0L);
        insertMsValues.clear();
        updateBalanceMsValues.clear();
        findMsValues.clear();
        getLockMsValues.clear();
        redisTotalMsValues.clear();
    }

    private TimeSummary summarize(ConcurrentLinkedQueue<Long> values) {
        if (values.isEmpty()) {
            return new TimeSummary(0L, 0L, 0L, 0D);
        }

        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int trimCount = sorted.size() >= 100 ? Math.max(1, sorted.size() / 100) : 0;
        int fromIndex = trimCount;
        int toIndex = sorted.size() - trimCount;
        if (fromIndex >= toIndex) {
            fromIndex = 0;
            toIndex = sorted.size();
        }

        long sum = 0L;
        for (int index = fromIndex; index < toIndex; index++) {
            sum += sorted.get(index);
        }

        return new TimeSummary(
                sorted.getFirst(),
                sorted.get(sorted.size() / 2),
                sorted.get(sorted.size() - 1),
                sum / (double) (toIndex - fromIndex)
        );
    }

    private record TimeSummary(long minMs, long midMs, long maxMs, double avgMs) {
    }
}
