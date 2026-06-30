package org.example.demo.payment;

import lombok.extern.slf4j.Slf4j;
import org.example.demo.common.util.Uuid7;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractDepositTableWritePerformanceTest {
    protected static final int[] THREADS = {10, 50, 200, 500};
    protected static final int TOTAL_OPS = 100000;
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected Path csvPath;

    protected abstract String getDbType();

    @BeforeAll
    void initCsvPath() throws Exception {
        csvPath = timestampedCsvPath("deposit-table-write-performance-" + getDbType());
        Files.deleteIfExists(csvPath);
    }

    @Order(1)
    @Test
    void depositRecord_write_performance() throws Exception {
        runScenario("deposit_record Table Write Performance", "deposit_record");
    }

    @Order(2)
    @Test
    void depositRecord2_write_performance() throws Exception {
        runScenario("deposit_record2 Table Write Performance", "deposit_record2");
    }

    @Order(3)
    @Test
    void depositRecord3_write_performance() throws Exception {
        runScenario("deposit_record3 Table Write Performance", "deposit_record3");
    }

    protected void runScenario(String scenarioName, String tableName) throws Exception {
        for (int threads : THREADS) {
            writeCsvResult(stressRun(scenarioName, tableName, threads));
        }
    }

    protected TableWriteResult stressRun(String scenarioName, String tableName, int threads) throws Exception {
        resetTable(tableName);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong success = new AtomicLong();
        AtomicLong otherErrors = new AtomicLong();
        AtomicLong startedAtNs = new AtomicLong();
        AtomicLong finishedAtNs = new AtomicLong();
        ConcurrentLinkedQueue<Long> insertLatencyMs = new ConcurrentLinkedQueue<>();

        int opsPerThread = TOTAL_OPS / threads;
        int remainder = TOTAL_OPS % threads;

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(threads);
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int threadOps = opsPerThread + (threadIndex < remainder ? 1 : 0);
                int threadSeed = threadIndex;
                futures.add(CompletableFuture.runAsync(() -> {
                    ready.countDown();
                    awaitLatch(start);

                    for (int opIndex = 0; opIndex < threadOps; opIndex++) {
                        String rowId = Uuid7.newUuidString();
                        long createdAt = System.currentTimeMillis();
                        String channelId = "perf-channel-" + threadSeed;
                        String channelOrderId = "perf-order-" + threadSeed + "-" + opIndex + "-" + rowId;
                        long insertStartedAtNs = System.nanoTime();
                        try {
                            if ("deposit_record3".equals(tableName)) {
                                jdbcTemplate.update(
                                        "INSERT INTO " + tableName + " (created_at, channel_id, channel_order_id, status, amount, request_body) VALUES (?, ?, ?, ?, ?, ?)",
                                        createdAt,
                                        channelId,
                                        channelOrderId,
                                        0,
                                        100L,
                                        "{\"channel_id\":\"" + channelId + "\",\"channel_order_id\":\"" + channelOrderId + "\",\"amount\":100}"
                                );
                            } else {
                                jdbcTemplate.update(
                                        "INSERT INTO " + tableName + " (id, created_at, channel_id, channel_order_id, status, amount, request_body) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                        rowId,
                                        createdAt,
                                        channelId,
                                        channelOrderId,
                                        0,
                                        100L,
                                        "{\"channel_id\":\"" + channelId + "\",\"channel_order_id\":\"" + channelOrderId + "\",\"amount\":100}"
                                );
                            }
                            long insertMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
                            insertLatencyMs.add(insertMs);
                            success.incrementAndGet();
                        } catch (RuntimeException e) {
                            log.info(e.getMessage());
                            otherErrors.incrementAndGet();
                        }
                    }
                }, pool));
            }

            ready.await(30, TimeUnit.SECONDS);
            startedAtNs.set(System.nanoTime());
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.MINUTES);
            finishedAtNs.set(System.nanoTime());
        } finally {
            pool.shutdownNow();
        }

        long durationNs = finishedAtNs.get() > 0 ? (finishedAtNs.get() - startedAtNs.get()) : 0;
        Duration duration = Duration.ofNanos(Math.max(durationNs, 1));
        long rowCount = countRows(tableName);
        LatencySummary insertLatency = summarizeLatency(insertLatencyMs);
        double tps = success.get() / (duration.toNanos() / 1_000_000_000.0);

        System.out.println("=== " + scenarioName + " (threads " + threads + ") ===");
        System.out.println("table=" + tableName + ", totalOps=" + TOTAL_OPS + ", threads=" + threads);
        System.out.println("duration=" + duration.toMillis() + " ms, tps=" + String.format("%.2f", tps));
        System.out.println("insertMs min=" + insertLatency.minMs
                           + ", mid=" + insertLatency.midMs
                           + ", max=" + insertLatency.maxMs
                           + ", avg=" + String.format("%.2f", insertLatency.avgMs));
        System.out.println("success=" + success.get() + ", otherErrors=" + otherErrors.get());
        System.out.println("rowCount=" + rowCount);

        assertTrue(success.get() > 0, "Table write stress produced no successful inserts.");
        assertEquals(TOTAL_OPS, rowCount, "Unexpected row count for " + tableName);

        return new TableWriteResult(
                scenarioName,
                tableName,
                threads,
                TOTAL_OPS,
                duration.toMillis(),
                tps,
                success.get(),
                otherErrors.get(),
                insertLatency.minMs,
                insertLatency.midMs,
                insertLatency.maxMs,
                insertLatency.avgMs,
                rowCount
        );
    }

    protected void writeCsvResult(TableWriteResult result) throws Exception {
        Files.createDirectories(csvPath.getParent());
        boolean needsHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0L;

        StringBuilder line = new StringBuilder();
        if (needsHeader) {
            line.append("scenario,tableName,threads,totalOps,durationMs,tps,success,otherErrors,insertMinMs,insertMidMs,insertMaxMs,insertAvgMs,rowCount")
                    .append(System.lineSeparator());
        }
        line.append(csv(result.scenarioName)).append(',')
                .append(csv(result.tableName)).append(',')
                .append(result.threads).append(',')
                .append(result.totalOps).append(',')
                .append(result.durationMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.tps)).append(',')
                .append(result.success).append(',')
                .append(result.otherErrors).append(',')
                .append(result.insertMinMs).append(',')
                .append(result.insertMidMs).append(',')
                .append(result.insertMaxMs).append(',')
                .append(String.format(Locale.US, "%.2f", result.insertAvgMs)).append(',')
                .append(result.rowCount)
                .append(System.lineSeparator());

        Files.writeString(
                csvPath,
                line.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    protected void resetTable(String tableName) {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    protected long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    protected static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    protected static LatencySummary summarizeLatency(ConcurrentLinkedQueue<Long> latencyMs) {
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

        return new LatencySummary(
                sorted.getFirst(),
                sorted.get(sorted.size() / 2),
                sorted.get(sorted.size() - 1),
                sum / (double) (toIndex - fromIndex)
        );
    }

    protected static Path timestampedCsvPath(String prefix) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        return Path.of("", "stress-results", prefix + "-" + timestamp + ".csv");
    }

    protected static String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    protected record LatencySummary(long minMs, long midMs, long maxMs, double avgMs) {
    }

    protected static class TableWriteResult {
        private final String scenarioName;
        private final String tableName;
        private final int threads;
        private final int totalOps;
        private final long durationMs;
        private final double tps;
        private final long success;
        private final long otherErrors;
        private final long insertMinMs;
        private final long insertMidMs;
        private final long insertMaxMs;
        private final double insertAvgMs;
        private final long rowCount;

        protected TableWriteResult(
                String scenarioName,
                String tableName,
                int threads,
                int totalOps,
                long durationMs,
                double tps,
                long success,
                long otherErrors,
                long insertMinMs,
                long insertMidMs,
                long insertMaxMs,
                double insertAvgMs,
                long rowCount
        ) {
            this.scenarioName = scenarioName;
            this.tableName = tableName;
            this.threads = threads;
            this.totalOps = totalOps;
            this.durationMs = durationMs;
            this.tps = tps;
            this.success = success;
            this.otherErrors = otherErrors;
            this.insertMinMs = insertMinMs;
            this.insertMidMs = insertMidMs;
            this.insertMaxMs = insertMaxMs;
            this.insertAvgMs = insertAvgMs;
            this.rowCount = rowCount;
        }
    }
}
