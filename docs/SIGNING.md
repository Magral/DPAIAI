# Постоянная подпись DPI Pilot

Публичные APK выпускаются workflow **Signed APK release**. Сборка и тесты
проверяются в release-варианте, сертификат APK сверяется с закреплённым SHA-256.
При отсутствии ключа выпуск останавливается. Debug CI остаётся только для
проверки кода; старый workflow публикации debug APK отключён.

## Однократная настройка

Создайте в безопасном месте PKCS12 keystore с alias `dpipilot`, RSA 4096,
сроком 10000 дней. Пароль хранилища и ключа должен совпадать. Пример:
`keytool -genkeypair -keystore dpipilot-release.p12 -storetype PKCS12 -alias dpipilot -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=DPI Pilot"`
Keytool запросит пароль интерактивно.

В Settings → Secrets and variables → Actions добавьте repository secrets:

| Secret | Значение |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Содержимое файла p12 в Base64; это кодировка, не шифрование |
| ANDROID_KEYSTORE_PASSWORD | Пароль хранилища и ключа |
| ANDROID_SIGNING_CERT_SHA256 | SHA-256 сертификата из `keytool -list -v -keystore dpipilot-release.p12 -alias dpipilot` |

Не публикуйте keystore, Base64 и пароль в репозитории, логах или чате.
Сохраните резервную копию keystore и пароля отдельно от GitHub.
Не генерируйте новый ключ для каждого релиза.

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

