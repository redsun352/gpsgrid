# GPSGrid PC — Mobile Internet

Telefonun mobil verisi üzerinden PC'ye GPS noktası göndermek için PC tarafı HTTP sunucusudur.

## Önerilen bağlantı

```text
Android (4G/5G)
      |
      v
Internet
      |
      v
Cloudflare Tunnel / güvenli HTTPS uç noktası
      |
      v
PC GPSGrid :8765
```

Yerel `192.168.x.x` adresleri yalnızca aynı LAN/Wi-Fi testleri içindir.

## Sunucuyu çalıştırma

```bash
pip install flask
python server.py
```

Sunucu `0.0.0.0:8765` üzerinde dinler.

## Mobil internet

PC'de Cloudflare Tunnel gibi bir HTTPS tüneli açılarak public URL elde edilir. Android uygulamasındaki **PC Server adresi** alanına bu HTTPS adresi girilir.

Örnek:

```text
https://gpsgrid.example.com
```

Android tarafı `/api/point` adresine HTTPS POST gönderir.

## Güvenlik

Public endpoint'i doğrudan internete kimlik doğrulamasız açmayın. Üretim sürümünde cihaz/saha tokenı ve HTTPS zorunlu tutulmalıdır.
