package org.example.demo.payment;

import lombok.extern.slf4j.Slf4j;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.facade.DepositFacade;
import org.example.demo.domain.payment.facade.DepositIgnoreAutoIncrementFacade;
import org.example.demo.domain.payment.facade.DepositIgnoreFacade;
import org.example.demo.domain.payment.facade.DepositRedisLockFacade;
import org.example.demo.domain.payment.infrastructure.exception.DepositLockRetryExceededException;
import org.example.demo.domain.payment.service.DepositFacadeStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDepositStressTest {
    protected static final int[] THREADS = {10, 50, 200, 200, 500, 500};
    protected static final DepositStressScenario EXTREME_CONTENTION =
            new DepositStressScenario(100000, 10);
    protected static final DepositStressScenario PERFORMANCE =
            new DepositStressScenario(100000, 1);

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DepositIgnoreFacade depositIgnoreFacade;

    @Autowired
    protected DepositIgnoreAutoIncrementFacade depositIgnoreAutoIncrementFacade;

    @Autowired
    protected DepositRedisLockFacade depositRedisLockFacade;

    @Autowired
    protected DepositFacadeStats depositFacadeStats;

    protected Path CSV_PATH;

    protected abstract String getDbType();

    @BeforeAll
    void initCsvPath() throws Exception {
        CSV_PATH = timestampedCsvPath("deposit-" + getDbType());
        resetCsv(CSV_PATH);
    }


    @Order(1)
    @Test
    void insertIgnore_extreme_contention() throws Exception {
        runScenarioRounds(CSV_PATH, "InsertIgnore Extreme Contention", depositIgnoreFacade, "deposit_record", EXTREME_CONTENTION);
    }

    @Order(2)
    @Test
    void redisLock_extreme_contention() throws Exception {
        runScenarioRounds(CSV_PATH, "RedisLock Extreme Contention", depositRedisLockFacade, "deposit_record2", EXTREME_CONTENTION);
    }

    @Order(3)
    @Test
    void insertIgnoreAutoIncrement_extreme_contention() throws Exception {
        runScenarioRounds(CSV_PATH, "InsertIgnore AutoIncrement Extreme Contention", depositIgnoreAutoIncrementFacade, "deposit_record3", EXTREME_CONTENTION);
    }

    @Order(4)
    @Test
    void insertIgnore_performance() throws Exception {
        runScenarioRounds(CSV_PATH, "InsertIgnore Performance", depositIgnoreFacade, "deposit_record", PERFORMANCE);
    }

    @Order(5)
    @Test
    void redisLock_performance() throws Exception {
        runScenarioRounds(CSV_PATH, "RedisLock Performance", depositRedisLockFacade, "deposit_record2", PERFORMANCE);
    }

    @Order(6)
    @Test
    void insertIgnoreAutoIncrement_performance() throws Exception {
        runScenarioRounds(CSV_PATH, "InsertIgnore AutoIncrement Performance", depositIgnoreAutoIncrementFacade, "deposit_record3", PERFORMANCE);
    }

    protected void runScenarioRounds(
            Path csvPath,
            String scenarioName,
            DepositFacade depositFacade,
            String tableName,
            DepositStressScenario scenario
    ) throws Exception {
        for (int threads : THREADS) {
            writeCsvResult(csvPath, stressRun(scenarioName, depositFacade, tableName, scenario, threads));
        }
    }

    protected DepositStressRunResult stressRun(
            String scenarioName,
            DepositFacade depositFacade,
            String tableName,
            DepositStressScenario scenario,
            int threads
    ) throws Exception {
        resetTable(tableName);
        resetStats();
        int totalRequests = scenario.totalRequests;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong requestSequence = new AtomicLong();
        AtomicLong success = new AtomicLong();
        AtomicLong lockRetryExceeded = new AtomicLong();
        AtomicLong otherErrors = new AtomicLong();
        AtomicLong startedAtNs = new AtomicLong();
        AtomicLong finishedAtNs = new AtomicLong();
        ConcurrentLinkedQueue<Long> successDirectLatencyMs = new ConcurrentLinkedQueue<>();
        Set<String> returnedIds = ConcurrentHashMap.newKeySet();

        try {
            List<CompletableFuture<Void>> requestFutures = new ArrayList<>(threads);
            for (int workerIndex = 0; workerIndex < threads; workerIndex++) {
                requestFutures.add(CompletableFuture.runAsync(() -> {
                    ready.countDown();
                    awaitLatch(start);

                    while (true) {
                        long requestIndex = requestSequence.getAndIncrement();
                        if (requestIndex >= totalRequests) {
                            return;
                        }

                        int groupIndex = (int) (requestIndex / scenario.duplicatesPerKey);
                        String requestBody = buildRequestBody(groupIndex);

                        try {
                            startedAtNs.compareAndSet(0L, System.nanoTime());
                            long directStartedAtNs = System.nanoTime();
                            DepositRecord depositRecord = depositFacade.direct(requestBody);
                            long directMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - directStartedAtNs));
                            success.incrementAndGet();
                            successDirectLatencyMs.add(directMs);
                            returnedIds.add(depositRecord.getId());
                        } catch (DepositLockRetryExceededException e) {
                            lockRetryExceeded.incrementAndGet();
                        } catch (RuntimeException e) {
                            log.info(e.getMessage());
                            otherErrors.incrementAndGet();
                        }
                    }
                }, pool));
            }
            ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            CompletableFuture.allOf(requestFutures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.MINUTES);
            finishedAtNs.set(System.nanoTime());
        } finally {
            pool.shutdownNow();
        }

        long durationNs = finishedAtNs.get() > 0 ? (finishedAtNs.get() - startedAtNs.get()) : 0;
        Duration duration = Duration.ofNanos(Math.max(durationNs, 1));
        long total = success.get() + lockRetryExceeded.get() + otherErrors.get();
        double tps = success.get() / (duration.toNanos() / 1_000_000_000.0);
        double successRatio = ratio(success.get(), total);
        double lockRetryExceededRatio = ratio(lockRetryExceeded.get(), total);
        double otherErrorsRatio = ratio(otherErrors.get(), total);
        LatencySummary insertLatency = new LatencySummary(
                depositFacadeStats.insertMinMs(),
                depositFacadeStats.insertMidMs(),
                depositFacadeStats.insertMaxMs(),
                depositFacadeStats.insertAvgMs()
        );
        LatencySummary directLatency = summarizeLatency(successDirectLatencyMs);
        LatencySummary findLatency = new LatencySummary(
                depositFacadeStats.findMinMs(),
                depositFacadeStats.findMidMs(),
                depositFacadeStats.findMaxMs(),
                depositFacadeStats.findAvgMs()
        );
        LatencySummary getLockLatency = new LatencySummary(
                depositFacadeStats.getLockMinMs(),
                depositFacadeStats.getLockMidMs(),
                depositFacadeStats.getLockMaxMs(),
                depositFacadeStats.getLockAvgMs()
        );
        LatencySummary redisTotalLatency = new LatencySummary(
                depositFacadeStats.redisTotalMinMs(),
                depositFacadeStats.redisTotalMidMs(),
                depositFacadeStats.redisTotalMaxMs(),
                depositFacadeStats.redisTotalAvgMs()
        );
        long rowCount = countRows(tableName);
        long distinctKeys = countDistinctKeys(tableName);
        long deadlockCount = depositFacadeStats.deadlockCount();
        long retryCount = depositFacadeStats.retryCount();
        long retryExceededCount = depositFacadeStats.retryExceededCount();

        System.out.println("=== " + scenarioName + " (threads " + threads + ") ===");
        System.out.println("table=" + tableName
                           + ", threads=" + threads
                           + ", totalRequests=" + scenario.totalRequests
                           + ", duplicatesPerKey=" + scenario.duplicatesPerKey);
        System.out.println("duration=" + duration.toMillis() + " ms, tps=" + String.format("%.2f", tps));
        System.out.println("insertMs min=" + insertLatency.minMs
                           + ", mid=" + insertLatency.midMs
                           + ", max=" + insertLatency.maxMs
                           + ", avg=" + String.format("%.2f", insertLatency.avgMs));
        System.out.println("directMs min=" + directLatency.minMs
                           + ", mid=" + directLatency.midMs
                           + ", max=" + directLatency.maxMs
                           + ", avg=" + String.format("%.2f", directLatency.avgMs));
        System.out.println("findMs min=" + findLatency.minMs
                           + ", mid=" + findLatency.midMs
                           + ", max=" + findLatency.maxMs
                           + ", avg=" + String.format("%.2f", findLatency.avgMs));
        System.out.println("getLockMs min=" + getLockLatency.minMs
                           + ", mid=" + getLockLatency.midMs
                           + ", max=" + getLockLatency.maxMs
                           + ", avg=" + String.format("%.2f", getLockLatency.avgMs));
        System.out.println("redisTotalMs min=" + redisTotalLatency.minMs
                           + ", mid=" + redisTotalLatency.midMs
                           + ", max=" + redisTotalLatency.maxMs
                           + ", avg=" + String.format("%.2f", redisTotalLatency.avgMs));
        System.out.println("success=" + success.get()
                           + ", lockRetryExceeded=" + lockRetryExceeded.get()
                           + ", otherErrors=" + otherErrors.get());
        System.out.println("successRatio=" + formatPercent(successRatio)
                           + ", lockRetryExceededRatio=" + formatPercent(lockRetryExceededRatio)
                           + ", otherErrorsRatio=" + formatPercent(otherErrorsRatio));
        System.out.println("rowCount=" + rowCount + ", distinctKeys=" + distinctKeys + ", returnedIds=" + returnedIds.size());
        System.out.println("deadlocks=" + deadlockCount
                           + ", retries=" + retryCount
                           + ", retryExceeded=" + retryExceededCount);

        assertTrue(success.get() > 0, "Stress run produced no successful operations.");
        assertEquals(rowCount, distinctKeys, "Row count and distinct key count should match for " + tableName);

        return new DepositStressRunResult(
                scenarioName,
                threads,
                tableName,
                threads,
                scenario.totalRequests,
                scenario.duplicatesPerKey,
                duration.toMillis(),
                tps,
                success.get(),
                successRatio,
                lockRetryExceeded.get(),
                lockRetryExceededRatio,
                otherErrors.get(),
                otherErrorsRatio,
                insertLatency.minMs,
                insertLatency.midMs,
                insertLatency.maxMs,
                insertLatency.avgMs,
                directLatency.minMs,
                directLatency.midMs,
                directLatency.maxMs,
                directLatency.avgMs,
                findLatency.minMs,
                findLatency.midMs,
                findLatency.maxMs,
                findLatency.avgMs,
                getLockLatency.minMs,
                getLockLatency.midMs,
                getLockLatency.maxMs,
                getLockLatency.avgMs,
                redisTotalLatency.minMs,
                redisTotalLatency.midMs,
                redisTotalLatency.maxMs,
                redisTotalLatency.avgMs,
                deadlockCount,
                retryCount,
                retryExceededCount,
                rowCount,
                distinctKeys,
                returnedIds.size()
        );
    }

    protected void resetStats() {
        depositFacadeStats.reset();
    }

    protected void resetTable(String tableName) {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    protected long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    protected long countDistinctKeys(String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT channel_id, channel_order_id FROM " + tableName + " GROUP BY channel_id, channel_order_id) t",
                Long.class
        );
        return count == null ? 0L : count;
    }

    protected String buildRequestBody(int groupIndex) {
        return "{\"channel_id\":\"stress-channel\",\"channel_order_id\":\"order-" + groupIndex + "\",\"amount\":100}";
    }

    protected void writeCsvResult(Path csvPath, DepositStressRunResult result) throws Exception {
        Files.createDirectories(csvPath.getParent());
        boolean needsHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0L;

        StringBuilder line = new StringBuilder();
        if (needsHeader) {
            line.append("scenario,threadsConfig,tableName,threads,totalRequests,duplicatesPerKey,durationMs,tps,success,successRatio,lockRetryExceeded,lockRetryExceededRatio,otherErrors,otherErrorsRatio,insertMinMs,insertMidMs,insertMaxMs,insertAvgMs,directMinMs,directMidMs,directMaxMs,directAvgMs,findMinMs,findMidMs,findMaxMs,findAvgMs,getLockMinMs,getLockMidMs,getLockMaxMs,getLockAvgMs,redisTotalMinMs,redisTotalMidMs,redisTotalMaxMs,redisTotalAvgMs,deadlocks,retries,retryExceeded,rowCount,distinctKeys,returnedIds")
                    .append(System.lineSeparator());
        }
        line.append(csv(result.scenarioName)).append(',')
                .append(result.threadsConfig).append(',')
                .append(csv(result.tableName)).append(',')
                .append(result.threads).append(',')
                .append(result.totalRequests).append(',')
                .append(result.duplicatesPerKey).append(',')
                .append(result.durationMs).append(',')
                .append(String.format("%.2f", result.tps)).append(',')
                .append(result.success).append(',')
                .append(formatRatio(result.successRatio)).append(',')
                .append(result.lockRetryExceeded).append(',')
                .append(formatRatio(result.lockRetryExceededRatio)).append(',')
                .append(result.otherErrors).append(',')
                .append(formatRatio(result.otherErrorsRatio)).append(',')
                .append(result.insertMinMs).append(',')
                .append(result.insertMidMs).append(',')
                .append(result.insertMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.insertAvgMs)).append(',')
                .append(result.directMinMs).append(',')
                .append(result.directMidMs).append(',')
                .append(result.directMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.directAvgMs)).append(',')
                .append(result.findMinMs).append(',')
                .append(result.findMidMs).append(',')
                .append(result.findMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.findAvgMs)).append(',')
                .append(result.getLockMinMs).append(',')
                .append(result.getLockMidMs).append(',')
                .append(result.getLockMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.getLockAvgMs)).append(',')
                .append(result.redisTotalMinMs).append(',')
                .append(result.redisTotalMidMs).append(',')
                .append(result.redisTotalMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.redisTotalAvgMs)).append(',')
                .append(result.deadlocks).append(',')
                .append(result.retries).append(',')
                .append(result.retryExceeded).append(',')
                .append(result.rowCount).append(',')
                .append(result.distinctKeys).append(',')
                .append(result.returnedIds).append(System.lineSeparator());

        Files.writeString(
                csvPath,
                line.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    protected static void resetCsv(Path csvPath) throws Exception {
        Files.deleteIfExists(csvPath);
    }

    protected static Path timestampedCsvPath(String prefix) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        return Path.of("", "stress-results", prefix + "-" + timestamp + ".csv");
    }

    protected static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static double ratio(long value, long total) {
        if (total == 0) {
            return 0D;
        }
        return value / (double) total;
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.US, "%.2f%%", ratio * 100);
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.US, "%.4f", ratio);
    }

    private static LatencySummary summarizeLatency(ConcurrentLinkedQueue<Long> latencyMs) {
        if (latencyMs.isEmpty()) {
            return new LatencySummary(0L, 0L, 0L, 0D);
        }

        List<Long> sorted = new ArrayList<>(latencyMs);
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
        long minMs = sorted.getFirst();
        long maxMs = sorted.get(sorted.size() - 1);
        long midMs = sorted.get(sorted.size() / 2);
        double avgMs = sum / (double) (toIndex - fromIndex);
        return new LatencySummary(minMs, midMs, maxMs, avgMs);
    }

    private static String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    protected static class DepositStressScenario {
        private final int totalRequests;
        private final int duplicatesPerKey;

        protected DepositStressScenario(int totalRequests, int duplicatesPerKey) {
            this.totalRequests = totalRequests;
            this.duplicatesPerKey = duplicatesPerKey;
        }
    }

    protected static class DepositStressRunResult {
        private final String scenarioName;
        private final int threadsConfig;
        private final String tableName;
        private final int threads;
        private final int totalRequests;
        private final int duplicatesPerKey;
        private final long durationMs;
        private final double tps;
        private final long success;
        private final double successRatio;
        private final long lockRetryExceeded;
        private final double lockRetryExceededRatio;
        private final long otherErrors;
        private final double otherErrorsRatio;
        private final long insertMinMs;
        private final long insertMidMs;
        private final long insertMaxMs;
        private final double insertAvgMs;
        private final long directMinMs;
        private final long directMidMs;
        private final long directMaxMs;
        private final double directAvgMs;
        private final long findMinMs;
        private final long findMidMs;
        private final long findMaxMs;
        private final double findAvgMs;
        private final long getLockMinMs;
        private final long getLockMidMs;
        private final long getLockMaxMs;
        private final double getLockAvgMs;
        private final long redisTotalMinMs;
        private final long redisTotalMidMs;
        private final long redisTotalMaxMs;
        private final double redisTotalAvgMs;
        private final long deadlocks;
        private final long retries;
        private final long retryExceeded;
        private final long rowCount;
        private final long distinctKeys;
        private final int returnedIds;

        protected DepositStressRunResult(
                String scenarioName,
                int threadsConfig,
                String tableName,
                int threads,
                int totalRequests,
                int duplicatesPerKey,
                long durationMs,
                double tps,
                long success,
                double successRatio,
                long lockRetryExceeded,
                double lockRetryExceededRatio,
                long otherErrors,
                double otherErrorsRatio,
                long insertMinMs,
                long insertMidMs,
                long insertMaxMs,
                double insertAvgMs,
                long directMinMs,
                long directMidMs,
                long directMaxMs,
                double directAvgMs,
                long findMinMs,
                long findMidMs,
                long findMaxMs,
                double findAvgMs,
                long getLockMinMs,
                long getLockMidMs,
                long getLockMaxMs,
                double getLockAvgMs,
                long redisTotalMinMs,
                long redisTotalMidMs,
                long redisTotalMaxMs,
                double redisTotalAvgMs,
                long deadlocks,
                long retries,
                long retryExceeded,
                long rowCount,
                long distinctKeys,
                int returnedIds
        ) {
            this.scenarioName = scenarioName;
            this.threadsConfig = threadsConfig;
            this.tableName = tableName;
            this.threads = threads;
            this.totalRequests = totalRequests;
            this.duplicatesPerKey = duplicatesPerKey;
            this.durationMs = durationMs;
            this.tps = tps;
            this.success = success;
            this.successRatio = successRatio;
            this.lockRetryExceeded = lockRetryExceeded;
            this.lockRetryExceededRatio = lockRetryExceededRatio;
            this.otherErrors = otherErrors;
            this.otherErrorsRatio = otherErrorsRatio;
            this.insertMinMs = insertMinMs;
            this.insertMidMs = insertMidMs;
            this.insertMaxMs = insertMaxMs;
            this.insertAvgMs = insertAvgMs;
            this.directMinMs = directMinMs;
            this.directMidMs = directMidMs;
            this.directMaxMs = directMaxMs;
            this.directAvgMs = directAvgMs;
            this.findMinMs = findMinMs;
            this.findMidMs = findMidMs;
            this.findMaxMs = findMaxMs;
            this.findAvgMs = findAvgMs;
            this.getLockMinMs = getLockMinMs;
            this.getLockMidMs = getLockMidMs;
            this.getLockMaxMs = getLockMaxMs;
            this.getLockAvgMs = getLockAvgMs;
            this.redisTotalMinMs = redisTotalMinMs;
            this.redisTotalMidMs = redisTotalMidMs;
            this.redisTotalMaxMs = redisTotalMaxMs;
            this.redisTotalAvgMs = redisTotalAvgMs;
            this.deadlocks = deadlocks;
            this.retries = retries;
            this.retryExceeded = retryExceeded;
            this.rowCount = rowCount;
            this.distinctKeys = distinctKeys;
            this.returnedIds = returnedIds;
        }
    }

    private record LatencySummary(long minMs, long midMs, long maxMs, double avgMs) {
    }
}
