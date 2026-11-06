package com.family.quizlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 读取电视上所有带桌面入口的应用。默认受限名单来自本机实测的动画/视频类应用。
 *
 * v3.0：本机实测「插 U 盘直接播放里面的视频」完全绕过拦截，真正在放视频的是
 * com.tpv.xmic.contentbrowser.ebony2k15（媒体中心，VideoPlayerActivity，接收 MEDIA_MOUNTED），
 * 另外还有系统自带的 org.droidtv.usbautoplayer（USB 自动播放）。两者都补进默认名单。
 */
public class AppRepository {

    /** 58PUF7695_T3 上实际存在的视频 / 动画类应用。可在家长设置里随时改动。 */
    public static final String[] DEFAULT_RESTRICTED = {
            "com.cibn.tv",                          // 未来电视 / CNTV 内容端
            "com.newtv.cboxtv",                     // 银河奇异果（爱奇艺 TV）
            "com.xiaodianshi.tv.yst",               // 小电视
            "com.gitvdemo.video",                   // 系统视频演示应用
            "com.hisilicon.android.videoplayer",    // 本地视频播放器
            // v3.0 真机取证：U 盘视频实际由媒体中心播放，原名单完全没有它
            "com.tpv.xmic.contentbrowser.ebony2k15",
            // v3.0：系统自带 USB 自动播放器（插入 U 盘后可自动起播）
            "org.droidtv.usbautoplayer"
    };

    /**
     * v3.0 关键词兜底：系统里名字像播放器/文件管理器的包也默认拉进受限名单。
     * 只匹配这些词，绝不碰 storage / vold / providers 等系统组件（见 EXCLUDED）。
     */
    private static final String[] KEYWORDS = {
            "video", "movie", "player", "usb", "filemanager", "fileexplorer"
    };

    /** 命中关键词也必须排除的系统组件（存储、权限、媒体库、桌面等）。 */
    private static final String[] EXCLUDED_PREFIXES = {
            "com.android.providers.",
            "com.android.externalstorage",
            "com.android.storagemanager",
            "com.android.sharedstoragebackup",
            "com.android.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.android.vending",
            "com.ktcp.launcher",
            "com.tpv.xmic.oobe",
            "com.family.quizlauncher",
            "android"
    };

    /**
     * v3.0 严格模式默认白名单。条目以 ".*" 结尾时按前缀匹配（如 com.android.providers.*）。
     * 桌面、设置、SystemUI、安装器、OOBE、自身、媒体/下载 Provider 与输入法必须放行，
     * 否则会把自己锁在系统外面。
     */
    private static final String[] DEFAULT_ALLOW = {
            "com.family.quizlauncher",
            "com.ktcp.launcher",
            "com.android.tv.launcher",
            "com.google.android.tvlauncher",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.tpv.xmic.oobe",
            "com.android.providers.*",
            "com.android.inputmethod.*",
            "com.android.server.telecom"
    };

    /** v2.9 及以前的默认名单（迁移时不再强行加回，尊重家长的手动改动）。 */
    private static final String[] OLD_DEFAULTS = {
            "com.cibn.tv",
            "com.newtv.cboxtv",
            "com.xiaodianshi.tv.yst",
            "com.gitvdemo.video",
            "com.hisilicon.android.videoplayer"
    };

    /**
     * v3.0 一次性迁移用：只返回「新增」的默认受限包，
     * 包括实测的 U 盘播放器与关键词兜底命中的播放器，不碰 v2.9 的老名单。
     */
    public static Set<String> v30AddedDefaults(Context context) {
        Set<String> added = defaultRestricted(context);
        added.removeAll(Arrays.asList(OLD_DEFAULTS));
        added.removeAll(Arrays.asList(DEFAULT_RESTRICTED));
        added.add("com.tpv.xmic.contentbrowser.ebony2k15");
        added.add("org.droidtv.usbautoplayer");
        return added;
    }

    /** v3.0：严格模式白名单默认值（"*" 后缀条目按前缀匹配）。 */
    public static Set<String> defaultAllowList() {
        return new HashSet<String>(Arrays.asList(DEFAULT_ALLOW));
    }

    /** v3.0：白名单条目匹配，支持 "com.android.providers.*" 这种前缀写法。 */
    public static boolean matchesAllowEntry(String entry, String packageName) {
        if (entry == null || packageName == null) {
            return false;
        }
        if (entry.endsWith(".*")) {
            return packageName.startsWith(entry.substring(0, entry.length() - 1));
        }
        return entry.equals(packageName);
    }

    /** 判断某个包是否在白名单集合里（含前缀条目与输入法兜底）。 */
    public static boolean isAllowListed(Set<String> allowList, String packageName) {
        if (packageName == null || allowList == null) {
            return false;
        }
        for (String entry : allowList) {
            if (matchesAllowEntry(entry, packageName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 默认受限名单 = 实测固定名单 + 本机安装包关键词兜底。
     * 关键词只用于「像播放器」的包，并且排除存储/Provider/桌面等系统组件。
     */
    public static Set<String> defaultRestricted(Context context) {
        Set<String> set = new HashSet<String>(Arrays.asList(DEFAULT_RESTRICTED));
        if (context == null) {
            return set;
        }
        try {
            PackageManager pm = context.getPackageManager();
            for (android.content.pm.PackageInfo packageInfo : pm.getInstalledPackages(0)) {
                ApplicationInfo info = packageInfo == null ? null : packageInfo.applicationInfo;
                if (info == null || info.packageName == null) {
                    continue;
                }
                String pkg = info.packageName;
                if (isExcluded(pkg)) {
                    continue;
                }
                // 用户自己装的普通应用如果没有桌面入口，多半是插件/服务，不做兜底。
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!system && !hasLauncherEntry(pm, pkg)) {
                    continue;
                }
                if (matchesKeyword(pkg)) {
                    set.add(pkg);
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败就只用固定名单，绝不因此崩掉设置页
        }
        return set;
    }

    private static boolean matchesKeyword(String packageName) {
        String lower = packageName.toLowerCase(Locale.US);
        for (String keyword : KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExcluded(String packageName) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLauncherEntry(PackageManager pm, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
            return infos != null && !infos.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);

        Prefs prefs = Prefs.get(context);
        Set<String> restricted = prefs.getRestricted();
        String self = context.getPackageName();
        Map<String, AppEntry> unique = new HashMap<String, AppEntry>();

        for (ResolveInfo info : infos) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.equals(self) || unique.containsKey(packageName)) {
                continue;
            }
            String label;
            try {
                CharSequence cs = info.loadLabel(pm);
                label = cs == null ? packageName : cs.toString().trim();
            } catch (Exception e) {
                label = packageName;
            }
            if (label.isEmpty()) {
                label = packageName;
            }
            Drawable icon = null;
            try {
                icon = info.loadIcon(pm);
            } catch (Exception ignored) {
                // 个别应用图标损坏时退化为默认图标
            }
            unique.put(packageName,
                    new AppEntry(packageName, label, icon, restricted.contains(packageName)));
        }

        // v3.0：受限名单里可能有没有桌面入口的包（如 org.droidtv.usbautoplayer），
        // 也必须显示出来，否则家长无法单独关掉它。
        for (String pkg : restricted) {
            if (pkg == null || pkg.equals(self) || unique.containsKey(pkg)) {
                continue;
            }
            String label = pkg;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(info);
                if (cs != null && cs.length() > 0) {
                    label = cs.toString().trim();
                }
            } catch (Throwable ignored) {
                // 包不存在（例如换了设备）就保留包名
            }
            unique.put(pkg, new AppEntry(pkg, label, null, true));
        }

        List<AppEntry> list = new ArrayList<AppEntry>(unique.values());
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return list;
    }
}
