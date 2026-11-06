package com.family.quizparent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单顺序探测本机同网段的 8080 端口，不做 mDNS、不依赖第三方库。
 * /24 网段最多 254 个地址，12 个线程并发，单地址 350ms 超时。
 */
public final class LanScanner {

    public interface Listener {
        void onProgress(int scanned, int total);

        void onFinished(List<String> hosts, boolean cancelled);
    }

    private static final int PORT = 8080;
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int THREADS = 12;

    private volatile boolean cancelled;
    private ExecutorService pool;

    public void cancel() {
        cancelled = true;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public void start(final Listener listener) {
        cancelled = false;
        final List<String> candidates = candidates();
        if (candidates.isEmpty()) {
            listener.onFinished(new ArrayList<String>(), false);
            return;
        }
        pool = Executors.newFixedThreadPool(THREADS);
        Thread coordinator = new Thread(new Runnable() {
            @Override
            public void run() {
                final AtomicInteger done = new AtomicInteger();
                final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                for (final String host : candidates) {
                    if (cancelled) {
                        break;
                    }
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!cancelled && probe(host)) {
                                    found.add(host);
                                }
                            } finally {
                                int scanned = done.incrementAndGet();
                                if (scanned % 16 == 0 || scanned == candidates.size()) {
                                    listener.onProgress(scanned, candidates.size());
                                }
                            }
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // 取消时直接继续汇报
                }
                List<String> result;
                synchronized (found) {
                    result = new ArrayList<String>(found);
                }
                listener.onFinished(result, cancelled);
            }
        }, "lan-scan");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    private static boolean probe(String host) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 优先取 wlan 网卡的 IPv4，超过 /24 的网段统一收敛到 /24，避免扫描 6 万多地址。 */
    private static List<String> candidates() {
        List<String> hosts = new ArrayList<String>();
        try {
            NetworkInterface wifi = null;
            NetworkInterface fallback = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                if (!name.startsWith("wlan")) {
                    if (fallback == null) {
                        fallback = nif;
                    }
                    continue;
                }
                wifi = nif;
                break;
            }
            NetworkInterface target = wifi != null ? wifi : fallback;
            if (target == null) {
                return hosts;
            }

            InetAddress address = null;
            int prefix = 24;
            for (java.net.InterfaceAddress ia : target.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address
                        && !ia.getAddress().isLoopbackAddress()
                        && !ia.getAddress().isLinkLocalAddress()) {
                    address = ia.getAddress();
                    prefix = ia.getNetworkPrefixLength();
                    if (ia.getAddress().isSiteLocalAddress()) {
                        break;
                    }
                }
            }
            if (address == null) {
                return hosts;
            }
            int ip = address.hashCode();
            int bits = 32 - prefix;
            if (bits > 8) {
                bits = 8;
            }
            if (bits <= 0) {
                bits = 8;
            }
            int mask = 0xFFFFFFFF << bits;
            int network = ip & mask;
            int total = (1 << bits) - 2;
            for (int i = 1; i <= total; i++) {
                int value = network + i;
                if (value == ip) {
                    continue;
                }
                hosts.add(formatIp(value));
            }
        } catch (Throwable ignored) {
            // 网络接口异常时返回空列表
        }
        return hosts;
    }

    private static String formatIp(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
