package com.xkdlabs.simpledatalogs

/** Receives the Play Store install referrer, or null when there is none or it can't be read. */
public fun interface InstallReferrerCallback {
    /** Called once, on the main thread. */
    public fun onResult(installReferrer: String?)
}
