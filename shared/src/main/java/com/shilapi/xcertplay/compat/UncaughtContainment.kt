package com.shilapi.xcertplay.compat

import com.shilapi.xcertplay.network.isMdnsOwnedThread

/**
 * Whether an uncaught throwable may be recorded and ignored instead of ending the process.
 *
 * On Dalvik a framework class or method this ROM lacks fails at first link with a [LinkageError]
 * (`NoClassDefFoundError`, `NoSuchMethodError`, `VerifyError`). That is a platform capability gap,
 * not a broken session: the thread that touched it is finished either way, but letting it reach the
 * platform handler also takes the process down, which the user sees as the app 闪退到桌面 and which
 * leaves the iPhone holding a half-open CarPlay session that then refuses every reconnect attempt.
 * A background thread is therefore contained; the main thread is not, because a failure there has
 * already lost the UI and only a process restart can present a fresh one.
 *
 * Threads a library owns and names itself are contained for any throwable — see [isMdnsOwnedThread] —
 * because the application cannot wrap them. Every other exception type keeps failing loudly so an
 * ordinary bug stays visible.
 */
fun uncaughtFailureIsContained(thread: Thread, error: Throwable, mainThread: Thread?): Boolean =
    isMdnsOwnedThread(thread.name) ||
        (error is LinkageError && !isSameThread(thread, mainThread))

/**
 * Identity where the platform could tell us, name only as a fallback: a main looper that was not
 * prepared yet must not turn the UI thread into a background one and silently swallow its death.
 */
internal fun isSameThread(thread: Thread, other: Thread?): Boolean =
    if (other != null) thread === other else thread.name == MAIN_THREAD_NAME

private const val MAIN_THREAD_NAME = "main"
