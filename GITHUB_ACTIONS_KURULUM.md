# GitHub Actions ile Build Alma

## Secrets nasıl eklenir

1. GitHub'da repoyu oluştur (henüz oluşturmadıysan)
2. Repo sayfasında: **Settings** → **Secrets and variables** → **Actions**
3. **New repository secret** butonuna her biri için tıkla, aşağıdaki isim/değer çiftlerini ekle:

| Secret adı | Değer |
|---|---|
| `MAPS_API_KEY` | Google Maps API key'in |
| `PLANET_API_KEY` | Planet API key'in |
| `COPERNICUS_CLIENT_ID` | Copernicus/Sentinel Hub OAuth client ID |
| `COPERNICUS_CLIENT_SECRET` | Copernicus/Sentinel Hub OAuth client secret |
| `USGS_USER` | USGS kullanıcı adın |
| `USGS_M2M_TOKEN` | USGS M2M application token |
| `EARTHDATA_TOKEN` | NASA Earthdata token |
| `GEE_PROJECT_ID` | GEE proje ID'si (örn. direct-pixel-498308-e0) |
| `GEE_SERVICE_ACCOUNT_JSON` | GEE service account JSON dosyasının TAM İÇERİĞİ (aşağıya bak) |

## GEE_SERVICE_ACCOUNT_JSON nasıl eklenir

Bu secret'ın değeri, indirdiğin `gee_service_account.json` dosyasının **tüm içeriği** olacak
(tek satır JSON, normalde dosya zaten tek satır halinde gelir). Şöyle yap:

1. `gee_service_account.json` dosyasını bir metin editöründe aç
2. İçeriğin TAMAMINI kopyala (Ctrl+A, Ctrl+C ya da telefonda tüm metni seç-kopyala)
3. GitHub'da yeni secret oluştururken Value alanına yapıştır

GEE kullanmıyorsan bu secret'ı boş bırakabilirsin, workflow dosyası bu durumu otomatik atlar.

## Build nasıl tetiklenir

- `main` veya `master` branch'ine her push'ta otomatik tetiklenir
- Ya da Actions sekmesinden **Build Debug APK** workflow'unu seçip **Run workflow** ile manuel tetikleyebilirsin

## APK nasıl indirilir

1. Repo sayfasında **Actions** sekmesine git
2. Tamamlanan workflow çalışmasına tıkla
3. Sayfanın altında **Artifacts** bölümünde `arkeosar-sat-debug-apk` görünecek, tıkla indir
4. İndirilen zip'i aç, içindeki `.apk` dosyasını telefonuna kur

## Güvenlik notu

GitHub Secrets, repo'nun normal kod içeriğinden ayrı, şifrelenmiş şekilde saklanır ve
workflow loglarında maskelenir (değerler `***` olarak görünür). Yine de bu repoyu
**public** yapmaman, private tutman önerilir - secrets güvenli olsa da, kod içindeki
yorumlar/yapı bazen istemeden bilgi sızdırabilir.
