package com.xkdlabs.simpledatalogs

/** Marks plumbing shared by the core and the Android library; it may change in any release. */
@RequiresOptIn(
    message = "This is SimpleDataLogs plumbing for the Android library and tests; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalSimpleDataLogsApi
