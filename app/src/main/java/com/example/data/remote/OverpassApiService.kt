package com.example.data.remote

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OverpassElement(
    val type: String = "node",
    val id: Long = 0L,
    val lat: Double? = null,
    val lon: Double? = null,
    val tags: Map<String, String>? = null
)

interface OverpassApiService {
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun queryVicinityNodes(@Field("data") query: String): OverpassResponse

    companion object {
        private const val BASE_URL = "https://overpass-api.de/"

        fun create(): OverpassApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(OverpassApiService::class.java)
        }

        fun buildVicinityOverpassQuery(lat: Double, lon: Double, radiusMeters: Int = 1500): String {
            return """
                [out:json][timeout:15];
                (
                  node["internet_access"="wlan"](around:$radiusMeters,$lat,$lon);
                  node["internet_access"="yes"](around:$radiusMeters,$lat,$lon);
                  node["man_made"="surveillance"](around:$radiusMeters,$lat,$lon);
                );
                out body 60;
            """.trimIndent()
        }
    }
}
