package me.piebridge.prevent.framework;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import me.piebridge.forcestopgb.BuildConfig;
import me.piebridge.prevent.common.GmsUtils;
import me.piebridge.prevent.framework.util.AccountWatcher;
import me.piebridge.prevent.framework.util.LogUtils;
import me.piebridge.prevent.framework.util.SafeActionUtils;
import me.piebridge.prevent.framework.util.TaskRecordUtils;

/**
 * Created by thom on 15/8/11.
 */
public class ActivityManagerServiceHook {

    private static Context mContext;
    private static Map<String, Boolean> mPreventPackages;
    // normally, there is only one
    private static Collection<String> settingsPackages = new HashSet<String>();
    private static AccountWatcher mAccountWatcher;

    private ActivityManagerServiceHook() {

    }

    public static void setContext(Context context, Map<String, Boolean> preventPackages) {
        mContext = context;
        mPreventPackages = preventPackages;
        mAccountWatcher = new AccountWatcher(context);
    }

    public static boolean hookBeforeStartProcessLocked(Object thiz, Object[] args, String sender) {
        ApplicationInfo info = (ApplicationInfo) args[0x1];
        String hostingType = (String) args[0x4];
        ComponentName hostingName = (ComponentName) args[0x5];
        String packageName = info.packageName;

        PreventLog.v("startProcessLocked, packageName: " + packageName + ", hostingType: " + hostingType);
        if (mPreventPackages == null && ("content provider".equals(hostingType) || "broadcast".equals(hostingType))) {
            SystemHook.retrievePreventsIfNeeded(thiz);
        }

        if (mContext == null) {
            return true;
        }

        boolean prevents = Boolean.TRUE.equals(mPreventPackages.get(packageName));
        if ("activity".equals(hostingType)) {
            SystemHook.cancelCheck(packageName);
            SystemHook.updateRunningGapps(packageName, true);
            if (prevents) {
                // never block activity
                mPreventPackages.put(packageName, false);
                prevents = false;
            }
            LogUtils.logStartProcess(packageName, hostingType, hostingName, sender);
        }

        return !prevents || hookDependency(hostingName, hostingType, packageName, sender);
    }

    private static boolean hookDependency(ComponentName hostingName, String hostingType, String packageName, String sender) {
        if (packageName.equals(sender)) {
            LogUtils.logStartProcess(packageName, hostingType + "(self)", hostingName, sender);
            return true;
        }
        if ("broadcast".equals(hostingType)) {
            // always block broadcast
            return hookBroadcast(hostingName, hostingType, packageName, sender);
        } else if ("service".equals(hostingType)) {
            return hookService(hostingName, hostingType, packageName, sender);
        } else if ("content provider".equals(hostingType) && !SystemHook.isSystemPackage(packageName)) {
            LogUtils.logStartProcess(true, packageName, hostingType, hostingName, sender);
            return false;
        }

        SystemHook.checkRunningServices(packageName, false);
        LogUtils.logStartProcess(packageName, hostingType + "(should safe)", hostingName, sender);
        return true;
    }

    private static boolean hookBroadcast(ComponentName hostingName, String hostingType, String packageName, String sender) {
        if (SafeActionUtils.isSafeBroadcast(hostingName)) {
            SystemHook.checkRunningServices(packageName, false);
            LogUtils.logStartProcess(packageName, hostingType + "(safe)", hostingName, sender);
            return true;
        } else {
            LogUtils.logStartProcess(true, packageName, hostingType, hostingName, sender);
            return false;
        }
    }

    private static boolean hookService(ComponentName hostingName, String hostingType, String packageName, String sender) {
        if (SystemHook.isFramework(sender) && SafeActionUtils.isSyncService(mContext, hostingName)) {
            return hookSyncService(hostingName, hostingType, packageName, sender);
        }
        if (GmsUtils.isGms(packageName)) {
            return hookGmsService(hostingName, hostingType, packageName, sender);
        }
        if (sender != null && cannotPrevent(sender, packageName)) {
            SystemHook.checkRunningServices(packageName, true);
            LogUtils.logStartProcess(packageName, hostingType, hostingName, sender);
            return true;
        }
        LogUtils.logStartProcess(true, packageName, hostingType, hostingName, sender);
        return false;
    }

    private static boolean hookGmsService(ComponentName hostingName, String hostingType, String packageName, String sender) {
        if (cannotPreventGms(hostingName, sender)) {
            // only allow gapps to use gms
            SystemHook.checkRunningServices(packageName, true);
            LogUtils.logStartProcess(packageName, hostingType, hostingName, sender);
            return true;
        } else {
            LogUtils.logStartProcess(true, packageName, hostingType, hostingName, sender);
            return false;
        }
    }

    private static void retrieveSettingsPackage(PackageManager pm, Collection<String> packages) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", BuildConfig.APPLICATION_ID, null));
        for (ResolveInfo resolveInfo : pm.queryIntentActivities(intent, 0)) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (SystemHook.isSystemPackage(packageName)) {
                PreventLog.d("add " + packageName + " as settings");
                packages.add(packageName);
            }
        }
    }

    private static boolean cannotPreventGms(ComponentName component, String sender) {
        if (GmsUtils.isGmsRegister(mContext, component)) {
            return true;
        }
        PackageManager pm = mContext.getPackageManager();
        if (settingsPackages.isEmpty()) {
            retrieveSettingsPackage(pm, settingsPackages);
        }
        if (settingsPackages.contains(sender)) {
            return true;
        }
        if (!GmsUtils.isGapps(pm, sender)) {
            return false;
        }
        return pm.getLaunchIntentForPackage(sender) == null || SystemHook.hasRunningActivity(sender);
    }

    private static boolean cannotPrevent(String sender, String packageName) {
        if (SystemHook.cannotPrevent(sender)) {
            // the sender cannot be prevent
            return true;
        } else if (mContext.getPackageManager().getLaunchIntentForPackage(sender) == null) {
            // allow the sender has no launcher
            return true;
        } else if (SystemHook.isSystemPackage(packageName) && SystemHook.hasRunningActivity(sender)) {
            // allow third-party app to call system package (except gms)
            return true;
        }
        return false;
    }

    private static boolean hookSyncService(ComponentName hostingName, String hostingType, String packageName, String sender) {
        if (mAccountWatcher.isComponentSyncable(hostingName)) {
            handleSafeService(packageName);
            SystemHook.checkRunningServices(packageName, true);
            LogUtils.logStartProcess(packageName, hostingType + "(sync)", hostingName, sender);
            return true;
        } else {
            LogUtils.logStartProcess(true, packageName, hostingType + "(sync)", hostingName, sender);
            return false;
        }
    }

    private static void handleSafeService(String packageName) {
        if (Boolean.TRUE.equals(mPreventPackages.get(packageName))) {
            PreventLog.i("allow " + packageName + " for next service/broadcast");
            mPreventPackages.put(packageName, false);
            SystemHook.restoreLater(packageName);
        }
    }

    public static boolean hookAfterCleanUpRemovedTaskLocked(Object[] args) {
        String packageName = TaskRecordUtils.getPackageName(args[0]);
        if (!shouldKillProcess(args[1])) {
            return false;
        }
        SystemHook.updateRunningGapps(packageName, false);
        if (packageName != null && mPreventPackages != null && mPreventPackages.containsKey(packageName)) {
            mPreventPackages.put(packageName, true);
            LogUtils.logForceStop("removeTask", packageName, "force in " + SystemHook.TIME_IMMEDIATE + "s");
            SystemHook.forceStopPackageForce(packageName, SystemHook.TIME_IMMEDIATE);
        }
        return true;
    }

    private static boolean shouldKillProcess(Object killProcess) {
        if (killProcess == null) {
            return false;
        }
        if (killProcess instanceof Boolean) {
            return (Boolean) killProcess;
        } else if (killProcess instanceof Integer) {
            Integer flags = (Integer) killProcess;
            return (flags & 0x1) != 0;
        }
        return false;
    }

}
