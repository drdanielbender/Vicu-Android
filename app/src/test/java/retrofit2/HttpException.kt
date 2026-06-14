package retrofit2

import okhttp3.ResponseBody

class Response<T> private constructor(private val code: Int) {
    fun code(): Int = code

    companion object {
        fun <T> error(code: Int, body: ResponseBody): Response<T> {
            return Response(code)
        }
    }
}

class HttpException(private val response: Response<*>) : RuntimeException("HTTP ${response.code()}") {
    fun code(): Int = response.code()
    fun response(): Response<*> = response
}
