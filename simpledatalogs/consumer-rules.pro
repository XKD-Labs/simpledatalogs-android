# SimpleDataLogs uses no reflection beyond checking for Google's install referrer library.
# Apps that never add that library must not see R8 warnings about it.
-dontwarn com.android.installreferrer.**
