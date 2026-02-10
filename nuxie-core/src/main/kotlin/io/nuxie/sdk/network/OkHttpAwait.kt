package io.nuxie.sdk.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.await(): Response {
  return suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (cont.isCancelled) return
        cont.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        cont.resume(response)
      }
    })

    cont.invokeOnCancellation {
      try {
        cancel()
      } catch (_: Exception) {}
    }
  }
}

