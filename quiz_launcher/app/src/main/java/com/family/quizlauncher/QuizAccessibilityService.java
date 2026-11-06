package com.family.quizlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * v2 拦截核心：只监听窗口状态变化，拿到前台包名。
 *
 * 受限应用且没有有效放行时，弹出一个 TYPE_ACCESSIBILITY_OVERLAY 全屏答题遮罩。
 * 不注册 HOME、不在后台起 Activity（遮罩添加失败时才降级为 Activity，再兜底回桌面）。
 */
public class QuizAccessibilityService extends AccessibilityService {

    private static final String TAG = "QuizGate";

    /** 窗口事件可能连续触发，500ms 内只评估一次，避免反复弹遮罩。 */
    private static final long DEBOUNCE_MS = 500L;

    /** 放行到点后，多等 300ms 再弹，避开窗口切换动画。 */
    private static final long EXPIRY_GRACE_MS = 300L;

    /**
     * 逃生口：遮罩显示期间连按 3 次返回键，直接强拆遮罩并回桌面。
     * 走的是服务侧的按键过滤，即使遮罩窗口丢了焦点、按键进不去界面也能生效。
     */
    private static final long ESCAPE_WINDOW_MS = 3000L;
    private static final int ESCAPE_BACK_COUNT = 3;

    /** 遮罩自愈复查间隔：只在遮罩存活/刚异常消失时运行，不是常驻轮询。 */
    private static final long GUARD_INTERVAL_MS = 1500L;
    /** 遮罩异常消失后，先等一小会儿再复查（等窗口切换动画结束）。 */
    private static final long GUARD_RECHECK_MS = 800L;

    /**
     * 永久白名单：自身、桌面、系统设置、SystemUI、包安装器、OOBE 等。
     * 输入法由 InputMethodManager 动态补充，避免不同电视的输入法包名不一致。
     */
    private static final Set<String> STATIC_WHITELIST = new HashSet<String>(Arrays.asList(
            "android",
            "com.ktcp.launcher",
            "com.android.tv.launcher",
            "com.google.android.tvlauncher",
            "com.android.settings",
            "com.android.tv.settings",
            "com.android.systemui",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.tpv.xmic.oobe"
    ));

    private static volatile QuizAccessibilityService sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private volatile boolean connected;
    private String lastForeground;
    private String pendingPackage;
    private String expiryPackage;
    private long lastBackDownAt;
    private int backDownCount;
    /** 正在守护的受限包：遮罩异常消失时用它自愈重建。 */
    private String guardTarget;

    private final Runnable pendingCheck = new Runnable() {
        @Override
        public void run() {
            String packageName = pendingPackage;
            pendingPackage = null;
            if (!connected || packageName == null) {
                return;
            }
            // 电视上桌面等后台窗口会穿插补发事件，lastForeground 可能已被噪音覆盖，
            // 这里以当前真正聚焦的窗口为准，读不到才退回最近一次事件。
            String focused = currentFocusedPackage();
            String effective = focused != null ? focused : lastForeground;
            if (!packageName.equals(effective)) {
                return;
            }
            android.util.Log.d(TAG, "pendingCheck pkg=" + packageName
                    + " focused=" + focused
                    + " showing=" + QuizOverlay.isShowing()
                    + " allowed=" + isAlwaysAllowed(packageName)
                    + " strict=" + prefs.isStrictMode()
                    + " restricted=" + prefs.isRestricted(packageName)
                    + " pass=" + prefs.isPassActive(packageName));
            if (QuizOverlay.isShowing()) {
                return;
            }
            if (!shouldGate(packageName)) {
                return;
            }
            showOverlay(packageName);
        }
    };

    private final Runnable expiryCheck = new Runnable() {
        @Override
        public void run() {
            String packageName = expiryPackage;
            expiryPackage = null;
            if (!connected || packageName == null) {
                return;
            }
            if (QuizOverlay.isShowing()) {
                return;
            }
            // 只有受限应用仍在前台时才重新拦截；已经回桌面就不再打扰。
            if (lastForeground == null || !lastForeground.equals(packageName)) {
                return;
            }
            if (!shouldGate(packageName)) {
                return;
            }
            if (!isInteractive()) {
                return;
            }
            showOverlay(packageName);
        }
    };

    /**
     * 遮罩自愈：只要「遮罩不在了，但目标受限包还停在前台」，就补弹一次。
     * 仅在遮罩存活期间、或遮罩刚异常消失后运行；目标包离开前台就自动停止，不做常驻轮询。
     */
    private final Runnable guardCheck = new Runnable() {
        @Override
        public void run() {
            String target = guardTarget;
            if (!connected || target == null) {
                return;
            }
            if (QuizOverlay.isShowing()) {
                handler.postDelayed(this, GUARD_INTERVAL_MS);
                return;
            }
            if (!shouldGate(target)) {
                guardTarget = null;
                return;
            }
            String foreground = currentFocusedPackage();
            if (target.equals(foreground)) {
                // 确认受限包仍在前台：补弹遮罩。
                showOverlay(target);
            } else {
                // 已经切到别的窗口（桌面/其它应用），或查不到前台：
                // 宁可不再补弹，也不能把答题遮罩误盖到桌面上。
                guardTarget = null;
                return;
            }
            handler.postDelayed(this, GUARD_INTERVAL_MS);
        }
    };

    private final QuizOverlay.Callback overlayCallback = new QuizOverlay.Callback() {
        @Override
        public void onPass(String packageName, long remainMs) {
            guardTarget = null;
            handler.removeCallbacks(guardCheck);
            scheduleExpiryCheck(packageName, remainMs);
        }

        @Override
        public void onFail(String packageName) {
            // 答错 / 取消 / 返回键：把用户送回桌面，不把受限应用留在前台。
            guardTarget = null;
            handler.removeCallbacks(guardCheck);
            Launcher.goHome(QuizAccessibilityService.this);
        }
    };

    public static boolean isConnected() {
        QuizAccessibilityService service = sInstance;
        return service != null && service.connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        connected = true;
        android.util.Log.d(TAG, "service connected");
        prefs = Prefs.get(this);
        prefs.initRestricted(AppRepository.defaultRestricted(this));
        prefs.ensureV30Defaults(this);
        // 设备重启过就清空所有放行记录（不依赖 BOOT_COMPLETED 广播）。
        prefs.purgeStalePasses();
        // 上一次遮罩如果因为进程被杀没来得及恢复音量，这里补偿恢复。
        AudioSilencer.restoreIfNeeded(this);

        handler.removeCallbacksAndMessages(null);
        pendingPackage = null;
        expiryPackage = null;
        lastForeground = null;
        // 进程被系统杀掉后重建时，旧窗口已经不存在，这里只做兜底清理。
        QuizOverlay.dismissActive();

        // 杀进程/重连后不会再有窗口事件，这里主动复查一次前台：
        // 受限应用还开着且没放行就立刻补弹，把「进程被低内存杀手干掉 → 遮罩消失」的空窗补上。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForegroundAfterConnect();
            }
        }, 600L);
    }

    private void checkForegroundAfterConnect() {
        if (!connected) {
            return;
        }
        String foreground = currentFocusedPackage();
        if (foreground == null || !shouldGate(foreground)) {
            return;
        }
        showOverlay(foreground);
    }

    /**
     * 按键过滤只用于逃生：遮罩显示时连按 3 次返回键 → 拆遮罩 + 回桌面。
     * 其余按键一律返回 false 放行，不改变正常操作。
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !QuizOverlay.isShowing()) {
            backDownCount = 0;
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackDownAt > ESCAPE_WINDOW_MS) {
            backDownCount = 0;
        }
        lastBackDownAt = now;
        backDownCount++;
        if (backDownCount >= ESCAPE_BACK_COUNT) {
            backDownCount = 0;
            forceBackToHome();
        }
        return false;
    }

    /** 不看遮罩状态，直接强拆窗口并回桌面（逃生口 / 异常兜底）。 */
    private void forceBackToHome() {
        try {
            QuizOverlay.dismissActive();
        } catch (Throwable ignored) {
            // 窗口可能已被系统移除
        }
        Launcher.goHome(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!connected || event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence name = event.getPackageName();
        if (name == null) {
            return;
        }
        String packageName = name.toString();
        if (packageName.isEmpty()) {
            return;
        }
        lastForeground = packageName;
        android.util.Log.d(TAG, "win event pkg=" + packageName
                + " overlay=" + QuizOverlay.isShowing()
                + " connected=" + connected);

        if (QuizOverlay.isShowing()) {
            // 遮罩正在抢回焦点时会短暂产生系统窗口事件，这期间不要撤罩。
            if (QuizOverlay.isRecoveringFocus()) {
                return;
            }
            String target = QuizOverlay.getTargetPackage();
            if (packageName.equals(target) || packageName.equals(getPackageName())) {
                return;
            }
            if (isHomeLauncher(packageName)) {
                if (QuizOverlay.hasWindowFocus()) {
                    // 电视上桌面窗口经常在后台补发 WINDOW_STATE_CHANGED 噪音事件，
                    // 遮罩仍然拿着焦点就说明用户没走，不能撤罩。
                    return;
                }
                // 真的回到了桌面：撤掉遮罩（应用内「回桌面」也会走这里兜底）。
                QuizOverlay.dismissActive();
                return;
            }
            if (shouldGate(packageName)) {
                // 切到另一个受限包：撤掉旧遮罩，新包由下面的拦截流程接管。
                QuizOverlay.dismissActive();
                guardTarget = target;
                handler.removeCallbacks(guardCheck);
                handler.postDelayed(guardCheck, GUARD_RECHECK_MS);
            } else {
                // 系统窗口 / 输入法 / TTS 引擎 / 自动化窗口等临时窗口：
                // 保留遮罩。不能因为这类窗口被撤罩，否则受限 App 会裸露在前台。
                return;
            }
        }

        if (!shouldGate(packageName)) {
            return;
        }
        scheduleCheck(packageName);
    }

    private void scheduleCheck(String packageName) {
        pendingPackage = packageName;
        handler.removeCallbacks(pendingCheck);
        handler.postDelayed(pendingCheck, DEBOUNCE_MS);
    }

    private void scheduleExpiryCheck(String packageName, long remainMs) {
        handler.removeCallbacks(expiryCheck);
        expiryPackage = packageName;
        if (remainMs <= 0L) {
            return;
        }
        handler.postDelayed(expiryCheck, remainMs + EXPIRY_GRACE_MS);
    }

    private void showOverlay(String packageName) {
        if (QuizOverlay.isShowing()) {
            return;
        }
        String label = labelOf(packageName);
        android.util.Log.d(TAG, "showOverlay pkg=" + packageName);
        try {
            QuizOverlay.show(this, packageName, label, overlayCallback);
            // 遮罩存活期间每 1.5 秒复查一次：窗口被系统意外移除、或事件漏网时补弹。
            guardTarget = packageName;
            handler.removeCallbacks(guardCheck);
            handler.postDelayed(guardCheck, GUARD_INTERVAL_MS);
        } catch (Throwable t) {
            android.util.Log.d(TAG, "showOverlay failed: " + t);
            // 遮罩添加失败（例如窗口令牌异常）：撤掉残留窗口，降级为 Activity。
            QuizOverlay.dismissActive();
            guardTarget = null;
            fallbackToActivity(packageName, label);
        }
    }

    /** 当前聚焦窗口的包名。读不到就返回 null，调用方按「可能是目标包」的最安全方向处理。 */
    private String currentFocusedPackage() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) {
                return null;
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window == null
                        || window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    continue;
                }
                if (!window.isFocused() && !window.isActive()) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                CharSequence pkg = root.getPackageName();
                if (pkg != null && pkg.length() > 0) {
                    return pkg.toString();
                }
            }
        } catch (Throwable ignored) {
            // 没有窗口读取能力时返回 null
        }
        return null;
    }

    /** 遮罩不可用时的第二道防线；Activity 也无法启动时直接回桌面。 */
    private void fallbackToActivity(String packageName, String label) {
        try {
            Intent intent = new Intent(this, QuizActivity.class);
            intent.putExtra(QuizActivity.EXTRA_PACKAGE, packageName);
            intent.putExtra(QuizActivity.EXTRA_LABEL, label);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            Launcher.goHome(this);
        }
    }

    /** 电视桌面（HOME）包名集合：只有真正回到桌面时才撤遮罩。 */
    private static final Set<String> HOME_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.ktcp.launcher",
            "com.android.tv.launcher",
            "com.google.android.tvlauncher"
    ));

    private boolean isHomeLauncher(String packageName) {
        return packageName != null && HOME_PACKAGES.contains(packageName);
    }

    /**
     * 统一拦截判定（v3.0）：
     * - 硬白名单（自身/桌面/设置/SystemUI/安装器/OOBE/输入法）永不拦截；
     * - 有有效放行就放行；
     * - 严格模式：白名单之外的一切前台应用都要答题；
     * - 普通模式：只看家长勾选的受限名单。
     */
    private boolean shouldGate(String packageName) {
        if (packageName == null || isAlwaysAllowed(packageName)) {
            return false;
        }
        if (prefs.isPassActive(packageName)) {
            return false;
        }
        if (prefs.isStrictMode()) {
            return !AppRepository.isAllowListed(prefs.getAllowList(), packageName);
        }
        return prefs.isRestricted(packageName);
    }

    private boolean isAlwaysAllowed(String packageName) {
        if (packageName == null) {
            return true;
        }
        if (packageName.equals(getPackageName())) {
            return true;
        }
        if (STATIC_WHITELIST.contains(packageName)) {
            return true;
        }
        // 媒体库 / 下载 Provider 等系统组件即使严格模式下也绝不能被拦。
        if (packageName.startsWith("com.android.providers.")) {
            return true;
        }
        return isInputMethod(packageName);
    }

    private boolean isInputMethod(String packageName) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return false;
            }
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (packageName.equals(info.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 输入法列表读取失败时按非输入法处理
        }
        return false;
    }

    private String labelOf(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (!TextUtils.isEmpty(label)) {
                return label.toString();
            }
        } catch (Throwable ignored) {
            // 应用被卸载或不可见时退回包名
        }
        return packageName;
    }

    private boolean isInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void onInterrupt() {
        cleanup();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        cleanup();
        if (sInstance == this) {
            sInstance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        cleanup();
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        pendingPackage = null;
        expiryPackage = null;
        guardTarget = null;
        QuizOverlay.dismissActive();
    }
}
