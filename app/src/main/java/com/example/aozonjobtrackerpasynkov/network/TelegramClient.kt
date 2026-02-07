package com.example.aozonjobtrackerpasynkov.network

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class TelegramClient(
    private val botToken: String,
    private val chatId: String
) {
    private val client = OkHttpClient()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun sendMessage(text: String, onResult: (Boolean, String?) -> Unit) {
        if (botToken.isBlank() || chatId.isBlank()) {
            onResult(false, "Bot token or Chat ID is blank")
            return
        }

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }

        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TelegramClient", "Failed to send message", e)
                onResult(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        onResult(true, null)
                    } else {
                        val errorBody = it.body?.string()
                        Log.e("TelegramClient", "Error response: $errorBody")
                        onResult(false, "Error: ${it.code} $errorBody")
                    }
                }
            }
        })
    }
}
