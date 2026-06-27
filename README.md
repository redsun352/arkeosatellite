# ArkeoSAR Sat

Polygon tabanlı, uydu/GIS verisiyle yeraltı anomali tespiti yapan Android uygulaması.

## Akış
1. Google Maps üzerinde dokunarak taranacak alan (polygon) çizilir
2. Polygon kapatıldığında "Analizi Başlat" aktif olur
3. Seçilen uydu kaynaklarından (şu an: Copernicus/Sentinel-2 NDVI, USGS/Landsat TIRS termal) bölge verisi çekilir
4. Polygon İÇİNDEKİ hücreler için anomali skoru hesaplanır (polygon dışı analiz edilmez)
5. Sonuç ekranında özet gösterilir (V2: harita üzerinde heatmap overlay)

## Kurulum
1. `local.properties.example` dosyasını `local.properties` olarak kopyalayın
2. Kendi API anahtarlarınızı doldurun (ÖNCEDEN PAYLAŞILMIŞ ANAHTARLARI KULLANMAYIN - rotate edin)
3. GEE kullanacaksanız: service account JSON dosyasını `app/src/main/assets/gee_service_account.json` olarak ekleyin
4. Android Studio'da açın, Gradle sync yapın, çalıştırın

## Mimari notları
- `network/` paketi her uydu kaynağı için ayrı alt paket içerir (copernicus, planet, usgs, gee)
- Her kaynak `SatelliteDataSource` arayüzünü implemente eder
- `gis/GeoTiffDecoder`: sıfırdan yazılmış, test edilmiş minimal TIFF okuyucu (tek bant, sıkıştırmasız)
- `gis/AnalysisOrchestrator`: kaynakları paralel çağırır, polygon-içi filtreleme (PolyUtil.containsLocation) yapar

## Bilinen sınırlamalar / TODO
- PlanetDataSource: çok-bantlı GeoTIFF desteği henüz yok (GeoTiffDecoder tek bant)
- UsgsM2mApi: `login-token` request body alan adları (`username`/`token`) doğrulanmamış varsayım - ilk gerçek çağrıda kontrol edin
- ResultActivity: V1 sadece metin özeti gösteriyor, harita overlay V2'de eklenecek
- GEE: expression-graph entegrasyonu henüz yapılmadı (gee_validation.py ile doğrulama bekleniyor)
