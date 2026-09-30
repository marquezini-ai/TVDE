package com.daniel.tvdeinsight.data.sheets

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class SheetsHttpException(
    val statusCode: Int,
    message: String
) : IOException(message) {
    val isRetryable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

object SheetsFailurePolicy {
    fun shouldRetry(error: Throwable): Boolean = when (error) {
        is SheetsHttpException -> error.isRetryable
        is UnknownHostException, is SocketTimeoutException, is IOException -> true
        else -> false
    }
}
