# Архитектура клиента

Модульный Kotlin Multiplatform + Compose Multiplatform, MVVM и однонаправленное изменение
состояния. Это архитектура приложения с общим UI для Android/iOS. Отдельный UseCase
нужен для составного или повторно используемого сценария, а не для обёртки каждого
метода репозитория.

## Граф модулей

| Модуль | Ответственность | Разрешённые project-зависимости production |
|---|---|---|
| androidApp | Android Application/Activity, manifest, APK/AAB | shared-ui |
| shared-ui | Composition root, Navigation 3, hosts, общая оболочка | features, core-ui, core-runtime, shared-data, shared-domain |
| feature-* | Экран, ViewModel, события, свой Koin module | core-ui, shared-domain |
| core-ui | Визуальные компоненты, ресурсы, оформление, UI helpers | shared-domain |
| shared-domain | Модели приложения, интерфейсы, чистая валидация | нет |
| shared-data | Wire, Room, адаптеры репозиториев, journal, outbox, файлы | shared-domain |
| core-runtime | Владелец сессии/передач, Android foreground service | shared-data, shared-domain |
| build-logic | Общие KMP/Compose conventions и правила зависимостей | Gradle API |

Production-зависимости контролирует MessengerKmpPlugin. Features не могут зависеть
друг от друга, data или runtime. Domain не подключает Room/Ktor/Compose/Koin.
В host-тестах разрешены интеграционные зависимости.

## DI и жизненный цикл

DI связывает уже выбранные зависимости. За доступность типов отвечают Gradle-модули;
за контракт — domain; Koin не заменяет эти границы.

Каждая feature предоставляет функцию *Module() с регистрацией своих ViewModel.
AppContainer объединяет модули и связывает domain-интерфейсы с реализациями.
Изолированный KoinApplication не использует глобальный startKoin.
Тест ContainerTest разрешает все feature ViewModel, параметризованные экраны,
платформенную зависимость и репозитории; проверяет отдельные реализации и singleton binding.

AppRuntime владеет одной базой, сетью и сессией для всех features.
Foreground-служба и UI регистрируют свою активность у этого владельца.
ApplicationSession наблюдает состояние в scope контейнера.
ViewModel получает свой scope от Lifecycle; навигационные entries владеют ViewModelStore.
AppContainer.close() начинает однократное асинхронное закрытие; closeAndJoin() позволяет
хосту/тесту дождаться завершения. Удаление DI-контейнера не считается закрытием сети/БД.

## Репозитории

ApplicationRepositories — только сборка объектов. Он не реализует репозитории:
- DefaultMessagingRepository — переписка;
- DefaultDirectoryRepository — каталог и создание чатов;
- DefaultGroupRepository — группы;
- DefaultProfileRepository — профиль и сессии;
- DefaultAdminRepository — администрирование.

Общие ChatCatalog, DirectoryCatalog и GroupCreation передаются делегатами.
ApplicationDrafts, ApplicationAttachments и ApplicationShares обслуживают отдельные
области хранения. Совместные транзакции и account isolation остаются в shared-data;
разделение адаптеров не создаёт отдельную БД или WebSocket для каждой feature.

Wire DTO и Room entities преобразуются в модели domain на границе data/application.
RepositoryCommands — внутренняя точка вызова протокола. Публичные методы не принимают
wire operationId. Устойчивые мутации получают идентификатор из журнала Room.
Идентификаторы OTP/TOTP повторов хранятся в памяти в пределах конкретной сессии;
ключ содержит SHA-256 payload, а не открытый код. Успех завершает повторяемую операцию.
Отмена корутины проходит как CancellationException; ApplicationFailure сохраняет код
серверной ошибки, включая прохождение нескольких границ.

## UI и данные

Экран читает StateFlow и отправляет действия в ViewModel.
MutableStateFlow принадлежит ViewModel; экран не записывает .value.
Платформенные ошибки выбора/открытия файла передаются через действие reportError.
Room Flow остаётся источником локальных данных; сетевые результаты сохраняются в data.
DTO, SQL и JSON не используются в экранах. Идентичность сообщения/чата при переносе
между слоями сохраняется.

Compose state/rememberSaveable используются для локального представления; SavedStateHandle
для восстанавливаемого состояния экрана. Черновики и outbox живут в Room и переживают
закрытие процесса. Состояние аккаунта никогда не хранится в навигационном route вместе
с токенами.

## Сборка

- messenger.kmp: targets Android/iosArm64/iosSimulatorArm64, SDK/JDK, host tests, границы.
- messenger.compose: KMP + Compose compiler/runtime/foundation/resources/Material3/Lifecycle.
- Serialization включена там, где нужны wire или сериализуемые routes.
- Koin отсутствует в core-ui/domain; регистрация feature ViewModel находится в features.
- Версии — в gradle/libs.versions.toml; namespace — в конкретном модуле.
- Release использует R8 и resource shrinking, подпись только из окружения.
- Контрактные fixtures поставляются с клиентом; внешний контракт можно проверить через
  -PcontractDirectory=<путь>, без изменения исходников.
