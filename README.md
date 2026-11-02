# RouterSpot

**English** · [Русский](#русский)

Android app that turns your phone into a VPN router. The phone shares a regular Wi-Fi hotspot, and all traffic of connected devices goes through your VPN. Nothing to install or configure on the laptop, TV or set-top box. **No root required.**

<p align="center">
  <img src="docs/screenshots/en-idle.png" width="270" alt="Sharing is off">
  <img src="docs/screenshots/en-active.png" width="270" alt="Sharing is on">
</p>

- One APK: the VPN core, the tunnel and the hotspot control are all inside.
- Supported links: `vless://` (Reality, TLS, plain) and NaiveProxy `https://user:pass@host:port`.
- Profile import from clipboard or QR code; hot switching between servers while sharing.
- No leaks: if the tunnel drops, clients lose internet instead of going online directly. IPv6 is not handed out to clients.
- Automatic reconnect when the phone switches between Wi-Fi and mobile data.
- Built-in diagnostics: phone, permissions, service and an end-to-end tunnel check.
- Interface in English and Russian.

## Requirements

- Android 13 or newer.
- Developer options and **Wireless debugging** enabled (needed once for pairing and after each reboot to start the service).
- A link to your VPN: your own server or one from a VPN provider.

## How it works

```
clients ──Wi-Fi──▶ phone hotspot ──▶ TUN (test network) ──▶ hev-socks5-tunnel ──▶ Xray ──▶ your VPN server ──▶ internet
```

Android only lets system processes route hotspot traffic through a custom tunnel. RouterSpot connects to the phone's own wireless debugging (like a computer over ADB) and starts a helper service with `shell` rights. The service:

1. creates a TUN interface through the hidden `TestNetworkManager` API;
2. makes it the hotspot upstream with `TetheringManager.setPreferTestNetworks`;
3. passes TCP, UDP and DNS from the TUN to the embedded Xray core via hev-socks5-tunnel.

The hotspot upstream is the tunnel only, so if the VPN fails, packets have nowhere to go. A watchdog in the service and in the app turns the hotspot off if sharing falls back to mobile data or the service dies.

The service lives until the phone reboots. After a reboot, connect to any Wi-Fi network, turn on wireless debugging for a minute and tap **Start service**. Pairing again is not needed.

## Getting started

1. Install the APK from [Releases](../../releases).
2. Follow the setup wizard: developer mode → wireless debugging → pairing code (entered right in the notification) → service start.
3. On the **Profiles** tab, add a VPN link from the clipboard or a QR code.
4. On the **Sharing** tab, tap **Share VPN** and connect your devices to the shown Wi-Fi network (or scan its QR code).

## Limitations

- Hidden APIs depend on the firmware: the mechanism may not work on some phones. Run **System → Diagnostics** to check.
- On phones that cannot run Wi-Fi and a hotspot at the same time, wireless debugging turns off when sharing starts; the service keeps working.
- NaiveProxy has no UDP: DNS is sent over TCP through the tunnel, other UDP is blocked (apps fall back to TCP).

## Building

Requires JDK 17 and the Android SDK (platform 35).

```sh
./scripts/fetch-deps.sh   # downloads Xray-core and hev-socks5-tunnel
./gradlew assembleDebug   # app/build/outputs/apk/debug/
```

## Built with

- [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0): VPN core.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT): TUN to SOCKS5.
- Jetpack Compose; Manrope and JetBrains Mono fonts (SIL OFL 1.1).

---

# Русский

[English](#routerspot) · **Русский**

Приложение для Android, которое превращает телефон в VPN-роутер. Телефон раздаёт обычный Wi-Fi, а весь трафик подключённых устройств идёт через ваш VPN. На ноутбуке, телевизоре или приставке ничего ставить и настраивать не нужно. **Root не нужен.**

<p align="center">
  <img src="docs/screenshots/ru-idle.png" width="270" alt="Раздача выключена">
  <img src="docs/screenshots/ru-active.png" width="270" alt="Раздача активна">
</p>

- Один APK: ядро VPN, туннель и управление хотспотом уже внутри.
- Поддерживаются ссылки `vless://` (Reality, TLS, без шифрования) и NaiveProxy `https://user:pass@host:port`.
- Импорт профилей из буфера обмена или по QR-коду, смена сервера на ходу без остановки раздачи.
- Без утечек: если туннель оборвётся, клиенты останутся без интернета, но не выйдут в сеть напрямую. IPv6 клиентам не выдаётся.
- Автоматическое переподключение, когда телефон переходит между Wi-Fi и мобильной сетью.
- Встроенная диагностика: телефон, разрешения, служба и сквозная проверка туннеля.
- Интерфейс на русском и английском.

## Что понадобится

- Android 13 или новее.
- Режим разработчика и **отладка по Wi-Fi** (один раз для сопряжения и после каждой перезагрузки для запуска службы).
- Ссылка на VPN: свой сервер или от провайдера VPN.

## Как это работает

```
клиенты ──Wi-Fi──▶ хотспот телефона ──▶ TUN (тестовая сеть) ──▶ hev-socks5-tunnel ──▶ Xray ──▶ ваш VPN-сервер ──▶ интернет
```

Раздавать трафик через свой туннель Android разрешает только системным процессам. RouterSpot подключается к беспроводной отладке самого телефона (как компьютер по ADB) и запускает служебный процесс с правами `shell`. Служба:

1. создаёт TUN-интерфейс через скрытый API `TestNetworkManager`;
2. назначает его выходом хотспота через `TetheringManager.setPreferTestNetworks`;
3. передаёт TCP, UDP и DNS из TUN во встроенное ядро Xray через hev-socks5-tunnel.

Выход хотспота — только туннель, поэтому при сбое VPN пакетам некуда идти. Сторож в службе и в приложении выключает хотспот, если раздача переключится на мобильную сеть или служба остановится.

Служба работает до перезагрузки телефона. После перезагрузки подключитесь к любому Wi-Fi, включите отладку по Wi-Fi на минуту и нажмите **«Запустить службу»**. Повторное сопряжение не нужно.

## Начало работы

1. Установите APK из [Releases](../../releases).
2. Пройдите мастер настройки: режим разработчика → отладка по Wi-Fi → код сопряжения (вводится прямо в уведомлении) → запуск службы.
3. На вкладке **«Профили»** добавьте ссылку на VPN из буфера или по QR-коду.
4. На вкладке **«Раздача»** нажмите **«Раздавать VPN»** и подключите устройства к показанной сети Wi-Fi (или отсканируйте её QR-код).

## Ограничения

- Скрытые API зависят от прошивки: на части телефонов механизм может не работать. Проверить можно в **«Система → Диагностика»**.
- На телефонах, которые не умеют одновременно Wi-Fi и хотспот, отладка выключится при включении раздачи; служба продолжит работать.
- У NaiveProxy нет UDP: DNS идёт по TCP через туннель, остальной UDP блокируется (приложения переходят на TCP).

## Сборка

Нужны JDK 17 и Android SDK (platform 35).

```sh
./scripts/fetch-deps.sh   # скачивает Xray-core и hev-socks5-tunnel
./gradlew assembleDebug   # app/build/outputs/apk/debug/
```

## На чём построено

- [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0): ядро VPN.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT): TUN → SOCKS5.
- Jetpack Compose; шрифты Manrope и JetBrains Mono (SIL OFL 1.1).
