package com.yoshiki.lifeagent.data

import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object Network {
    fun createGoalRepository(
        baseUrl: String,
        tokenProvider: suspend () -> String,
    ): GoalRepository {
        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient.Builder().readTimeout(35, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        return HttpGoalRepository(retrofit.create(GoalApi::class.java), tokenProvider)
    }
}
