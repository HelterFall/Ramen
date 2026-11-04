# Актуальная контрольная точка — 2026-11-05

> `main` остаётся базовой веткой. Целевая работа выполняется в открытом draft PR #28; ничего не слито в `main`.

- **Целевая игра:** *Gore Screaming Show*, только очищенная Steam-сборка с удалённым NSFW-контентом. Не добавлять бинарники игры, извлечённые сценарии или ресурсы в Git.
- **Рабочая ветка:** `docs/system-nnn-launch-roadmap`; [PR #28](https://github.com/HelterFall/Ramen/pull/28) открыт как draft.
- **Последний зелёный CI по коду:** [Android CI #362](https://github.com/HelterFall/Ramen/actions/runs/41918800780), точный head `efa7b76411ee517e21cc895201ee0de998b85e4e`. Build, unit tests, lint и debug APK upload прошли. Artifact ID `13366486289`, 11,696,165 bytes, SHA-256 `02027cc8c24caf5e1dc6d8f098f1e35e683cb2e780cd40791c0ca6e56d0eba70`; срок хранения до 2026-11-11. Это не физический device test.
- **SPT loader:** два индексированных пула проверены на 20 модулях; есть ограниченный разбор record envelope и диагностические Pool 1 / marker scanners.
- **Частичная VM:** первый поднабор включает PRINT/LPRINT/APPEND, SELECT/CASE и ограниченные CALCU-выражения. Неподдерживаемые COMMAND/FUNCTION и неизвестные коды завершаются явной ошибкой; полной совместимости это не означает.
- **WGQ fix:** standalone `.wgq` теперь попадает в image catalog только после bounded header probe на поддерживаемую Black Cyc packed-BMP структуру. Если кандидатов нет, поиск продолжается по GPK/GTB в других папках. Это не классификатор всех WGQ и не аудиоплеер.
- **Проект System-NNN:** пользовательский `project.xtx` подтверждает 20 модулей и полный набор sibling SPT-файлов. Новый read-only каталог читает манифест; Activity больше не выбирает `01.spt` автоматически и останавливает multi-module проект с понятной ошибкой. Entrypoint и межмодульные переходы не доказаны. Остальные блокеры: только первая GPK/GTB-пара и первый VPK/VTB-банк, связь реплик с голосом/BGM и сценическими ресурсами, отсутствие полного Android-теста. По текущей схеме `sys.spt` не имеет обычного `film 0`, а у остальных 19 модулей он ведёт на границу записи; как минимум `backspt` и `01` остаются кандидатами. Pool 2 в `sys` содержит системные подписи и имена части модулей, но dispatch-семантика ещё не доказана. Дополнительно доказано, что descriptor 3 — DWORD pointer vector: первые descriptor4 slots указывают на `SYSTEM_FUNCTION 0x3333/81`, остальные — на `0x3333/80` во всех 20 модулях (1 028 targets). `sys` имеет 3 reserved targets и 0 ordinary film targets; это подтверждает таблицу, но не boot handoff. Дополнительно для 948 обычных фильмов найден повторяющийся структурный шаблон: `0x3333/0x50` → соседний `0x3333/0x52` → один `0x3333/0x1A` перед `0x5556/0x0C`. По точной форме payload `[1, Pool2Index]` найдена 511 ссылка на строки Pool 2, совпадающие с именами модулей; это кандидаты на jump-target references, но не доказанные переходы.
- **UI:** System-NNN помечается как экспериментальный запуск с частичной поддержкой команд. Не называть игру полностью поддерживаемой.
- **Сохранять без регрессий:** Resource Inspector, подтверждённое пользователем чтение текущих GPK/GTB и VPK/VTB, ZIP/folder import и отсутствие искусственного лимита 512.
- **Правило безопасности данных:** никакие материалы из NSFW-издания не добавлять, не восстанавливать и не использовать как замену очищенной Steam-сборке.

**Следующий приоритет:** проверить кандидаты ссылок на модули сравнением payload и control-flow контекстов с обычными Pool 2 ссылками; отдельно доказать boot dispatch из `sys`/`backspt`. Не реализовывать совпадения имён как переходы без семантического подтверждения. Затем составить полный read-only каталог ресурсов.

---

# Ramen — единый handoff проекта

> **Каноническое состояние на 2026-11-04:** рабочая и базовая ветка проекта теперь `main`. PR #27 (`feat/system-nnn-spt-loader`) успешно влит merge-коммитом `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`. Это состояние соответствует исходникам, проверенным успешным Actions run #278 до слияния. Не начинай новую работу со старой ветки `feat/system-nnn-spt-loader`: создавай новые feature-ветки от актуального `main`.

> Этот документ — единая точка восстановления контекста. Перед изменениями прочитай его и [docs/BUILD_MATRIX.md](docs/BUILD_MATRIX.md), затем проверь актуальный head `main` и самый новый CI. Исторические снимки ниже помечены отдельно и не должны подменять текущий статус.

## Как возобновить работу в новом чате

Передай следующему чату:

> **Продолжаем Ramen. Репозиторий `HelterFall/Ramen`. Каноническая ветка — `main`. Сначала прочитай корневой `HANDOFF.md` и `docs/BUILD_MATRIX.md` именно из main, проверь текущий SHA и последний Android CI. Не возвращайся на feat/system-nnn-spt-loader: PR #27 уже влит.**

Важные ссылки:
- Репозиторий: https://github.com/HelterFall/Ramen
- Основная ветка: https://github.com/HelterFall/Ramen/tree/main
- Этот файл: https://github.com/HelterFall/Ramen/blob/main/HANDOFF.md
- Слияние PR #27: https://github.com/HelterFall/Ramen/pull/27
- SPT-заметки: [docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md](docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md)
- Карта запуска System-NNN и журнал этой работы: [docs/SYSTEM_NNN_LAUNCH_ROADMAP.md](docs/SYSTEM_NNN_LAUNCH_ROADMAP.md)
- Хронологический журнал: [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md)
- Матрица веток и сборок: [docs/BUILD_MATRIX.md](docs/BUILD_MATRIX.md)

## Текущий статус интеграции

- **Каноническая ветка:** `main`.
- **Merge commit:** `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`.
- **Исходная ветка PR #27:** `feat/system-nnn-spt-loader`, проверенный head `75675e3302439bc31f5bc1961bc82b1233ec34b7`; PR #27 закрыт как merged.
- **Проверка до merge:** Actions run [#278](https://github.com/HelterFall/Ramen/actions/runs/41674773614) завершился успешно на точном исходном SHA PR #27. Он выполнил `assembleDebug`, `testDebugUnitTest`, `lintDebug` и загрузил debug APK.
- **Проверка после merge:** push-run [#279](https://github.com/HelterFall/Ramen/actions/runs/41676851845) завершился успешно на merge commit в `main`: build, unit tests, lint и загрузка debug APK прошли.
- **Resource Inspector:** входит в `main`. Activity зарегистрирована в Manifest; shortcut доступен на главном экране, а карточки игр позволяют открыть Inspector для ресурсов игры. Пользователь подтвердил, что Inspector пока нужен.
- **GPK/GTB и VPK/VTB:** парсеры и просмотр ресурсов включены в текущее состояние `main`.
- **System-NNN:** loader, player и путь запуска включены, но трактовка SPT-таблицы остаётся экспериментальной. Успешный CI не доказывает правильность полного SPT VM или совместимость реальной игры.
- **PR #25 и #26:** остаются отдельными незлитыми ветками. PR #27 содержит собственные реализации пересекающихся ресурсных компонентов, но не всю историю этих веток. Не сливай #25/#26 автоматически; сначала проверь, есть ли там уникальные исправления, которых нет в main.

---

## 1. Что такое Ramen

Ramen — Android-first библиотека и плеер визуальных новелл. Идея продукта: пользователь импортирует папку или архив игры, приложение исследует её структуру, определяет движок и запускает игру через отдельный runtime-адаптер.

Это не универсальный эмулятор Windows и не обещание запуска любой игры. В проекте принципиально разделяются:

1. **Обнаружение** — найдены характерные файлы или маркеры.
2. **Идентификация / разбор** — структура подтверждена байтами и ограниченным парсером.
3. **Воспроизводимость** — runtime реально запускает игру и проходит заранее определённые проверки на заявленных устройствах.

Нельзя подменять одно другим. Расширение файла само по себе ничего не доказывает.

Основной стек:
- Kotlin;
- Android / Jetpack Compose;
- Gradle Wrapper;
- Android Storage Access Framework для выбора источников;
- WebView для ограниченного HTML5 runtime;
- собственные read-only парсеры ресурсов;
- CI через GitHub Actions.

Платформа: Android. В текущем Gradle-конфиге `minSdk=26`, `compileSdk=37`, `targetSdk=37`.

## 2. Краткая карта репозитория

Проект пока собран как одно Android-приложение `:app`, а не как набор отдельных Gradle-модулей. Диаграмма в `docs/ARCHITECTURE.md` описывает желаемые границы, но не означает, что все эти модули уже созданы.

### Корень

| Путь | Назначение |
|---|---|
| `README.md` | Краткая точка входа в проект; должен ссылаться на этот HANDOFF |
| `HANDOFF.md` | Главный сводный контекст, актуальные статусы, карта, PR, план |
| `CONTRIBUTING.md` | Правила вклада и работы с тестовыми данными |
| `settings.gradle.kts` | Gradle-проект; подключён только `:app` |
| `build.gradle.kts` | Корневые плагины и Kotlin Gradle Plugin |
| `gradle/libs.versions.toml` | Централизованные версии библиотек и плагинов |
| `gradle/wrapper/` | Зафиксированный Gradle Wrapper |
| `app/` | Android-приложение, исходники и unit-тесты |
| `docs/` | Архитектура, безопасность, форматы, журнал и handoff-заметки |
| `testdata/` | Оригинальные небольшие тестовые данные; не добавлять туда коммерческие игры |

### Основные исходники приложения

Корневой пакет: `app/src/main/java/io/github/helterfall/ramen/`

| Пакет / файл | Что делает |
|---|---|
| `MainActivity.kt` | Android entry point; вызывает Compose `RamenApp()` |
| `ui/RamenApp.kt` | Главный экран библиотеки, import actions, переходы в инспектор и runtime |
| `library/GameRecord.kt` | Модель записи игры |
| `library/LibraryRepository.kt` | Чтение/сохранение списка игр |
| `importer/ZipGameImporter.kt` | Импорт ZIP в приватное хранилище |
| `importer/SafFolderImporter.kt` | Импорт выбранной папки через SAF |
| `importer/HtmlFileImporter.kt` | Импорт одиночного HTML/HTM-файла |
| `importer/SafeZipExtractor.kt` | Безопасная распаковка ZIP |
| `importer/SafeFolderCopier.kt` | Безопасное копирование папки |
| `importer/HtmlInlineResourceExtractor.kt` | Извлечение поддерживаемых base64 image/audio из HTML |
| `importer/VisualNovelDetector.kt` | Детектор движков и статусов совместимости |
| `inspection/BinaryArtifactInspector.kt` | Read-only PE/ELF/ZIP/RIFF/Ogg/PNG/JPEG-инспекция и PE metadata |
| `inspection/ContainerFormatRegistry.kt` | Реестр форматов с уровнями доказательности |
| `inspection/ReadOnlyRandomAccessSource.kt` | Интерфейс позиционного read-only доступа и File-backed реализация |
| `inspection/Sha256FileHasher.kt` | Потоковый SHA-256 |
| `inspection/DirectoryInventoryScanner.kt` | Ограниченная детерминированная инвентаризация папки |
| `inspection/DirectoryInventoryJsonWriter.kt` | Потоковая запись JSON schema v1 |
| `inspection/ResourceArchiveParsers.kt` | GPK/GTB и VPK/VTB парсеры ресурсов |
| `inspection/BmpRleDecoder.kt` | Ограниченное декодирование BMP BI_RLE4/BI_RLE8 |
| `inspection/ReadOnlyRangeStreamer.kt` | Чтение ограниченного диапазона источника |
| `inspection/ReadOnlySourceRangeInputStream.kt` | InputStream-обёртка для диапазона random-access источника |
| `inspection/VpkOggCacheMaterializer.kt` | Материализация Ogg-диапазона во временный файл для MediaPlayer |
| `parser/SystemNnnScriptLoader.kt` | XOR-demask, проверка SPT header, таблиц и кандидатов Shift-JIS строк |
| `runtime/resources/ImportedResourcePairScanner.kt` | Поиск совпадающих пар архив/индекс в импортированной игре |
| `runtime/resources/ResourceInspectorActivity.kt` | UI для просмотра найденных ресурсов |
| `runtime/nativeaudio/AndroidResourceDecoders.kt` | Android-декодеры графических ресурсов |
| `runtime/html5/Html5PlayerActivity.kt` | Изолированный HTML5/WebView runtime |
| `runtime/systemnnn/SystemNnnPlayerActivity.kt` | Activity запуска System-NNN player |
| `runtime/systemnnn/SystemNnnPlayerScreen.kt` | Compose-экран сцены/диалога |
| `runtime/systemnnn/SystemNnnPlayerViewModel.kt` | Состояние и логика воспроизведения сцены |
| `runtime/systemnnn/SystemNnnCommandDecoderImpl.kt` | Текущая экспериментальная интерпретация таблицы переходов |
| `runtime/systemnnn/SystemNnnGpkBitmapRepository.kt` | Поиск и загрузка изображений GPK |
| `runtime/systemnnn/SystemNnnVpkAudioRepository.kt` | Получение аудио из VPK/VTB |
| `runtime/systemnnn/SystemNnnAudioPlayer.kt` | Android MediaPlayer-адаптер |
| `storage/StorageSpaceGuard.kt` | Проверка доступного места при потоковой записи |

### Тесты

`app/src/test/java/io/github/helterfall/ramen/` покрывает importer, detector, binary inspection, resource parsers, resource-pair scanner, System-NNN loader/decoder/player, BMP RLE и storage guard.

Особенно важные тестовые классы в рабочей ветке:
- `importer/HtmlInlineResourceExtractorTest.kt`
- `inspection/ResourceArchiveParsersTest.kt`
- `inspection/BmpRleDecoderTest.kt`
- `inspection/ResourceInputStreamAndCacheTest.kt`
- `parser/SystemNnnScriptLoaderTest.kt`
- `runtime/resources/ImportedResourcePairScannerTest.kt`
- `runtime/systemnnn/SystemNnnCommandDecoderImplTest.kt`
- `runtime/systemnnn/SystemNnnGpkBitmapRepositoryTest.kt`
- `runtime/systemnnn/SystemNnnPlayerViewModelTest.kt`
- `storage/StorageSpaceGuardTest.kt`

### Документация

| Документ | Содержание / замечание |
|---|---|
| `docs/PROJECT_SCOPE.md` | Идея продукта, MVP, не-цели и критерии |
| `docs/ARCHITECTURE.md` | Архитектурные слои и границы |
| `docs/BUILD_SETUP.md` | Toolchain и сборка |
| `docs/ZIP_IMPORT.md` | ZIP-импорт; некоторые лимиты в нём устарели для PR #27 |
| `docs/FOLDER_IMPORT.md` | Folder import; некоторые лимиты устарели для PR #27 |
| `docs/HTML5_RUNTIME.md` | Ограниченный WebView runtime; лимиты размера частично устарели для PR #27 |
| `docs/ENGINE_COMPATIBILITY.md` | Матрица движков; нуждается в сверке с System-NNN prototype |
| `docs/ENGINE_RESEARCH.md` | История read-only исследования движков; ранние next steps устарели |
| `docs/FORMAT_REGISTRY.md` | Evidence registry; GPK/GTB и VPK/VTB в нём всё ещё описаны как extension-only, это уже устарело относительно текущей ветки |
| `docs/INVENTORY_REPORT_SCHEMA.md` | JSON schema v1 для directory inventory |
| `docs/DEVELOPMENT_LOG.md` | Хронология и долговременные handoff-записи |
| `docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md` | Наблюдения из присланного SPT анализа |
| `docs/SYSTEM_NNN_LAUNCH_ROADMAP.md` | Пошаговый план запуска System-NNN, риски, критерии готовности и журнал работ |
| `docs/ROADMAP.md` | Историческая roadmap; статусы M0–M4 частично устарели |

**При конфликте старых статусов в документах используй этот HANDOFF и проверяй код соответствующей ветки.** Следующий документальный проход должен синхронизировать перечисленные устаревшие страницы.

## 3. Архитектура и поток данных

Основной путь пользователя:

1. Выбор ZIP / папки / одиночного HTML через Android picker.
2. Импортер копирует данные в app-private storage, сначала во временную папку.
3. После успешного копирования запускается `VisualNovelDetector`.
4. Создаётся `GameRecord` с engine ID, статусом и evidence.
5. `LibraryRepository` сохраняет запись.
6. UI показывает игру и подходящие действия:
   - HTML5 player;
   - Resource Inspector;
   - System-NNN player, если детектор вернул соответствующий engine ID.
7. Runtime-адаптер сам отвечает за чтение формата, отображение, звук и Android lifecycle.

Путь импортированных данных: `files/games/<id>` внутри app-private storage. ZIP/папка копируются, исходный источник не используется как постоянная live-ссылка.

### Важные архитектурные факты

- `LibraryRepository` сейчас использует JSON в Android SharedPreferences. **Room не интегрирован**, несмотря на то что он упоминается как будущая технология в части ранних документов.
- SAF уже используется для выбора ZIP/папок/файлов. Но SAF-backed random-access implementation для бинарной инспекции пока не сделана; текущий positional source file-backed.
- Декодеры и runtime-код находятся внутри одного `:app` модуля. Предложенное разделение на `core` / `runtime` Gradle-модули пока архитектурная перспектива.
- Не запускать Windows EXE во время инспекции. PE reader только читает metadata; это не Wine/эмулятор.
- Game content — недоверенные входные данные. Сохранять bounds, overflow checks, path validation, read-only parsing и отсутствие необязательных полномочий.

## 4. Состояние функций — на какой факт опираемся

| Функция | Состояние в рабочем snapshot | Основание / оговорка |
|---|---|---|
| Android shell + Compose library | Реализовано | Код в `MainActivity`, `RamenApp` |
| ZIP import | Реализовано | Код и unit-тесты; import в app-private storage |
| SAF folder import | Реализовано | Код и unit-тесты; физические тесты с разными providers всё ещё нужны |
| Standalone HTML import | Реализовано | Один HTML; соседние CSS/JS/assets не копируются |
| HTML inline image/audio extraction | Реализовано | Поддерживаемые base64 MIME types; в PR #27 запись контролируется storage guard |
| Engine detector | Реализован | Неизвестное остаётся UNKNOWN; обнаружение не равно поддержке |
| HTML5 runtime | Ограниченный prototype | Root-level `index.html`, local appassets origin, multi-profile separation, offline restrictions; physical device acceptance нужен |
| PE/ELF and common signature inspection | Реализовано частично | PE headers/imports; ELF только signature |
| SHA-256 + directory inventory | Реализовано | Bounded deterministic scan |
| JSON inventory writer | Реализован | Schema v1; пользовательский save/share UI отсутствует |
| SAF positional read for inspector | Не реализован | Есть file-backed `ReadOnlyRandomAccessSource` |
| Room | Не реализован | SharedPreferences сейчас |
| GPK/GTB reading | **Пользователь подтвердил, что читаются** | В рабочей ветке есть `GpkGtbArchiveParser`, image decoders и resource inspector; полноту всех вариантов не утверждать |
| VPK/VTB reading | **Пользователь подтвердил, что читаются** | В рабочей ветке есть `VpkVtbArchiveParser`, Ogg validation/materialization и audio adapter; полноту всех вариантов не утверждать |
| Black Cyc resource preview | Есть отдельная незлитая ветка/PR #26 | Не путать resource preview с полноценным Black Cyc script runtime |
| System-NNN SPT parser | Prototype в PR #27 | Header/tables/text candidate parsing; semantics таблиц ещё исследуются |
| System-NNN scene player | Код prototype в PR #27 | Есть Activity/ViewModel/Compose screen; нет подтверждённого end-to-end прохождения реальной игры |
| System-NNN audio | Аудиокомпоненты есть | Ogg staging + MediaPlayer; текущий transition decoder не dispatch-ит аудио-команды |
| Choice / ветвления SPT | Не подтверждены | Не добавлять неподтверждённые opcode semantics |
| Arbitrary Windows engine support | Не реализовано | Нет запуска/эмуляции EXE |

### Детали detector status в текущей ветке

В `VisualNovelDetector.kt`:
- корректно загруженный SPT-кандидат → `engineId=system-nnn-spt`, `compatibilityStatus=PARSER_AVAILABLE`;
- root `index.html` без Tyrano marker → `html5`, `SUPPORTED_LIMITED`;
- TyranoScript → `DETECTED_UNSUPPORTED`;
- Ren'Py → `DETECTED_UNSUPPORTED`;
- KiriKiri → `DETECTED_UNSUPPORTED`;
- иначе → `UNKNOWN`.

В detector evidence для System-NNN пока осталась фраза, что playback interpreter ещё не подключён. Это текстовое сообщение не синхронизировано с наличием player-классов в PR #27; перед изменением статуса нужно определить, что именно означает `PARSER_AVAILABLE` и какие проверки уже пройдены.

## 5. Импорт и storage safety

На PR #27 importers изменены: произвольные жёсткие byte ceilings сняты; большие файлы потоково записываются с проверкой свободного места.

Текущая реализация `StorageSpaceGuard`:
- проверяет доступное место на томе;
- проверка вызывается пакетами записи примерно по 256 KiB через `StorageGuardedOutputStream`;
- оставляет резерв: минимум 16 MiB, максимум 256 MiB, или около 5% доступного места — применяется описанная в коде формула;
- не заменяет ограничения количества entries и глубины.

Ограничения текущих ZIP / folder importer:
- максимум 10 000 entries;
- максимум depth 64;
- нормализация и проверка путей;
- отклонение абсолютных/traversal путей;
- отклонение повторяющихся путей без учёта регистра;
- staging directory с очисткой при ошибке;
- folder copier проверяет повторяющиеся document source IDs от provider;
- ограничения длины сегмента/пути также остаются.

В отличие от старой версии docs, для ветки PR #27 **не следует заявлять лимиты 512 MiB на файл и 1 GiB на всю игру** — эти byte ceilings сняты. Следует описывать действующую проверку реального свободного места.

HTML single-file import:
- принимает .html/.htm или подходящий HTML MIME;
- копирует выбранный документ как `index.html`;
- извлекает поддерживаемые встроенные data URI изображений и аудио в приватную `assets`;
- не копирует соседние CSS, JS, картинки, аудио и другие файлы проекта;
- для multi-file проекта нужен импорт папки или ZIP.

## 6. HTML5 runtime

Ограниченный WebView runtime описан в `docs/HTML5_RUNTIME.md`. Главные свойства:
- используется app-controlled local origin `https://appassets.androidplatform.net/game/`;
- обслуживается только директория текущей игры;
- отдельный WebView multi-profile session на каждую игру;
- file/content URL access выключены;
- нет JS-to-native bridge;
- сетевые запросы, mixed content, popup/external navigation, geolocation и permissions блокируются;
- Android INTERNET permission не запрашивается;
- JS и DOM storage включены только для изолированного локального game profile;
- присутствует диагностика WebView, DOM geometry и native bounds;
- уже исправлялись проблемы resize/orientation/Compose footer affecting viewport.

Не называть это полным браузером или универсальным HTML5 engine. Запуск зависит от установленного Android System WebView и поддержки multi-profile.

## 7. GPK/GTB и VPK/VTB

Пользователь отдельно уточнил: **GPK, GTB, VPK и VTB читаются в текущем проекте**. Не возвращаться к формулировке, что эти файлы ещё только предполагается изучать.

В рабочей ветке:
- `ResourceArchiveParsers.kt` содержит `GpkGtbArchiveParser` и `VpkVtbArchiveParser`;
- парсер GPK/GTB отдаёт image resource metadata и bounded payload ranges;
- ветка включает JPEG/PNG и BMP handling, в том числе BI_RGB, BI_RLE4 и BI_RLE8, а также Black Cyc DWQ packed-BMP subset;
- `ImportedResourcePairScanner` сопоставляет файлы по совпадающему basename и parent directory без учёта регистра: `.gpk + .gtb`, `.vpk + .vtb`;
- scanner исключает symlinks и игнорирует `.xtx` при поиске пар;
- VPK/VTB parser проверяет Ogg page/resource structure; `VpkOggCacheMaterializer` готовит временный seekable Ogg-файл для Android MediaPlayer.

Уровень уверенности:
- факт чтения подтверждён пользователем;
- код парсеров и тестов присутствует в текущей ветке;
- полный перечень совместимых версий/вариантов и device-level acceptance здесь не доказан;
- наличие resource preview не означает наличие полноценного script interpreter для любого движка.

## 8. System-NNN SPT — все известные наблюдения

Подробная запись лежит в [docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md](docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md). Ниже основные данные, чтобы их было видно сразу.

### Источник данных

Пользователь предоставил:
- `hu.txt` — Python analysis script для XOR-demask и анализа SPT;
- `ho.txt` — его текстовый вывод.

Сам бинарный SPT в этой сессии **не был предоставлен**. Поэтому значения ниже сохранены как вывод анализатора, а не повторно измеренные нами байты оригинального файла.

### Вывод по одному sample

| Поле | Значение из вывода |
|---|---|
| File size | 389 500 bytes = `0x5F17C` |
| Transform | XOR каждого байта с `0xFF` |
| Demasked signature | `SPTHEADER0` |
| Signature offset | `0x30` |
| First DWORD | `0x00000014` = 20 |

Candidate count/offset pairs, поля читаются как два little-endian DWORD:

| Header location | Count | Offset |
|---|---:|---:|
| `0x10` | 1299 | `0xC5BA` |
| `0x18` | 1124 | `0x167F2` |
| `0x20` | 69 | `0xC390` |
| `0x28` | 2 | `0x0` |

Shift-JIS observations:
- первый printable run по эвристике скрипта: `0x32B64`;
- конец run: `0x32BC9`;
- длина: 101 byte;
- строгий Shift-JIS decode этого run успешен;
- найденные маркеры `text\0`: `0x32B34`, `0x32B3C`, `0x32B44`, `0x32B4C`, `0x32B54`, `0x32B5C`.

Five little-endian DWORD values printed at `0x32B20`:

| Offset | Value |
|---|---:|
| `0x32B20` | `0x00016752` |
| `0x32B24` | `0x00016791` |
| `0x32B28` | `0x000167A3` |
| `0x32B2C` | `0x000167C6` |
| `0x32B30` | `0x000167D9` |

Смысл пяти DWORD и шести marker records пока не установлен.

### Что уже предполагает код PR #27

`SystemNnnScriptLoader.kt` сейчас:
- XOR-demask-ит весь файл ключом `0xFF`;
- проверяет `SPTHEADER[0-9]` по смещению `0x30`;
- читает четыре count/offset descriptors из header;
- считает main table candidate расположенной по `0xC5BA` и с count 1299;
- задаёт command region end `0x32B20`;
- задаёт text pool start `0x32B64`;
- пытается декодировать NUL-delimited records в Shift-JIS от этого места до конца файла.

Это implementation hypothesis, а не полная спецификация.

`SystemNnnCommandDecoderImpl.kt` сейчас предполагает:
- 1299 записей;
- каждая запись — UInt32 little-endian (4 bytes);
- записи читаются последовательно с `0xC5BA`;
- значения ниже `gtbResourceCount` считаются resource indices;
- прочие значения идут по sequential text-pool index.

**Найдено важное расхождение:** PR #27 в описании говорит о правиле `value >= 0x32B64` → dialogue reference и меньших значениях → GPK/GTB resource index. Но фактический `SystemNnnCommandDecoderImpl` сравнивает значение с `gtbResourceCount`, а не с `0x32B64`; затем берёт следующий текст по индексу `nextTextEntryIndex`, а не разрешает текст по самому значению через абсолютный offset. Это надо сверить с реальным sample и scene order прежде, чем считать pointer rule реализованным.

Ещё один важный arithmetic check:
- `0xC5BA + 1299 * 4 = 0xDA06`.
- Значит заявленная 4-byte table занимает диапазон `0xC5BA..0xDA06`; всё остальное до `0x32B20` нельзя автоматически считать той же 1299-entry table.
- Loader хранит command bytes от main offset до `0x32B20`, поэтому границы command region и границы main pointer table — разные понятия.

### Чего пока нельзя утверждать

- что все четыре header descriptors поняты семантически;
- что все записи main table точно UInt32 pointers;
- что каждое значение указывает на текст или ресурс по описанной границе;
- что пять DWORD at `0x32B20` — именно указатели;
- что маркеры `text\0` задают один конкретный тип записи;
- что все NUL-terminated strings от `0x32B64` до EOF — один text pool;
- что диалоги идут в порядке, который сейчас задан последовательным индексом;
- что Choice/ветвления/audiocommands известны;
- что реальная игра System-NNN прошла end-to-end.

### Следующие SPT проверки

1. Получить оригинальный SPT как отдельный образец или безопасный diagnostic dump, не помещая коммерческий бинарник в Git.
2. Снять bounded hex windows вокруг `0xC390`, `0xC5BA`, `0x16752`, `0x167F2`, `0x32B20`, `0x32B64`.
3. Посчитать SHA-256 оригинального образца и записать его локально / в приватном handoff, если допустимо.
4. Проверить table entry width и структуру записей на байтах, а не только на count.
5. Исследовать 1124-entry table at `0x167F2` и 69-entry table at `0xC390`.
6. Разобрать шесть `text\0` records и пять DWORDs around `0x32B20`.
7. Сопоставить реальные dialogue strings и GPK/GTB resource indexes с фактическим порядком сцен.
8. Только после этого фиксировать decoder pointer rule и переходить к полноценным Choice/branch/audio semantics.

## 9. Хронология проекта

### 1 ноября 2026 — базовое приложение и импорт
- Создан Android/Kotlin/Compose проект.
- Добавлены Gradle Wrapper, pinned toolchain и Android CI.
- Добавлен исходный HTML5 fixture.
- Добавлены безопасный ZIP import, SAF folder import, game detector и первичное хранение библиотеки.
- Библиотека пока сохраняется в SharedPreferences, не Room.

### 1 ноября 2026 — HTML5 runtime
- Добавлен ограниченный локальный WebView player.
- Настроены local resources, origin и запрет сетевых/внешних переходов.
- Добавлен одиночный HTML import и извлечение встроенных data URI assets.
- Исправлялись diagnostics, DOM/native bounds, поворот экрана и resize feedback loop.

### 2 ноября 2026 — read-only binary inspection
- PR #19: PE headers/common signatures + evidence-aware format registry.
- PR #20: bounded PE32/PE32+ standard import directory.
- PR #21 был superseded PR #22 из-за base conflict.
- PR #22: positional source, streaming SHA-256, container probe outcomes.
- PR #23: bounded deterministic directory inventory + source-backed PE inspection.
- PR #24: streaming versioned JSON inventory writer; CI correction для escaping.

### 2–3 ноября 2026 — ресурсные форматы и System-NNN ветки
- PR #25: GPK/GTB и VPK/VTB parsers, image/audio resource work, сценарный prototype boundaries; branch очень большой и имеет отдельную историю.
- PR #26: Black Cyc resource preview, DWQ packed-BMP subset, opaque SPT classification only alongside matching GPK/GTB; это не полноценный Black Cyc script runtime.
- PR #27: System-NNN SPT loader, command decoder, runtime Activity/Compose UI, image and audio repositories/player; также сняты arbitrary byte ceilings в importer в пользу storage guard.
- 4 ноября: SPT observations сохранены в `docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md` и `docs/DEVELOPMENT_LOG.md`; подготовлен этот единый handoff.

## 10. Ветки и PR — актуальный статус после merge PR #27

- `main` — единственная каноническая ветка для дальнейшей разработки.
- PR [#27](https://github.com/HelterFall/Ramen/pull/27), ветка `feat/system-nnn-spt-loader`, влит 2026-11-03 merge-коммитом `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`.
- Проверенный исходный head PR #27: `75675e3302439bc31f5bc1961bc82b1233ec34b7`; именно он указан в метаданных успешного run #278.
- PR [#25](https://github.com/HelterFall/Ramen/pull/25) (`research/gpk-vpk-readonly-parsers`) и PR [#26](https://github.com/HelterFall/Ramen/pull/26) (`feature/blackcyc-resource-preview`) на момент обновления остаются открытыми и не влитыми.
- #25 и #26 расходятся с main и между собой. PR #27 уже интегрировал собственную версию парсеров и Inspector, но это не означает, что каждая уникальная правка из #25/#26 попала в main.
- До дополнительного merge нужно сравнивать уникальные изменения #25/#26 с текущим main по файлам и тестам. В частности, крупный inventory JSON из #25 требует отдельной проверки на приватные пути и локальные данные.
- Ветка `feat/system-nnn-spt-loader` теперь историческая исходная ветка PR #27. Новую работу создавай от main; не продолжай коммиты в старой ветке.

## 11. CI и известные проблемы — текущая запись

### Успешная проверка исходного состояния PR #27

- [Actions run #278](https://github.com/HelterFall/Ramen/actions/runs/41674773614) завершился со статусом success.
- Проверенный branch/head: `feat/system-nnn-spt-loader` / `75675e3302439bc31f5bc1961bc82b1233ec34b7`.
- Job выполнил `assembleDebug`, `testDebugUnitTest`, `lintDebug`, затем загрузил артефакт.
- Артефакт: `ramen-debug-apk`, ID `13250256957`, размер 11,642,991 байт, SHA-256 `3e86f99170c5636246a142e353408f062a078e6309ce5af0196ae65d89edac67`, срок хранения до 2026-11-10.
- Этот run проверил исходный код PR #27 перед merge. Это не release build и не тест на физическом Android-устройстве.

### Проверка merge-коммита в main

- После merge GitHub запустил push-run [#279](https://github.com/HelterFall/Ramen/actions/runs/41676851845) на commit `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`.
- Run #279 завершился `completed / success`: `Assemble, test, and lint` и `Upload debug APK` — success.
- Артефакт: `ramen-debug-apk`, ID `13251016631`, 11,642,990 байт, SHA-256 `f4d34352b983b46e583cd776e9dc3825d340632fafcfe28edfc96b2690701f99`, создан 2026-11-03 16:45:09 UTC, истекает 2026-11-10 16:45:07 UTC.
- В отличие от #278, run #279 проверил уже сам merge commit в `main`. Это по-прежнему debug CI, а не release или физический device acceptance.

### Историческая CI-проблема, уже исправленная

Старый PR #27 run #230 падал в `HtmlInlineResourceExtractorTest.leavesUnsupportedDataUrisUntouched`. Причина установлена: временный переписанный HTML-файл находится в `gameDirectory`, но guard проверял свободное место в ещё не созданном `assetsDirectory`. Проверка переписанного HTML теперь использует `StorageSpaceGuard(gameDirectory)`; поток extracted payload по-прежнему использует guard каталога assets после его создания. Последующие проверки #265, #270 и #278 прошли. Возвращаться к этому failure нужно только при новом воспроизведении.

### Что именно означает зелёный CI

Workflow `.github/workflows/android-ci.yml` запускает только:

```bash
./gradlew --no-daemon --stacktrace assembleDebug testDebugUnitTest lintDebug
```

Он не запускает `assembleRelease`, не выполняет production signing и не публикует приложение в Google Play. Успешная сборка не доказывает физическое устройство, полный SPT VM, корректность каждого формата ресурсов или прохождение реальной игры от начала до конца.

## 12. Приоритетный план продолжения

### P0 — зафиксировать базу и CI
1. Проверить актуальный head main и самый новый Android CI run перед любыми изменениями.
2. PR #27 уже влит; run #278 успешен на исходном head, после merge запущена проверка main. При новых code errors извлекать точную причину по логу, не гадать.
3. Старый failure `leavesUnsupportedDataUrisUntouched` уже исправлен; возвращаться к нему только при повторном падении.
4. Повторить `assembleDebug`, unit tests и lint.

### P1 — проверить SPT parser/decoder
1. Сверить фактический код с SPT notes и найти расхождение pointer threshold.
2. Не менять кодировку, границы, table width или opcode semantics без реальных байтов/fixture evidence.
3. Добавить тесты, которые показывают конкретную связь pointer → text entry / resource index.
4. Отделить raw parsed structures от interpretation layer, если это упростит последующее исследование.

### P2 — реальный запуск System-NNN
1. Импортировать оригинальную тестовую игру на Android.
2. Подтвердить, что правильные SPT и same-basename GPK/GTB pair обнаруживаются.
3. Проверить последовательность backgrounds/dialogues по сравнению с оригинальной игрой.
4. Проверить memory/release bitmap lifecycle, rotation, pause/resume.
5. Проверить Ogg resource decode and playback; отдельно помнить, что decoder сейчас не dispatch-ит audio command.
6. Записать результат, устройство, Android version, app commit и ограничения.

### P3 — привести ветки и документацию в порядок
1. Проверить, нужны ли уникальные изменения из незлитых #25/#26 сверх уже интегрированного main; не сливать их целиком без reconciliation.
2. Проверить inventory JSON в PR #25 на приватные metadata.
3. Синхронизировать `README.md`, `ROADMAP.md`, `FORMAT_REGISTRY.md`, `ENGINE_COMPATIBILITY.md`, `ENGINE_RESEARCH.md`, ZIP/FOLDER/HTML docs с реальным кодом.
4. Оставить этот HANDOFF каноническим стартовым документом; новые важные решения заносить сюда и в development log.
5. Только после тестов и согласования объединять ветки/PR.

## 13. Рабочие правила, чтобы не терять контекст

Каждая следующая сессия / большой этап должны завершаться обновлением:
- `HANDOFF.md`: актуальные статусы, branch/head, открытые PR, известные ошибки, next steps;
- `docs/DEVELOPMENT_LOG.md`: что именно изменено, какие проверки прошли, что не получилось, что только гипотеза;
- профильного документа (например, SPT notes), если появились новые байтовые наблюдения.

В каждом инженерном выводе помечать его уровень:
- **Проверено кодом** — кодовая реализация найдена.
- **Проверено unit test** — есть тест, и конкретный прогон прошёл.
- **Проверено CI** — конкретный run зелёный.
- **Проверено на устройстве** — конкретный device test пройден.
- **Со слов пользователя** — пользователь подтвердил поведение.
- **Гипотеза** — ещё предстоит подтвердить.

Не заносить в репозиторий:
- коммерческие игровые бинарники и целые игровые каталоги;
- личные absolute paths и приватную информацию из inventory без необходимости;
- чужие игровые assets без лицензии/разрешения.

Для каждого sample полезно сохранять вне Git:
- имя/версию игры, если можно;
- SHA-256 бинарного образца;
- размер и список файлов;
- bounded hex windows / diagnostic JSON;
- версию Ramen (commit SHA);
- краткий результат на устройстве.

**Главная цель ближайшего этапа:** не начинать новые движки, а довести текущий System-NNN путь от импортированной игры до корректного воспроизведения сцен, опираясь на проверенные SPT offsets и уже читаемые GPK/GTB/VPK/VTB.


## Дополнение 2026-11-04 — состояние после продвижения PR #27 в main

- `main` теперь содержит полное дерево исходников PR #27, включая Resource Inspector, System-NNN player path, GPK/GTB и VPK/VTB resource parsers, import improvements, тесты и handoff-документы.
- Merge commit: `026ef6cdf1d2d9150fe858c88be5bb2d4a0b06e5`.
- Исходный PR #27 head `75675e3302439bc31f5bc1961bc82b1233ec34b7` проверен успешным run #278; артефакт и digest записаны в разделе 11.
- Новый канонический источник для разработки и сборок — `main`. Старую ветку `feat/system-nnn-spt-loader` не использовать как рабочую.
- Inspector оставлен включённым по прямому решению пользователя. Не удалять и не прятать его в debug-only flavor без отдельного запроса.
- System-NNN остаётся экспериментальным: запуск подключён, но semantics SPT decoder и end-to-end совместимость требуют дальнейшего исследования.
- PR #25/#26 по-прежнему нуждаются в отдельном аудите; не считай их целиком интегрированными.



## Дополнение 2026-11-05 — дополнительные признаки boot-path

- `sys.spt` не имеет обычного slot `film 0` по текущей схеме таблиц, в отличие от остальных 19 SPT-модулей.
- Все 19 обычных `film 0` target values попадают точно на границы разобранных command records; среди первых кандидатов — `backspt` и `01`, но уникального entrypoint это не даёт.
- Вторичный Pool 2 в `sys.spt` содержит 17 строк, включая системные подписи и 14 имён модулей. Три функции `0x3333/81` используют индексы трёх системных подписей.
- Это поддерживает гипотезу о специальном системном/UI-пути через `sys`, но не доказывает, куда и как он передаёт управление. Нужен разбор конкретного dispatch.
