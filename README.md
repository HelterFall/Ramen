# Ramen

**Ramen** — Android-first библиотека и плеер визуальных новелл. Приложение импортирует игру, определяет доступные данные/движок и передаёт запуск отдельному runtime-адаптеру.

> **Нужно восстановить контекст или продолжить после перерыва? Начинай с [HANDOFF.md](HANDOFF.md).** Каноническая ветка — `main`; PR #27 с Resource Inspector и экспериментальным System-NNN уже влит. Новую работу начинай от актуального `main` и сверяй его SHA/CI.

> **Текущий статус:** Android shell, ZIP/folder/HTML import, ограниченный HTML5 WebView runtime, read-only inventory, Resource Inspector и GPK/GTB + VPK/VTB parsers входят в `main`. System-NNN loader/player также интегрирован, но SPT semantics и воспроизведение реальной игры ещё требуют проверки. Нельзя путать parser available с полной совместимостью.

## Принципы проекта

Ramen не является универсальным Windows-эмулятором и не обещает, что каждая игра будет работать. Держим отдельно три уровня:

1. Файл/движок обнаружен.
2. Формат распознан и разобран проверенным парсером.
3. Игра реально запускается и проходит воспроизводимые тесты.

Расширение файла само по себе не является доказательством формата. Игровые данные — недоверенный ввод; все парсеры должны быть read-only и ограниченными по размерам, диапазонам и вложенности.

## Текущая основа

- Kotlin, Jetpack Compose, Android.
- Pinned Gradle Wrapper, JDK 17, GitHub Actions Android CI.
- ZIP, folder через Android Storage Access Framework и single HTML import в app-private storage.
- Детектор HTML5, TyranoScript, Ren'Py, KiriKiri и System-NNN SPT.
- Ограниченный HTML5 WebView runtime с локальным origin, изоляцией browser profile и offline policy.
- Read-only PE/ELF/common signature inspection, стандартные PE32/PE32+ imported DLL names.
- Детерминированный ограниченный directory inventory, streaming SHA-256 и JSON schema v1 writer.
- GPK/GTB и VPK/VTB resource parsers и Resource Inspector входят в `main`.
- Экспериментальные System-NNN SPT loader, command decoder, Compose player и audio/image adapters интегрированы в `main` через merged PR #27.
- Room ещё не интегрирован: библиотека использует SharedPreferences.

## Куда смотреть

- **[HANDOFF.md](HANDOFF.md)** — главный документ для возобновления работы.
- **[Build matrix](docs/BUILD_MATRIX.md)** — проверка, какие функции попадают в APK из каждой ветки, и сверка последних Actions.
- [Development log](docs/DEVELOPMENT_LOG.md) — хронология и записи о решениях.
- [System-NNN SPT format notes](docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md) — байтовые наблюдения и гипотезы по SPT.
- [Project scope](docs/PROJECT_SCOPE.md) — продуктовая цель и MVP.
- [Architecture](docs/ARCHITECTURE.md) — архитектурные границы.
- [Engine compatibility](docs/ENGINE_COMPATIBILITY.md) — матрица движков (сверять с HANDOFF, часть статусов устарела).
- [Engine research](docs/ENGINE_RESEARCH.md) — read-only исследование.
- [Format registry](docs/FORMAT_REGISTRY.md) — реестр доказательности форматов (GPK/VPK статусы требуют синхронизации).
- [Inventory JSON schema](docs/INVENTORY_REPORT_SCHEMA.md) — схема JSON-отчёта.
- [Roadmap](docs/ROADMAP.md) — историческая дорожная карта; актуальный приоритет см. в HANDOFF.
- [Build setup](docs/BUILD_SETUP.md) — Android toolchain и команды.
- [ZIP import](docs/ZIP_IMPORT.md), [folder import](docs/FOLDER_IMPORT.md), [HTML5 runtime](docs/HTML5_RUNTIME.md) — поведение и ограничения.
- [Contributing](CONTRIBUTING.md) — правила работы и test data.

## Сборка

Используй committed Gradle Wrapper из корня:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Подробные версии SDK/Gradle/Kotlin и Windows-команды см. в [docs/BUILD_SETUP.md](docs/BUILD_SETUP.md).

## Ближайший приоритет

Не начинать новый движок. Сначала довести текущий System-NNN path: актуальный CI → исправление тестов → подтверждение SPT pointer/table semantics на реальном sample → проверка сцен, изображений, аудио и Android lifecycle. Продолжать работу из main; PR #27 уже влит. Уникальные изменения в оставшихся PR #25/#26 оценивать отдельно, не сливать их целиком без reconciliation.

