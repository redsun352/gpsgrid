# GPSGrid PC — Tailscale

Telefonun mobil verisi üzerinden PC'deki GPSGrid sunucusuna erişmek için Tailscale kullanılır.

## Bağlantı

```text
Android (4G/5G)
      |
      v
Tailscale
      |
      v
Windows PC — 100.x.x.x:8765
      |
      v
GPSGrid Flask Server
```

PC sunucusu `0.0.0.0:8765` üzerinde dinler; bu nedenle Tailscale arayüzündeki `100.x.x.x` adresinden erişilebilir.

## PC

```bash
pip install flask
python server.py
```

PC'de Tailscale çalışırken:

```powershell
tailscale ip
```

çıktısındaki IPv4 adresi Android uygulamasının **PC Server adresi** alanına yazılır. Örnek:

```text
http://100.95.116.23:8765
```

## Android

Android cihazında da Tailscale uygulaması açık ve PC ile aynı tailnet'e bağlı olmalıdır. GPSGrid APK'sı doğrudan Tailscale IPv4 adresine `/api/state`, `/api/heartbeat`, `/api/clients`, `/api/point` ve `/api/polygon/close` istekleri gönderir.

## Windows Firewall

İlk bağlantıda Windows Firewall TCP `8765` portuna izin vermiyorsa PowerShell'i yönetici olarak açıp:

```powershell
New-NetFirewallRule -DisplayName "GPSGrid Tailscale 8765" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -Profile Any
```

kuralını oluşturun.

## Test

PC'de:

```powershell
curl.exe http://100.95.116.23:8765/api/state
```

JSON dönüyorsa Tailscale üzerinden server erişilebilirdir.

## Güvenlik

Tailscale bağlantısı public internete açılmadığı için Cloudflare Tunnel gerekmez. Yine de tailnet erişimini yalnızca yetkili cihazlarla sınırlandırın.
