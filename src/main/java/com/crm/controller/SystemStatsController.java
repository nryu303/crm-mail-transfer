package com.crm.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON endpoint that powers the dashboard's right-edge server-stats panel.
 * Reads host memory from /proc/meminfo (Linux only — degrades to 0/0 elsewhere), CPU
 * load from {@link OperatingSystemMXBean#getSystemLoadAverage()}, disk usage from
 * {@link File#getTotalSpace()}, and JVM heap from the MX bean. Poll interval is
 * picked by the front-end; this side is cheap to invoke (~no DB, just file reads).
 */
@RestController
@RequestMapping("/manager/dashboard/sys-stats")
public class SystemStatsController {

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();

        // Host memory (Linux /proc/meminfo)
        Map<String, Long> mem = readMeminfo();
        long total = mem.getOrDefault("MemTotal", 0L) * 1024L;
        long free  = mem.getOrDefault("MemFree", 0L)  * 1024L;
        long avail = mem.getOrDefault("MemAvailable", free) * 1024L;
        long buff  = mem.getOrDefault("Buffers", 0L) * 1024L;
        long cache = mem.getOrDefault("Cached", 0L) * 1024L;
        long used  = total - avail;
        long swapTotal = mem.getOrDefault("SwapTotal", 0L) * 1024L;
        long swapFree  = mem.getOrDefault("SwapFree", 0L)  * 1024L;
        long swapUsed  = swapTotal - swapFree;

        Map<String, Object> memBlock = new LinkedHashMap<>();
        memBlock.put("totalBytes", total);
        memBlock.put("usedBytes",  used);
        memBlock.put("availBytes", avail);
        memBlock.put("buffBytes",  buff);
        memBlock.put("cacheBytes", cache);
        memBlock.put("usedPct",    total > 0 ? (int) ((used * 100) / total) : 0);
        memBlock.put("swapTotalBytes", swapTotal);
        memBlock.put("swapUsedBytes",  swapUsed);
        memBlock.put("swapUsedPct",    swapTotal > 0 ? (int) ((swapUsed * 100) / swapTotal) : 0);
        out.put("memory", memBlock);

        // CPU load
        OperatingSystemMXBean osb = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("cores", osb.getAvailableProcessors());
        cpu.put("load1", osb.getSystemLoadAverage());
        out.put("cpu", cpu);

        // Disk (root filesystem)
        File root = new File("/");
        long diskTotal = root.getTotalSpace();
        long diskFree  = root.getFreeSpace();
        long diskUsed  = diskTotal - diskFree;
        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("totalBytes", diskTotal);
        disk.put("usedBytes",  diskUsed);
        disk.put("freeBytes",  diskFree);
        disk.put("usedPct",    diskTotal > 0 ? (int) ((diskUsed * 100) / diskTotal) : 0);
        out.put("disk", disk);

        // JVM heap
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsedBytes",   heap.getUsed());
        jvm.put("heapCommitBytes", heap.getCommitted());
        jvm.put("heapMaxBytes",    heap.getMax());
        jvm.put("heapUsedPct",     heap.getMax() > 0 ? (int) ((heap.getUsed() * 100) / heap.getMax()) : 0);
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        jvm.put("uptimeSec", uptimeMs / 1000);
        out.put("jvm", jvm);

        out.put("nowMs", System.currentTimeMillis());
        return out;
    }

    private static Map<String, Long> readMeminfo() {
        Map<String, Long> out = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/meminfo"));
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String rest = line.substring(colon + 1).trim();
                // value is like "12345 kB" — strip the suffix
                int sp = rest.indexOf(' ');
                String num = sp > 0 ? rest.substring(0, sp) : rest;
                try { out.put(key, Long.parseLong(num)); }
                catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {
            // /proc/meminfo not available (non-Linux) — caller falls back to zeros.
        }
        return out;
    }
}
