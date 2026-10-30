# Постоянная подпись DPI Pilot

Публичные APK выпускаются workflow **Signed APK release**. Сборка и тесты
проверяются в release-варианте, сертификат APK сверяется с закреплённым SHA-256.
При отсутствии ключа выпуск останавливается. Debug CI остаётся только для
проверки кода; старый workflow публикации debug APK отключён.

## Однократная настройка

Постоянный ключ уже создан. Его публичный SHA-256 закреплён в
`signing/certificate-sha256.txt`. В публичном репозитории нет закрытого ключа.

В Settings → Secrets and variables → Actions → New repository secret:

- Name: `DPI_PILOT_SIGNING`
- Secret: полное содержимое приватного файла `DPI-Pilot-signing-secret.txt`.

Файл содержит JSON: schema=1, keystore_base64 (PKCS12, alias dpipilot),
store_password, certificate_sha256. Это одновременно резервная копия
закрытого ключа и пароля. Храните её приватно; не добавляйте в репозиторий,
issues или сообщения. Base64 является кодировкой, а не шифрованием.
Не создавайте новый ключ при следующих релизах.

Скрипт восстановления сверяет сертификат keystore с закреплённым SHA-256,
а проверка APK повторно сверяет сертификат уже собранного приложения.
Пароль и Base64 маскируются в логах. Временный keystore удаляется после сборки.

После настройки: Actions → Signed APK release → Run workflow → main.
Workflow публикует APK прямо в GitHub Release без необходимости распаковки ZIP.
Увеличивайте versionCode и versionName перед каждым новым выпуском.

## Переход с 0.4.1

Ранее CI создавал временную debug-подпись. Для перехода на постоянный
сертификат потребуется один раз удалить старую установку. Настройки при
удалении теряются, локальный MTProxy нужно добавить заново.
Обновления с постоянным сертификатом сохраняют данные приложения.
Без старого закрытого ключа подписать совместимое обновление старого APK нельзя.

## Локальная release-сборка

Укажите ANDROID_KEYSTORE_PATH и ANDROID_KEYSTORE_PASSWORD, затем:
`bash gradlew assembleRelease testReleaseUnitTest`.
Для проверки: `ANDROID_SIGNING_CERT_SHA256=... bash scripts/verify-release.sh app/build/outputs/apk/release/app-release.apk`.

