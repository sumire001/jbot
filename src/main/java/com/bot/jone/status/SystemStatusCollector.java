package com.bot.jone.status;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态面板数据采集：实时读取部署 J-One 的本机（Termux/Linux）系统指标。
 *
 * 数据源：
 *   - /proc/cpuinfo            CPU 型号 / 内核数
 *   - /sys/.../cpuinfo_max_freq CPU 最大主频
 *   - /proc/stat               系统整体 CPU 使用率（双采样，间隔约 250ms）
 *   - /proc/meminfo            内存总量 / 可用量
 *   - /proc/sys/kernel/osrelease 内核版本
 *   - JVM 进程 CPU：com.sun.management.OperatingSystemMXBean#getProcessCpuLoad
 *
 * 任一项读取失败都降级为 "unknown"，绝不返回假数据。
 * Windows 开发机上 /proc 不存在，各项自然降级，不影响启动。
 */
@Component
public class SystemStatusCollector {

    /** 采集间隔(ms)：/proc/stat 双采样窗口 */
    private static final long SAMPLE_INTERVAL_MS = 250;

    /** 兜底值：任何采集失败都用它 */
    private static final String UNKNOWN = "unknown";

    /**
     * 采集一次快照，key 与模板占位符一致：
     * CPU_MODEL / CPU_FREQ / CPU_CORES / JVM_CPU / CPU_USAGE / MEM_TOTAL / MEM_USED / MEM_PCT / MEM_RING / SYSTEM_VERSION
     */
    public Map<String, String> collect() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CPU_MODEL", cpuModel());
        m.put("CPU_FREQ", cpuFreq());
        m.put("CPU_CORES", cpuCores());
        m.put("CPU_USAGE", cpuUsage());
        m.put("JVM_CPU", jvmCpu());

        long[] mem = meminfoKb();   // [0]=total, [1]=available
        if (mem[0] > 0) {
            long used = Math.max(0, mem[0] - mem[1]);
            double pct = used * 100.0 / mem[0];
            m.put("MEM_TOTAL", String.format("%.2f MB", mem[0] / 1024.0));
            m.put("MEM_USED", String.format("%.2f MB", used / 1024.0));
            m.put("MEM_PCT", String.format("%.0f%%", pct));
            m.put("MEM_RING", String.format(
                    "conic-gradient(#c2703d 0%% %.1f%%, rgba(0,0,0,0.08) %.1f%% 100%%)", pct, pct));
        } else {
            m.put("MEM_TOTAL", UNKNOWN);
            m.put("MEM_USED", UNKNOWN);
            m.put("MEM_PCT", UNKNOWN);
            m.put("MEM_RING", "conic-gradient(#c2703d 0% 0%, rgba(0,0,0,0.08) 0% 100%)");
        }

        m.put("SYSTEM_VERSION", systemVersion());
        return m;
    }

    /** CPU 型号：/proc/cpuinfo 的 "model name"（x86）或 "Hardware"（Android）；都无则 unknown */
    private String cpuModel() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                String l = line.toLowerCase();
                if (l.startsWith("model name") || l.startsWith("hardware")) {
                    int i = line.indexOf(':');
                    if (i >= 0) {
                        String v = line.substring(i + 1).trim();
                        if (!v.isEmpty()) return v;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return UNKNOWN;
    }

    /** CPU 主频：cpuinfo_max_freq（kHz）→ GHz；缺文件再试 cpuinfo_cur_freq */
    private String cpuFreq() {
        for (String f : new String[]{"cpuinfo_max_freq", "cpuinfo_cur_freq"}) {
            try {
                String s = Files.readString(
                        Path.of("/sys/devices/system/cpu/cpu0/cpufreq/" + f)).trim();
                long khz = Long.parseLong(s);
                if (khz > 0) {
                    return String.format("%.2fGHz", khz / 1_000_000.0);
                }
            } catch (Exception ignore) {
            }
        }
        return UNKNOWN;
    }

    /** 内核数：/proc/cpuinfo 的 processor 行数，失败退 Runtime.availableProcessors() */
    private String cpuCores() {
        try {
            int n = 0;
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.toLowerCase().startsWith("processor")) n++;
            }
            if (n > 0) return String.valueOf(n);
        } catch (Exception ignore) {
        }
        return String.valueOf(Runtime.getRuntime().availableProcessors());
    }

    /** 系统整体 CPU 使用率：/proc/stat 双采样；失败返回 unknown */
    private String cpuUsage() {
        try {
            long[] a = procStatCpuTimes();
            Thread.sleep(SAMPLE_INTERVAL_MS);
            long[] b = procStatCpuTimes();
            if (a == null || b == null) return UNKNOWN;
            long dTotal = (b[0] + b[1]) - (a[0] + a[1]);   // total = busy + idle
            long dIdle = b[1] - a[1];
            if (dTotal <= 0) return UNKNOWN;
            return String.format("%.2f%%", (dTotal - dIdle) * 100.0 / dTotal);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** 读 /proc/stat 首行：返回 {busy, idle(含 iowait)}，单位 jiffies */
    private long[] procStatCpuTimes() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
            if (!line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            // parts: cpu user nice system idle iowait irq softirq steal ...
            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long busy = user + nice + system;
            return new long[]{busy, idle + iowait};
        } catch (Exception e) {
            return null;
        }
    }

    /** JVM 进程 CPU 占用（全核口径 0~100%）；不可用返回 unknown */
    private String jvmCpu() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            double load = os.getProcessCpuLoad();
            if (load < 0) return UNKNOWN;
            return String.format("%.2f%%", load * 100);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** /proc/meminfo：返回 {MemTotal, MemAvailable}（KB）；失败返回 {0, 0} */
    private long[] meminfoKb() {
        long total = 0, avail = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) continue;
                switch (parts[0]) {
                    case "MemTotal:" -> total = Long.parseLong(parts[1]);
                    case "MemAvailable:" -> avail = Long.parseLong(parts[1]);
                    default -> { }
                }
                if (total > 0 && avail > 0) break;
            }
        } catch (Exception ignore) {
        }
        return new long[]{total, avail};
    }

    /** 系统版本：os.name + os.arch + 内核版本（/proc/sys/kernel/osrelease） */
    private String systemVersion() {
        String kernel = "";
        try {
            kernel = Files.readString(Path.of("/proc/sys/kernel/osrelease"),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception ignore) {
        }
        String base = System.getProperty("os.name", UNKNOWN) + " "
                + System.getProperty("os.arch", "");
        return kernel.isEmpty() ? base : base + " " + kernel;
    }
}
