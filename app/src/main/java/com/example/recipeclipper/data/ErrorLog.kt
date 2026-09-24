package com.example.recipeclipper.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Where the repositories report a database failure they have swallowed. A seam rather than a
 * direct `android.util.Log` call because `Log` is an unmocked stub in JVM tests and throws
 * there; tests pass a recording one. [AndroidErrorLog] is the real one.
 */
fun interface ErrorLog {
    fun error(message: String, cause: Throwable)
}

object AndroidErrorLog : ErrorLog {
    private const val TAG = "RecipeClipper"
    override fun error(message: String, cause: Throwable) {
        Log.e(TAG, message, cause)
    }
}

/**
 * Runs one database call, and if it throws, logs it and returns [fallback] instead of letting
 * the exception reach `viewModelScope`, where it would crash the app. SQLite failures (disk
 * full, a corrupt file, a locked database) and Room's `IllegalStateException`s are what this is
 * for. [CancellationException] is always rethrown, so cancelling a caller still works.
 */
internal suspend fun <T> ErrorLog.guard(what: String, fallback: T, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        error("$what failed", e)
        fallback
    }

/**
 * A failed query ends a Room Flow with an exception, which would crash whatever collects it in
 * `viewModelScope`. This logs it and emits one empty list instead, so the screen shows its
 * empty state. `catch` never intercepts cancellation.
 */
internal fun <T> Flow<List<T>>.orEmptyOnError(log: ErrorLog, what: String): Flow<List<T>> =
    catch { e ->
        log.error("$what failed", e)
        emit(emptyList())
    }
