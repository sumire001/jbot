package com.bot.jone.monitor;

import com.bot.jone.config.ImagesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 资源监控HTTP接口
 */
@RestController
@RequestMapping("/monitor")
@Slf4j
public class MonitorController {

    @Autowired
    private MemoryMonitor memoryMonitor;

    @Autowired
    private ImagesConfig imagesConfig;

    /**
     * 获取内存报告
     * GET http://localhost:9026/monitor/memory
     */
    @GetMapping("/memory")
    public Map<String, Object> getMemoryReport() {
        MemoryReport report = memoryMonitor.getMemoryReport();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", report);
        result.put("limit", Map.of(
            "maxHeapMB", 100,
            "configMaxMB", imagesConfig.getDownload().getMaxSizeMb()
        ));
        return result;
    }

    /**
     * 手动触发GC（谨慎使用）
     * POST http://localhost:9026/monitor/gc
     */
    @PostMapping("/gc")
    public Map<String, Object> triggerGC() {
        log.warn("[监控接口] 手动触发GC请求");
        memoryMonitor.triggerGC();
        MemoryReport report = memoryMonitor.getMemoryReport();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "GC已触发");
        result.put("currentMemory", report);
        return result;
    }

    /**
     * 查看JVM详细信息
     * GET http://localhost:9026/monitor/jvm
     */
    @GetMapping("/jvm")
    public Map<String, Object> getJvmInfo() {
        Map<String, Object> result = new HashMap<>();
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

        // JVM基本信息
        result.put("jvm", Map.of(
            "javaVersion", System.getProperty("java.version"),
            "javaVendor", System.getProperty("java.vendor"),
            "javaHome", System.getProperty("java.home"),
            "osName", System.getProperty("os.name"),
            "osVersion", System.getProperty("os.version"),
            "availableProcessors", runtime.availableProcessors()
        ));

        // 内存信息
        result.put("memory", Map.of(
            "heapUsedMB", memoryMXBean.getHeapMemoryUsage().getUsed() / 1024 / 1024,
            "heapMaxMB", memoryMXBean.getHeapMemoryUsage().getMax() / 1024 / 1024,
            "heapCommittedMB", memoryMXBean.getHeapMemoryUsage().getCommitted() / 1024 / 1024,
            "jvmTotalMB", runtime.totalMemory() / 1024 / 1024,
            "jvmFreeMB", runtime.freeMemory() / 1024 / 1024
        ));

        // 线程信息
        result.put("threads", Map.of(
            "threadCount", ManagementFactory.getThreadMXBean().getThreadCount(),
            "peakThreadCount", ManagementFactory.getThreadMXBean().getPeakThreadCount()
        ));

        // CPU信息
        double cpuLoad = -1;
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                cpuLoad = sunOs.getProcessCpuLoad() * 100;
            }
        } catch (Exception e) { /* ignore */ }
        result.put("cpu", Map.of("processCpuLoad", cpuLoad));

        // GC信息
        long gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(bean -> bean.getCollectionCount())
            .sum();
        result.put("gc", Map.of("gcCount", gcCount));

        // 运行时间
        long uptimeMs = runtimeMXBean.getUptime();
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs) % 60;
        result.put("uptime", Map.of(
            "formatted", String.format("%d小时%d分%d秒", hours, minutes, seconds),
            "uptimeMs", uptimeMs
        ));

        result.put("success", true);
        return result;
    }
}