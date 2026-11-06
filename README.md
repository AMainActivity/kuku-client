# Куку! / Kuku!

Мессенджер на Kotlin Multiplatform с общим интерфейсом Compose Multiplatform для Android и iOS.
Этот репозиторий содержит клиент; для обмена сообщениями нужен совместимый сервер.

## Возможности

- Вход по email-коду, управление профилем и сессиями.
- Личные и групповые чаты, ответы на сообщения и вложения.
- Локальная история, черновики и очередь отправки для работы без сети.
- Обновления через WebSocket и восстановление соединения.
- Поиск пользователей, управление группами и администрирование с учётом прав доступа.

На Android авторизованное соединение обслуживает foreground-служба. История чатов читается
из Room и пополняется с сервера страницами; сервер не нужен для просмотра уже сохранённой истории.

## Технологии

| Область | Библиотеки |
|---|---|
| Интерфейс | Compose Multiplatform, Material 3 |
| Состояние и навигация | Lifecycle ViewModel, Coroutines / Flow, Navigation 3 |
| DI | Koin |
| Сеть | Ktor, WebSocket, kotlinx.serialization |
| Хранение | Room Multiplatform, SQLite |
| Медиа на Android | Coil, CameraX, Media3 |

Версии зависимостей и инструментов собраны в [version catalog](gradle/libs.versions.toml).

## Быстрый старт

### Android

Требуются JDK 21 и Android SDK Platform 37. Минимальная версия устройства — Android 8.0 (API 26).

1. Откройте корень репозитория в Android Studio с поддержкой используемых Kotlin и Android Gradle Plugin.
2. Укажите JDK 21 для Gradle. Путь к Android SDK задайте через `ANDROID_HOME` или `sdk.dir` в локальном `local.properties`.
3. Дождитесь синхронизации Gradle и запустите конфигурацию модуля `androidApp`.

Сборка APK из PowerShell:

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

На macOS/Linux ту же задачу можно вызвать через `sh ./gradlew :androidApp:assembleDebug`.
APK появится в `androidApp/build/outputs/apk/debug/`.

### iOS

Требуются macOS, Xcode с iOS SDK, JDK 21 и Android SDK для конфигурации общей Gradle-сборки.
Минимальная версия приложения в Xcode-проекте — iOS 17. Подключены цели iOS ARM64 и симулятор ARM64.

Откройте `iosApp/iosApp.xcodeproj`, выберите схему `iosApp` и симулятор.
Для запуска на устройстве настройте Signing & Capabilities своей команды Apple Developer.

### Подключение к серверу

При первом запуске укажите хост и порт своего сервера с корректным TLS-сертификатом.
После проверки соединения введите разрешённый на сервере email и полученный код.
Настройка сервера, допуска пользователей и отправки писем выполняется отдельно от клиента.

## Структура проекта

| Модуль / каталог | Назначение |
|---|---|
| `androidApp`, `iosApp` | Платформенные приложения |
| `shared-ui` | Общая оболочка, навигация и сборка DI |
| `feature-*` | Экраны и ViewModel отдельных функций |
| `shared-domain` | Модели приложения и интерфейсы репозиториев |
| `shared-data` | Сеть, Room и реализации репозиториев |
| `core-runtime` | Жизненный цикл соединения и фоновой службы |
| `core-ui` | Общие компоненты интерфейса и ресурсы |
| `build-logic` | Общие Gradle-плагины и правила зависимостей модулей |
| `contracts` | Локальный серверный контракт для тестов (не хранится в Git) |
| `ci` | Скрипты проверок Android и iOS |

Архитектура — feature-модули, MVVM и разделение presentation/domain/data.
Features работают с интерфейсами domain; реализации подключаются в общем DI-контейнере.

## Проверки и сборка релиза

Android: debug/release, AAB, host-тесты, общие Kotlin metadata и lint:

```powershell
.\ci\check-android.ps1
```

iOS: native-тесты и сборки Xcode без подписи, выполняются на macOS:

```sh
sh ci/check-ios.sh
```

Контракт не хранится в Git. Перед полным локальным прогоном тестов разместите
его файлы в `contracts/v0.1.0` или передайте внешний каталог через Gradle-параметр
`-PcontractDirectory=<каталог>`. Для Android-скрипта используйте `-ContractDirectory <каталог>`.
Обычная сборка приложения контрактных файлов не требует.

Android release использует R8 и уменьшение ресурсов. Для подписи задайте переменные окружения
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
Без них release собирается неподписанным. Ключи и пароли в Git не добавляются.

## Документация

- [Архитектура и границы модулей](ARCHITECTURE.md).
