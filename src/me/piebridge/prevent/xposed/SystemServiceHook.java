package me.piebridge.prevent.xposed;

import android.app.IApplicationThread;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import me.piebridge.forcestopgb.BuildConfig;
import me.piebridge.prevent.common.GmsUtils;
import me.piebridge.prevent.common.PreventIntent;
import me.piebridge.prevent.framework.ActivityManagerServiceHook;
import me.piebridge.prevent.framework.IntentFilterHook;
import me.piebridge.prevent.framework.IntentFilterMatchResult;
import me.piebridge.prevent.framework.PreventLog;
import me.piebridge.prevent.framework.SystemHook;
import me.piebridge.prevent.framework.util.ActivityRecordUtils;
import me.piebridge.prevent.framework.util.BroadcastFilterUtils;
import me.piebridge.prevent.framework.util.LogcatUtils;
import me.piebridge.prevent.framework.util.ProcessRecordUtils;
import me.piebridge.prevent.framework.util.SafeActionUtils;

/**
 * Created by thom on 15/9/19.
 */
public class SystemServiceHook extends XC_MethodHook {

    private static boolean systemHooked;

    private static Method getRecordForAppLocked;

    private static final ThreadLocal<String> RECEIVER_SENDER = new ThreadLocal<String>();

    private static final ThreadLocal<String> SERVICE_SENDER = new ThreadLocal<String>();

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        if (!systemHooked) {
            PreventLog.d("start prevent hook (system)");
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            SystemHook.setClassLoader(classLoader);
            hookActivityManagerService(classLoader);
            hookActivity(classLoader);
            hookIntentFilter(classLoader);
            hookIntentIfNeeded(classLoader);
            if (BuildConfig.ALIPAY_DONATE || BuildConfig.WECHAT_DONATE) {
                exportActivityIfNeeded();
            }
            PreventLog.d("finish prevent hook (system)");
            systemHooked = true;
            LogcatUtils.logcat();
        }
    }

    private void hookIntentIfNeeded(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            XposedHelpers.findAndHookMethod("android.content.Intent", classLoader,
                    "isExcludingStopped",
                    new IntentExcludingStoppedHook());
        }
    }

    private void hookIntentFilter(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.IntentFilter", classLoader, "match",
                String.class, String.class, String.class, Uri.class, Set.class, String.class,
                new IntentFilterMatchHook());
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> activityManagerService = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader);

        hookActivityManagerServiceStartProcessLocked(activityManagerService);

        hookActivityManagerServiceBroadcastIntent(activityManagerService, classLoader);

        hookActivityManagerServiceStartService(activityManagerService);

        hookActivityManagerServiceBindService(activityManagerService, classLoader);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            hookActivityManagerServiceCleanUpRemovedTaskLocked(activityManagerService, classLoader);
        }

        hookActivityManagerServiceStartActivity(activityManagerService, classLoader);

        hookActivityManagerServiceMoveActivityTaskToBack(activityManagerService);

        hookActivityManagerServiceHandleAppDiedLocked(activityManagerService, classLoader);

        hookActivityManagerServiceRemoveProcessLocked(activityManagerService, classLoader);

        getRecordForAppLocked = activityManagerService.getDeclaredMethod("getRecordForAppLocked", IApplicationThread.class);
        getRecordForAppLocked.setAccessible(true);
    }

    private static void logLinkageError(String method, LinkageError e) {
        PreventLog.d("cannot hook " + method, e);
    }

    private void hookActivityManagerServiceRemoveProcessLocked(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        // mainly for android 5.1's dependency
        int sdk = Build.VERSION.SDK_INT;
        String method = "removeProcessLocked";
        XC_MethodHook hook = new IgnoreDependencyHook();
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            Class<?> processRecord = Class.forName("com.android.server.am.ProcessRecord", false, classLoader);
            if (sdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                // sdk 15, sdk 16, sdk 17, sdk 18, sdk 19, sdk 21, sdk 22, sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        processRecord, boolean.class, boolean.class, String.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                // sdk 14
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        processRecord, boolean.class, boolean.class,
                        hook);
            } else {
                // sdk 10
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        processRecord, boolean.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceHandleAppDiedLocked(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        int sdk = Build.VERSION.SDK_INT;
        String method = "handleAppDiedLocked";
        XC_MethodHook hook = new AppDiedHook();
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            Class<?> processRecord = Class.forName("com.android.server.am.ProcessRecord", false, classLoader);
            if (sdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                // sdk 14, sdk 15, sdk 16, sdk 17, sdk 18, sdk 19, sdk 21, sdk 22, sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        processRecord, boolean.class, boolean.class,
                        hook);
            } else {
                // sdk 10
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        processRecord, boolean.class,
                        hook);
            }
        }

    }

    private void hookActivityManagerServiceMoveActivityTaskToBack(Class<?> activityManagerService) {
        // for move activity to back
        String method = "moveActivityTaskToBack";
        XC_MethodHook hook = new BackActivityHook();
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            // sdk 10, sdk 14, sdk 15, sdk 16, sdk 17, sdk 18, sdk 19, sdk 21, sdk 22, sdk 23
            XposedHelpers.findAndHookMethod(activityManagerService, method,
                    IBinder.class, boolean.class,
                    hook);
        }
    }

    private void hookActivityManagerServiceStartActivity(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        // for start home activity
        int sdk = Build.VERSION.SDK_INT;
        String method = "startActivity";
        XC_MethodHook hook = new HomeActivityHook();
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            if (sdk >= Build.VERSION_CODES.LOLLIPOP) {
                // sdk 21, sdk 22, sdk 23
                Class<?> profilerInfo = Class.forName("android.app.ProfilerInfo", false, classLoader);
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, String.class, Intent.class, String.class, IBinder.class, String.class, int.class, int.class, profilerInfo, Bundle.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // sdk 18, sdk 19
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, String.class, Intent.class, String.class, IBinder.class, String.class, int.class, int.class, String.class, ParcelFileDescriptor.class, Bundle.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN) {
                // sdk 16, sdk 17
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, IBinder.class, String.class, int.class, int.class, String.class, ParcelFileDescriptor.class, Bundle.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                // sdk 14, sdk 15
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, Uri[].class, int.class, IBinder.class, String.class, int.class, boolean.class, boolean.class, String.class, ParcelFileDescriptor.class, boolean.class,
                        hook);
            } else {
                // sdk 10
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, Uri[].class, int.class, IBinder.class, String.class, int.class, boolean.class, boolean.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceCleanUpRemovedTaskLocked(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        int sdk = Build.VERSION.SDK_INT;
        String method = "cleanUpRemovedTaskLocked";
        XC_MethodHook hook = new CleanUpRemovedHook();
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            if (sdk >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // sdk 22, sdk 23
                Class<?> taskRecord = Class.forName("com.android.server.am.TaskRecord", false, classLoader);
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        taskRecord, boolean.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN) {
                // sdk 16, sdk 17, sdk 18, sdk 19, sdk 21
                Class<?> taskRecord = Class.forName("com.android.server.am.TaskRecord", false, classLoader);
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        taskRecord, int.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                // sdk 14, sdk 15
                Class<?> activityRecord = Class.forName("com.android.server.am.ActivityRecord", false, classLoader);
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        activityRecord, boolean.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceBindService(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        int sdk = Build.VERSION.SDK_INT;
        String method = "bindService";
        XC_MethodHook hook = new ContextHook(SERVICE_SENDER);
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            Class<?> iServiceConnection = Class.forName("android.app.IServiceConnection", false, classLoader);
            if (sdk >= Build.VERSION_CODES.M) {
                // sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, IBinder.class, Intent.class, String.class, iServiceConnection, int.class, String.class, int.class,
                        hook);
            } else
            if (sdk >= Build.VERSION_CODES.JELLY_BEAN) {
                // sdk 16, sdk 17, sdk 18, sdk 19, sdk 21, sdk 22
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, IBinder.class, Intent.class, String.class, iServiceConnection, int.class, int.class,
                        hook);
            } else {
                // sdk 10, sdk 14, sdk 15
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, IBinder.class, Intent.class, String.class, iServiceConnection, int.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceStartService(Class<?> activityManagerService) {
        int sdk = Build.VERSION.SDK_INT;
        String method = "startService";
        XC_MethodHook hook = new ContextHook(SERVICE_SENDER);
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            if (sdk >= Build.VERSION_CODES.M) {
                // sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, String.class, int.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // sdk 18, sdk 19, sdk 21, sdk 22
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, int.class,
                        hook);
            } else {
                // sdk 10, sdk 14, sdk 15, sdk 16, sdk 17
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceBroadcastIntent(Class<?> activityManagerService, ClassLoader classLoader) throws ClassNotFoundException {
        int sdk = Build.VERSION.SDK_INT;
        String method = "broadcastIntent";
        XC_MethodHook hook = new IntentContextHook(RECEIVER_SENDER);
        try {
            hookLongestMethod(activityManagerService, method, hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            Class<?> iIntentReceiver = Class.forName("android.content.IIntentReceiver", false, classLoader);
            if (sdk >= Build.VERSION_CODES.M) {
                // sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, iIntentReceiver, int.class, String.class, Bundle.class, String[].class, int.class, Bundle.class, boolean.class, boolean.class, int.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // sdk 18, sdk 19, sdk 21, sdk 22
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, iIntentReceiver, int.class, String.class, Bundle.class, String.class, int.class, boolean.class, boolean.class, int.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN) {
                // sdk 16, sdk 17
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, iIntentReceiver, int.class, String.class, Bundle.class, String.class, boolean.class, boolean.class, int.class,
                        hook);
            } else {
                // sdk 10, sdk 14, sdk 15
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        IApplicationThread.class, Intent.class, String.class, iIntentReceiver, int.class, String.class, Bundle.class, String.class, boolean.class, boolean.class,
                        hook);
            }
        }
    }

    private void hookActivityManagerServiceStartProcessLocked(Class<?> activityManagerService) {
        int sdk = Build.VERSION.SDK_INT;
        String method = "startProcessLocked";
        XC_MethodHook hook = new ProcessHook();
        try {
            hookLongestMethod(activityManagerService, method, "ProcessRecord", hook);
        } catch (LinkageError e) {
            logLinkageError(method, e);
            if (sdk >= Build.VERSION_CODES.LOLLIPOP) {
                // sdk 21, sdk 22, sdk 23
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        String.class, ApplicationInfo.class, boolean.class, int.class, String.class, ComponentName.class, boolean.class, boolean.class, int.class, boolean.class, String.class, String.class, String[].class, Runnable.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.KITKAT) {
                // sdk 19, sdk 20
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        String.class, ApplicationInfo.class, boolean.class, int.class, String.class, ComponentName.class, boolean.class, boolean.class, boolean.class,
                        hook);
            } else if (sdk >= Build.VERSION_CODES.JELLY_BEAN) {
                // sdk 16, sdk 17, sdk 18
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        String.class, ApplicationInfo.class, boolean.class, int.class, String.class, ComponentName.class, boolean.class, boolean.class,
                        hook);
            } else {
                // sdk 10, 14, 15
                XposedHelpers.findAndHookMethod(activityManagerService, method,
                        String.class, ApplicationInfo.class, boolean.class, int.class, String.class, ComponentName.class, boolean.class,
                        hook);
            }
        }
    }

    private static Method findLongestMethod(Class<?> hookClass, String methodName, String returnName) {
        Method longestMethod = null;
        for (Method method : hookClass.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            String returnType = method.getReturnType().getSimpleName();
            if ((returnName == null || returnName.equals(returnType)) && (longestMethod == null || longestMethod.getParameterTypes().length < method.getParameterTypes().length)) {
                PreventLog.v("found " + hookClass.getSimpleName() + "." + methodName + ": " + method);
                longestMethod = method;
            }
        }
        return longestMethod;
    }

    private static void hookLongestMethod(Class<?> hookClass, String methodName, XC_MethodHook hook) {
        hookLongestMethod(hookClass, methodName, null, hook);
    }

    private static void hookLongestMethod(Class<?> hookClass, String methodName, String returnName, XC_MethodHook hook) {
        Method method = findLongestMethod(hookClass, methodName, returnName);
        if (method == null) {
            PreventLog.e("cannot find " + hookClass.getSimpleName() + "." + methodName);
        } else {
            XposedBridge.hookMethod(method, hook);
            PreventLog.d("hooked " + hookClass.getSimpleName() + "." + methodName);
        }
    }

    private static Object getRecordForAppLocked(Object activityManagerService, Object thread) {
        if (getRecordForAppLocked == null) {
            return null;
        }
        try {
            return getRecordForAppLocked.invoke(activityManagerService, thread);
        } catch (IllegalAccessException e) {
            PreventLog.d("cannot access getRecordForAppLocked", e);
        } catch (InvocationTargetException e) {
            PreventLog.d("cannot invoke getRecordForAppLocked", e);
        }
        return null;
    }

    private static void exportActivityIfNeeded() {
        hookLongestMethod(PackageParser.class, "parseActivity", new ExportedActivityHook());
    }

    private static void hookActivity(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> applicationThread = Class.forName("android.app.ApplicationThreadProxy", false, classLoader);
        hookLongestMethod(applicationThread, "scheduleLaunchActivity", new StartActivityHook());

        hookLongestMethod(applicationThread, "scheduleResumeActivity", new ResumeActivityHook());

        hookLongestMethod(applicationThread, "schedulePauseActivity", new PauseActivityHook());

        hookLongestMethod(applicationThread, "scheduleDestroyActivity", new DestroyActivityHook());
    }

    public static class AppDiedHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Boolean restarting = (Boolean) param.args[0x1];
            Object processRecord = param.args[0];
            if (!restarting && !ProcessRecordUtils.isKilledByAm(processRecord)) {
                SystemHook.onAppDied(processRecord);
            }
        }
    }

    public static class BackActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Boolean result = (Boolean) param.getResult();
            if (result) {
                Object activityRecord = ActivityRecordUtils.getActivityRecord(param.args[0x0]);
                SystemHook.onMoveActivityToBack(activityRecord);
            }
        }
    }

    public static class HomeActivityHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Intent intent = null;
            for (Object arg : param.args) {
                if (arg instanceof Intent) {
                    intent = (Intent) arg;
                    break;
                }
            }
            Object processRecord = getRecordForAppLocked(param.thisObject, param.args[0]);
            ApplicationInfo info = ProcessRecordUtils.getInfo(processRecord);
            if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
                String sender = info == null ? "" : info.packageName;
                PreventLog.v("start activity, intent: " + intent + ", sender: " + sender);
                SystemHook.onStartHomeActivity(sender);
            }
        }
    }

    public static class ContextHook extends XC_MethodHook {

        private final ThreadLocal<String> context;

        public ContextHook(ThreadLocal<String> context) {
            this.context = context;
        }

        @Override
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            Object processRecord = getRecordForAppLocked(param.thisObject, param.args[0]);
            ApplicationInfo info = ProcessRecordUtils.getInfo(processRecord);
            String sender = info == null ? "" : info.packageName;
            context.set(sender);
        }

        @Override
        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            context.remove();
        }
    }

    public static class IntentContextHook extends ContextHook {
        public IntentContextHook(ThreadLocal<String> context) {
            super(context);
        }

        @Override
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            Intent intent = (Intent) param.args[0x1];
            if (intent != null) {
                String action = intent.getAction();
                if (AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)) {
                    SafeActionUtils.updateWidget(intent.getComponent(), true);
                } else if (AppWidgetManager.ACTION_APPWIDGET_DISABLED.equals(action)) {
                    SafeActionUtils.updateWidget(intent.getComponent(), false);
                }
            }
        }
    }

    public static class IgnoreDependencyHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String reason = String.valueOf(param.args[param.args.length - 1]);
            if (reason.startsWith("stop ")) {
                Object processRecord = param.args[0];
                // 0x5 = "stop "
                String packageName = reason.substring(0x5);
                String killPackageName = ProcessRecordUtils.getInfo(processRecord).packageName;
                if (GmsUtils.isGms(packageName) && !GmsUtils.isGms(killPackageName)) {
                    XposedHelpers.setBooleanField(processRecord, "removed", false);
                    param.setResult(false);
                }
            }
        }
    }

    public static class ProcessHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String sender = SERVICE_SENDER.get();
            if (sender == null) {
                sender = RECEIVER_SENDER.get();
            }
            if (!ActivityManagerServiceHook.hookBeforeStartProcessLocked(param.thisObject, param.args, sender)) {
                param.setResult(null);
            }
        }
    }

    public static class IntentExcludingStoppedHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            Boolean result = (Boolean) param.getResult();
            if (result != null && result && IntentFilterHook.isPrevent((Intent) param.thisObject)) {
                param.setResult(false);
            }
        }
    }

    public static class IntentFilterMatchHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (IntentFilterHook.canHook((Integer) param.getResult())) {
                Object filter = param.thisObject;
                String action = (String) param.args[0];
                IntentFilterMatchResult result;
                if (filter instanceof PackageParser.ActivityIntentInfo) {
                    result = IntentFilterHook.hookActivityIntentInfo((PackageParser.ActivityIntentInfo) filter, RECEIVER_SENDER.get(), action);
                } else if (filter instanceof PackageParser.ServiceIntentInfo) {
                    result = IntentFilterHook.hookServiceIntentInfo((PackageParser.ServiceIntentInfo) filter, SERVICE_SENDER.get(), action);
                } else if (BroadcastFilterUtils.isBroadcastFilter(filter)) {
                    result = IntentFilterHook.hookBroadcastFilter(filter, param.args);
                } else {
                    result = IntentFilterMatchResult.NONE;
                }
                if (!result.isNone()) {
                    param.setResult(result.getResult());
                }
            }
        }

    }

    public static class CleanUpRemovedHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            ActivityManagerServiceHook.hookAfterCleanUpRemovedTaskLocked(param.args);
        }
    }

    public static class ExportedActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            PackageParser.Activity result = (PackageParser.Activity) param.getResult();
            if (result == null) {
                return;
            }
            ActivityInfo info = result.info;
            if (BuildConfig.ALIPAY_DONATE && PreventIntent.NAME_ALIPAY.equals(info.packageName) && PreventIntent.CLASS_ALIPAY.equals(info.name)) {
                info.exported = true;
            }
            if (BuildConfig.WECHAT_DONATE && PreventIntent.NAME_WECHAT.equals(info.packageName) && PreventIntent.CLASS_WECHAT.equals(info.name)) {
                info.exported = true;
            }
        }
    }

    public static class StartActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object activityRecord = ActivityRecordUtils.getActivityRecord(param.args[0x1]);
            SystemHook.onStartActivity(activityRecord);
        }
    }

    public static class ResumeActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object activityRecord = ActivityRecordUtils.getActivityRecord(param.args[0x0]);
            SystemHook.onResumeActivity(activityRecord);
        }
    }

    public static class PauseActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object activityRecord = ActivityRecordUtils.getActivityRecord(param.args[0x0]);
            Boolean userLeaving = (Boolean) param.args[0x2];
            if (userLeaving) {
                SystemHook.onUserLeavingActivity(activityRecord);
            }
        }
    }

    public static class DestroyActivityHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object activityRecord = ActivityRecordUtils.getActivityRecord(param.args[0x0]);
            SystemHook.onDestroyActivity(activityRecord);
        }
    }

}
