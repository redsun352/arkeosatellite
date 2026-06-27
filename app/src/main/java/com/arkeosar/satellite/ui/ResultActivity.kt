package com.arkeosar.satellite.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.arkeosar.satellite.databinding.ActivityResultBinding

/**
 * V1: Basit metin özeti gösterir (kaç hücre analiz edildi, hangi kaynaklar kullanıldı).
 *
 * TODO (V2): Google Maps üzerinde polygon sınırı içinde renkli heatmap overlay'i.
 * AnalysisResult.cells listesi şu an Intent ile taşınmıyor (boyutu büyük olabilir);
 * V2'de bir ViewModel/repository üzerinden Activity'ler arası paylaşılmalı, ya da
 * sonucu yerel bir cache'e (Room/dosya) yazıp ResultActivity'nin sadece bir ID
 * taşıması daha doğru bir mimari olur.
 */
class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CELL_COUNT = "extra_cell_count"
        const val EXTRA_SOURCES = "extra_sources"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cellCount = intent.getIntExtra(EXTRA_CELL_COUNT, 0)
        val sourcesText = intent.getStringExtra(EXTRA_SOURCES) ?: "-"

        binding.summaryText.text = buildString {
            append("Polygon içinde analiz edilen hücre sayısı: $cellCount\n\n")
            append("Kullanılan uydu kaynakları: $sourcesText")
        }
    }
}
