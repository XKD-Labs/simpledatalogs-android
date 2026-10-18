package com.xkdlabs.simpledatalogs

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Turns activity callbacks into foreground, background and screen-view moments; runs on the main thread. */
internal class AppLifecycleObserver(
    /** Called when the app enters the foreground. */
    private val onEnteredForeground: () -> Unit,

    /** Called when the app leaves the foreground. */
    private val onLeftForeground: () -> Unit,

    /** Called with the activity's simple name each time one resumes, or null when screen views are off. */
    private val onScreenResumed: ((String) -> Unit)?,
) : Application.ActivityLifecycleCallbacks {
    /** Counts started activities. */
    private val foregroundTracker = ForegroundTracker()

    /** Reports entering the foreground. */
    override fun onActivityStarted(activity: Activity) {
        if (foregroundTracker.activityStarted() == ForegroundTracker.Transition.ENTERED_FOREGROUND) {
            onEnteredForeground()
        }
    }

    /** Sends a screen view, when asked for. */
    override fun onActivityResumed(activity: Activity) {
        onScreenResumed?.invoke(activity.javaClass.simpleName)
    }

    /** Reports leaving the foreground. */
    override fun onActivityStopped(activity: Activity) {
        if (foregroundTracker.activityStopped(activity.isChangingConfigurations) == ForegroundTracker.Transition.LEFT_FOREGROUND) {
            onLeftForeground()
        }
    }

    /** Not needed. */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    /** Not needed. */
    override fun onActivityPaused(activity: Activity) = Unit

    /** Not needed. */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** Not needed. */
    override fun onActivityDestroyed(activity: Activity) = Unit
}
