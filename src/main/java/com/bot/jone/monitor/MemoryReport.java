package com.bot.jone.monitor;

import lombok.Builder;
import lombok.Data;

/**
 * 内存报告数据类
 */
@Data
@Builder
public class MemoryReport {
    /** 堆内存已使用（MB） */
    private long heapUsedMB;

    /** 堆内存最大值（MB） */
    private long heapMaxMB;

    /** 堆内存已提交（MB） */
    private long heapCommittedMB;

    /** JVM总内存（MB） */
    private long jvmTotalMB;

    /** JVM空闲内存（MB） */
    private long jvmFreeMB;

    /** CPU负载百分比 */
    private double cpuLoadPercent;

    /** 线程数 */
    private int threadCount;

    /**
     * 获取内存使用百分比
     */
    public double getHeapUsagePercent() {
        if (heapMaxMB == 0) return 0;
        return (heapUsedMB * 100.0) / heapMaxMB;
    }

    @Override
    public String toString() {
        return String.format("MemoryReport{堆内存: %dMB/%dMB (%.1f%%), JVM: %dMB, CPU: %.1f%%, 线程: %d}",
            heapUsedMB, heapMaxMB, getHeapUsagePercent(), jvmTotalMB, cpuLoadPercent, threadCount);
    }
}