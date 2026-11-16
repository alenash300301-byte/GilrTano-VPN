# Обновления GilrTano VPN

Механизм обновления находится в настройках приложения: **Проверить обновления**. Он получает последний опубликованный GitHub Release, показывает описание изменений, скачивает APK, сверяет SHA-256 и передаёт APK установщику Android. Установка всегда подтверждается пользователем.

## Однократная настройка подписи

Android разрешает обновлять установленное приложение только APK с той же подписью. Поэтому ключ подписи нужно создать один раз и хранить в GitHub Secrets. Не добавляйте keystore или пароли в репозиторий и не отправляйте их в чат.

На Windows в PowerShell выполните:

```powershell
keytool -genkeypair -v -keystore gilrtano-release.jks -alias gilrtano-release -keyalg RSA -keysize 2048 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\gilrtano-release.jks")) | Set-Clipboard
```

Задайте пароль и сохраните его в надёжном месте. Затем откройте GitHub: **Settings → Secrets and variables → Actions → New repository secret** и создайте секреты:

- `ANDROID_KEYSTORE_BASE64` — Base64 из буфера обмена.
- `ANDROID_KEYSTORE_PASSWORD` — пароль хранилища.
- `ANDROID_KEY_ALIAS` — `gilrtano-release`.
- `ANDROID_KEY_PASSWORD` — пароль ключа.

## Публикация обновления

После добавления секретов создайте и отправьте тег версии, например:

```bash
git tag v0.9.11
git push origin v0.9.11
```

Workflow `.github/workflows/release-apk.yml` соберёт подписанный APK, создаст SHA-256 и опубликует GitHub Release. Тег должен быть новой версией выше установленной в приложении.

## Важное замечание о первой установке

Если GilrTano VPN уже установлен APK, подписанным другим ключом, Android не позволит обновить его APK с новым ключом. Для бесшовных обновлений нужно использовать тот же ключ, которым подписана текущая установленная версия. Если такого ключа нет, первый APK с новым ключом придётся установить отдельно; перед переустановкой сохраните профили и ссылки подписок.
