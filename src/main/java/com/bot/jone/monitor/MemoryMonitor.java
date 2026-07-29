package com.bot.jone.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.text.DecimalFormat;

/**
 * 内存和CPU监控组件
 * 定时打印系统资源使用情况
 */
@Component
@Slf4j
public class MemoryMonitor {

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
    private final DecimalFormat df = new DecimalFormat("#.##");

    /**
     * 每5分钟打印一次内存使用情况
     */
    @Scheduled(fixedRate = 300000)
    public void logMemoryUsage() {
        // 堆内存使用
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        long usedMB = heapUsage.getUsed() / 1024 / 1024;
        long maxMB = heapUsage.getMax() / 1024 / 1024;
        double usedPercent = (heapUsage.getUsed() * 100.0) / heapUsage.getMax();

        // JVM总内存
        Runtime runtime = Runtime.getRuntime();
        long totalMemoryMB = runtime.totalMemory() / 1024 / 1024;
        long freeMemoryMB = runtime.freeMemory() / 1024 / 1024;

        // 系统CPU负载
        double cpuLoad = getCpuLoad();

        log.info("[资源监控] 堆内存: {}MB/{}MB ({}%), JVM总内存: {}MB, 空闲: {}MB, CPU负载: {}%",
            usedMB, maxMB, df.format(usedPercent),
            totalMemoryMB, freeMemoryMB,
            df.format(cpuLoad));

        // 内存警告（超过80MB）
        if (usedMB > 80) {
            log.warn("[资源警告] 堆内存使用超过80MB！当前: {}MB/{}MB", usedMB, maxMB);
        }

        // 内存严重警告（超过90MB）
        if (usedMB > 90) {
            log.error("[资源严重警告] 堆内存使用超过90MB！即将达到100MB上限！当前: {}MB", usedMB);
        }
    }

    /**
     * 手动触发内存报告
     */
    public MemoryReport getMemoryReport() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        Runtime runtime = Runtime.getRuntime();

        return MemoryReport.builder()
            .heapUsedMB(heapUsage.getUsed() / 1024 / 1024)
            .heapMaxMB(heapUsage.getMax() / 1024 / 1024)
            .heapCommittedMB(heapUsage.getCommitted() / 1024 / 1024)
            .jvmTotalMB(runtime.totalMemory() / 1024 / 1024)
            .jvmFreeMB(runtime.freeMemory() / 1024 / 1024)
            .cpuLoadPercent(getCpuLoad())
            .threadCount(ManagementFactory.getThreadMXBean().getThreadCount())
            .build();
    }

    /**
     * 获取CPU负载百分比
     */
    private double getCpuLoad() {
        try {
            if (osMXBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osMXBean;
                return sunOsBean.getProcessCpuLoad() * 100;
            }
        } catch (Exception e) {
            log.debug("无法获取CPU负载: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 手动触发GC（慎用）
     */
    public void triggerGC() {
        log.info("[资源监控] 手动触发GC...");
        long beforeUsed = memoryMXBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        System.gc();
        long afterUsed = memoryMXBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        log.info("[资源监控] GC完成，内存: {}MB -> {}MB (释放 {}MB)",
            beforeUsed, afterUsed, beforeUsed - afterUsed);
    }
}