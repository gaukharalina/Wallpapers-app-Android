package com.example.viewmodel

import android.graphics.Bitmap
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.ImageConfig
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed class WallpaperUiState {
    object Idle : WallpaperUiState()
    object Loading : WallpaperUiState()
    data class Success(val images: List<String>) : WallpaperUiState() // Base64 strings
    data class Error(val message: String) : WallpaperUiState()
}

class WallpaperViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<WallpaperUiState>(WallpaperUiState.Idle)
    val uiState: StateFlow<WallpaperUiState> = _uiState.asStateFlow()

    private val _referenceImage = MutableStateFlow<String?>(null)
    val referenceImage: StateFlow<String?> = _referenceImage.asStateFlow()

    fun setReferenceImage(base64Image: String?) {
        _referenceImage.value = base64Image
    }

    fun generateWallpapers(prompt: String) {
        _uiState.value = WallpaperUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    _uiState.value = WallpaperUiState.Error("Please configure your Gemini API Key in the Secrets panel.")
                    return@launch
                }

                val parts = mutableListOf<Part>()
                parts.add(Part(text = prompt))
                
                _referenceImage.value?.let { refImage ->
                    parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = refImage)))
                }

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = parts)),
                    generationConfig = GenerationConfig(
                        imageConfig = ImageConfig(aspectRatio = "9:16", imageSize = "1K"),
                        responseModalities = listOf("TEXT", "IMAGE")
                    )
                )

                // Make 4 parallel calls to get 4 variations
                val deferreds = (1..4).map {
                    async {
                        try {
                            val response = RetrofitClient.service.generateContent(apiKey, request)
                            val base64 = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it.inlineData != null }?.inlineData?.data
                            base64
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }
                }

                val results = deferreds.awaitAll().filterNotNull()

                if (results.isNotEmpty()) {
                    _uiState.value = WallpaperUiState.Success(results)
                } else {
                    _uiState.value = WallpaperUiState.Error("Failed to generate images.")
                }
            } catch (e: Exception) {
                _uiState.value = WallpaperUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
