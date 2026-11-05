# Актуальная контрольная точка — 2026-11-05

> `main` остаётся базовой веткой. Целевая работа выполняется в открытом draft PR #28; ничего не слито в `main`.

- **Целевая игра:** *Gore Screaming Show*, только очищенная Steam-сборка с удалённым NSFW-контентом. Не добавлять бинарники игры, извлечённые сценарии или ресурсы в Git.
- **Рабочая ветка:** `docs/system-nnn-launch-roadmap`; [PR #28](https://github.com/HelterFall/Ramen/pull/28) открыт как draft.
- **Последний зелёный CI по коду:** [Android CI #362](https://github.com/HelterFall/Ramen/actions/runs/41918800780), точный head `efa7b76411ee517e21cc895201ee0de998b85e4e`. Build, unit tests, lint и debug APK upload прошли. Artifact ID `13366486289`, 11,696,165 bytes, SHA-256 `02027cc8c24caf5e1dc6d8f098f1e35e683cb2e780cd40791c0ca6e56d0eba70`; срок хранения до 2026-11-11. Это не физический device test.
- **SPT loader:** два индексированных пула проверены на 20 модулях; есть ограниченный разбор record envelope и диагностические Pool 1 / marker scanners.
- **Частичная VM:** первый поднабор включает PRINT/LPRINT/APPEND, SELECT/CASE и ограниченные CALCU-выражения. Неподдерживаемые COMMAND/FUNCTION и неизвестные коды завершаются явной ошибкой; полной совместимости это не означает.
- **WGQ fix:** standalone `.wgq` теперь попадает в image catalog только после bounded header probe на поддерживаемую Black Cyc packed-BMP структуру. Если кандидатов нет, поиск продолжается по GPK/GTB в других папках. Это не классификатор всех WGQ и не аудиоплеер.
- **Проект System-NNN:** пользовательский `project.xtx` подтверждает 20 модулей и полный набор sibling SPT-файлов. Новый read-only каталог читает манифест; Activity больше не выбирает `01.spt` автоматически и останавливает multi-module проект с понятной ошибкой. Entrypoint и межмодульные переходы не доказаны. Остальные блокеры: только первая GPK/GTB-пара и первый VPK/VTB-банк, связь реплик с голосом/BGM и сценическими ресурсами, отсутствие полного Android-теста. По текущей схеме `sys.spt` не имеет обычного `film 0`, а у остальных 19 модулей он ведёт на границу записи; как минимум `backspt` и `01` остаются кандидатами. Pool 2 в `sys` содержит системные подписи и имена части модулей, но dispatch-семантика ещё не доказана. Дополнительно доказано, что descriptor 3 — DWORD pointer vector: первые descriptor4 slots указывают на `SYSTEM_FUNCTION 0x3333/81`, остальные — на `0x3333/80` во всех 20 модулях (1 028 targets). `sys` имеет 3 reserved targets и 0 ordinary film targets; это подтверждает таблицу, но не boot handoff. Дополнительно для 948 обычных фильмов найден повторяющийся структурный шаблон: `0x3333/0x50` → соседний `0x3333/0x52` → один `0x3333/0x1A` перед `0x5556/0x0C`. По точной форме payload `[1, Pool2Index]` найдена 511 ссылка на строки Pool 2, совпадающие с именами модулей; это кандидаты на jump-target references, но не доказанные переходы. Уточнение по 15 совпадающим ссылкам `0x3333/0x1A`/`0x3333/0x1B`: они указывают на один Pool 2 index в том же модуле, но находятся в разных film intervals (13 id1B раньше, 2 позже); это не локальная пара команд. Все семь затронутых кодов также ссылаются на не-модульные строки Pool 2, поэтому переходы не доказаны.
- **UI:** System-NNN помечается как экспериментальный запуск с частичной поддержкой команд. Не называть игру полностью поддерживаемой.
- **Сохранять без регрессий:** Resource Inspector, подтверждённое пользователем чтение текущих GPK/GTB и VPK/VTB, ZIP/folder import и отсутствие искусственного лимита 512. Добавлен per-game `.txt` export структуры: дерево папок (включая пустые), файлы и размеры; быстрый режим не читает содержимое и не хеширует файлы.
- **Правило безопасности данных:** никакие материалы из NSFW-издания не добавлять, не восстанавливать и не использовать как замену очищенной Steam-сборке.

**Следующий приоритет:** использовать новый `.txt` structure report для быстрого сопоставления каталогов/файлов и поиска структуры модулей; затем продолжить разбор подтверждённых module-dispatch ссылок. Не повторять уже записанные инвентаризации.

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


## Дополнение 2026-11-05 — каталог сцен и кандидаты межмодульных ссылок

- PR #28 остаётся открытым Draft, base = main. Изменения продолжаются только в docs/system-nnn-launch-roadmap; main не менялся.
- Новый архив hui(4).zip исследован read-only. scene.dat имеет version 1, declared size 1164, record count 69 и корректно заканчивается на EOF. Типы/значения строк сохранены как непрозрачные UInt32-поля, без выдумывания семантики.
- nya/scenevoice.xtx содержит 69 уникальных последовательных идентификаторов @sc01–@sc69. Файл использует CRLF.
- Для 01.spt descriptor 3 имеет 69 указателей, descriptor 4 задаёт 2 зарезервированных элемента, значит обычных film slots 67. Три независимых количества 69 (scene.dat, scenevoice, descriptor 3) пока доказывают только совпадение cardinality, но не соответствие по индексу.
- В PR добавлены:
  - SystemNnnSceneCatalogReader — bounded parser scene.dat, CRLF-safe extraction @scNN, count-only comparison with film table. Поле sameIndexMappingProven намеренно false.
  - SystemNnnModuleReferenceScanner — read-only сканер точных payload [1, Pool2Index], указывающих на строки, совпадающие с именами project.xtx. Сохраняет offset/type/code/source/target, но не объявляет их вызовами или переходами.
  - Synthetic unit tests для обоих анализаторов. Игровые бинарники, диалоги и ассеты в Git не добавлялись.
- Независимый повтор предиката на 20 модулях архива находит те же 511 ссылок на имена модулей. Негативный контроль сохраняется: те же типы записей ссылаются на множество обычных Pool 2 строк; runtime-переходы не включать.
- Следующий этап: побайтно сопоставить каждую строку scene.dat с payload соответствующего указателя descriptor 3 и @scNN; затем исследовать 511 кандидатов в пределах конкретных film intervals и control-flow. Модульные call/return и автоматический выбор entrypoint не реализовывать до доказанного target/return behavior.
- CI для нового кода должен быть подтверждён на итоговом head. Физическое устройство и полное прохождение игры не проверялись.


### Дополнение 2026-11-05 — индекс исходного film для ссылок на модули

- SystemNnnCommandStreamReader теперь отдаёт полный проверенный descriptor-3 pointer vector через filmTableEntries(): table index, ordinary film number (null для reserved prefix), target command offset.
- SystemNnnModuleReferenceScanner добавляет к каждой точной ссылке на module-name строку ближайший предшествующий физический film-entry target: sourceFilmTableIndex и sourceFilmNumber.
- Это только привязка к исходному интервалу по физическому положению. Она не доказывает, что строковая ссылка вызывает модуль, и не устанавливает целевой film в принимающем модуле.
- Добавлен синтетический тест с reserved-prefix элементом и двумя обычными film intervals.
- Первый CI #379 выявил ошибку в отрицательном тестовом fixture: запись объявляла 4 DWORD, хотя содержала 5. Fixture исправлен на валидные 5 DWORD с некандидатным reference tag. Исправленный head ожидает завершения CI; зелёный результат пока не заявлять.
- Следующий шаг: сгруппировать кандидатов по (source module, source film, record family/code, target module) и исследовать соседние branch/control records. VM-вызовы, call stack и module auto-start не включать до подтверждения destination film и return convention.


### Результат независимой группировки кандидатов по исходным film-интервалам

- Повторный анализ 20 SPT из hui(4).zip подтвердил, что все 511 точных ссылок на имена модулей имеют preceding ordinary film-table owner.
- 511 кандидатов распределены по 173 различным исходным film-интервалам в 7 модулях: 01 (2), 03 (19), akane (49), aoi (173), kiika (213), yamiko (17), yuka (38).
- Это подтверждает структурную привязку ссылок к исходным film-интервалам, но не модульный переход/вызов и не целевой film.
- 15 ссылок 0x3333/0x001A и соответствующие same-index 0x3333/0x001B остаются в разных исходных film-интервалах (13 раньше / 2 позже), не являются локальной парой call/return.
- Следующий шаг: создать детерминированный диагностический отчёт module → source film → record family/code → target Pool 2 string и сравнить соседние control-flow records. Автоматический module dispatch и call stack остаются выключенными.


### Новый диагностический экспорт ссылок на модули

- В SystemNnnModuleReferenceCandidate теперь есть границы исходного film-интервала и предыдущая/следующая запись из того же интервала. Для соседей хранятся только offset, identifier type, code family/id, длина и максимум 4 непрозрачных DWORD payload.
- Добавлен SystemNnnModuleReferenceReportWriter: детерминированный UTF-8 отчёт по project → module → source film → кандидаты; максимум 20 000 кандидатов; текст реплик и игровые ресурсы не экспортируются; вызывающий код владеет потоком.
- В Resource Inspector → "Текстовые данные" добавлена кнопка "Сохранить карту ссылок на модули (.txt)". Экспорт идёт в фоне, читает все найденные project.xtx и их sibling SPT, сохраняет предупреждения по отдельным модулям и использует системный Android CreateDocument.
- CI #394 на предыдущем head обнаружил, что синтетические строки 0x3333/0x50 и 0x3333/0x52 в тесте сами совпадали с фильтром [1, Pool2Index]. Тестовый fixture исправлен: эти записи больше не ссылаются на имя целевого модуля. Следующий CI должен подтвердить исправление и новый экспорт.
- Этот экспорт остаётся диагностикой. Автоматический выбор стартового модуля, module call/return и проигрывание сцен не включены.


### 2026-11-05 — фактические script-control переходы

- По 20 SPT из hui(4).zip найдено 29 type-2 CONTROL записей family 0x5556 с одним Pool 2 операндом, точно разрешающимся в имя из project.xtx:
  - 14 SCRIPT из sys → op1, 01, 02, 03, 04, 05, 06, 07, 08, 082, akane, aoi, kiika, day;
  - 3 SCRIPTJUMP: 01 → yuka; 03 → yamiko; yuka → yuka2;
  - 12 SUBSCRIPT → _staff из 03 (1), akane (3), aoi (3), kiika (3), yamiko (1), yuka (1).
- Публичный NNNLib source другой игры (pinned tinyan/SystemNNN@78a05053c78b3b80065a9a209c6685a7c60a2e83) подтверждает семейные имена кодов и общий механизм загрузки spt/<module>.spt, но не доказывает target-specific return behavior. SCRIPT/SUBSCRIPT в reference используют вложенный script level; SCRIPTJUMP заменяет текущий.
- Тот же источник определяет 0x3333/0x0053 как CONFIGMASK; предыдущие 13 совпадений _staff у этого кода — не переходы.
- Добавлен read-only SystemNnnScriptTransferScanner и тесты. Resource Inspector экспортирует SCRIPT-переходы отдельно от общих строковых ссылок.
- Native transfer dispatch проверен по самому `hui/gss.exe`: SCRIPT/SUBSCRIPT/SCRIPTJUMP читают только Pool 2 DWORD по смещению record+0x0C, передают имя модуля callback и продвигают PC на длину текущей записи. DWORD по record+0x10 — начало следующей записи (её length), но target handler не использует его как selector. Поэтому ViewModel сохраняет точный caller continuation `record.endOffsetExclusive`; speculative `returnSelectorCandidate` удалён. Не переносить `AdjustScriptCall` из другого NNNLib title.


### 2026-11-05 — локальные SUB связаны с descriptor 3

- Все 1 069 CONTROL SUB (0x5556/0x0009) из 20 модулей имеют два DWORD операнда. Первый находится в диапазоне descriptor 3 и указывает на валидный 5-DWORD function record 0x3333/0x50 или reserved 0x3333/0x51.
- Второй операнд в каждом модуле — уникальная плотная нумерация локальных SUB ID от 0 до N−1.
- SUB по модулям: sys 9, backspt 22, 01 65, 02 50, 03 182, 04 67, 05 37, 06 9, 07 27, 08 16, 082 58, akane 105, aoi 89, kiika 115, yamiko 68, yuka 109, day 8, op1 19, yuka2 13, _staff 1.
- CONTROL RETURN записей 1 008, поэтому не сопоставлять их попарно с SUB по порядку.
- Добавлен read-only SystemNnnLocalSubroutineScanner; экспорт показывает SUB ID → descriptor3 slot → target offset/function.
- Выполнение SUB/RETURN и runtime call stack остаются выключенными до доказательства target-specific return behavior.
### 2026-11-05 — SETFILM and film block sequence

- Проверка 20 SPT по физическим descriptor-3 интервалам: все 948 ordinary films имеют ровно одну SYSTEM_FUNCTION 0x3333/0x001A SETFILM с payload [1, N], за которой следует CONTROL 0x5556/0x000C ENDFILM.
- Для всех 948 N = descriptor3 tableIndex - reservedPrefixCount + 1. Ошибок/пропусков/дубликатов в ordinary intervals не найдено.
- В NNNLib reference SystemFunctionSetFilm сохраняет N−1 как текущий film number активного SPT. ENDFILM передаёт управление outer loop, это не общий конец приложения. При локальном SUB после ENDFILM следующая CONTROL RETURN возвращает к сохранённому PC.
- Ramen декодирует SETFILM, валидирует номер относительно descriptor3 owner, сохраняет currentFilmNumber в состоянии ViewModel; ENDFILM теперь последовательный переход к следующей записи, CONTROL END остаётся концом управляющего потока.
- Добавлен интеграционный тест: SUB → descriptor3 target → SETFILM → ENDFILM → RETURN → caller continuation.
- Связь 69 строк scene.dat / 69 @scNN / 69 descriptor3 в 01.spt всё ещё только совпадение количества, не доказанное сопоставление по индексу. Gallery navigation, межмодульные SCRIPT, sys startup и физическое прохождение на устройстве не реализованы.

### 2026-11-05 — project routing and native scene.dat

- Реализована межмодульная маршрутизация по CONTROL SCRIPT (0x5556/0x0A), SUBSCRIPT (0x5556/0x16), SCRIPTJUMP (0x5556/0x15). В целевых записях один DWORD Pool 2, который разрешается в токен модуля project.xtx. Ramen сохраняет точный command continuation сам: SCRIPT/SUBSCRIPT push, SCRIPTJUMP replace, NEXT pop/return, END reset sys, EXIT завершает.
- Подгружаются все 20 модулей основного spt/project.xtx; альтернативный !eng files выбирается только если основной отсутствует и альтернативный manifest единственный.
- IF/ELSIF/ELSE теперь вычисляет bounded RPN и пропускает brace-block по длине. Переменные общие между module decoders, local SUB stack остаётся отдельным для каждого.
- Исправлен scene.dat parser: layout fileSize + characterCount + character groups; внутри sceneCount, затем строки sptNumber, filmCount, filmNumbers[]. abs(sptNumber) — прямой индекс project.xtx; abs(filmNumber)-1 — нулевой film index внутри соответствующего SPT. Минус у sptNumber отмечает H-scene; минус у filmNumber отключает completion tracking, но фильм остаётся воспроизводимым.
- В primary archive: 1 character group, 69 scene rows, 150 film references; индексы модулей: 4→03, 10→082, 11→akane, 12→aoi, 13→kiika, 14→yamiko, 15→yuka, 18→yuka2. Все 150 film refs валидны внутри descriptor3 своего модуля; 43 помечены отрицательным значением и не участвуют в completion checks.
- Activity готовит module list, descriptor3 offsets и scene.dat. Запуск scene route доступен через EXTRA_SCENE_CHARACTER_INDEX, EXTRA_SCENE_INDEX и необязательный EXTRA_SCENE_FILM_CHOICE_INDEX.
- ENDFILM в normal story режиме продолжает поток; в scene replay запускает следующий фильм этой строки scene.dat, после последнего заканчивает replay.
- Видимый gallery picker ещё не добавлен. CI/APK/автотесты не запускались по запросу.


### 2026-11-05 — target SPT transfer return path verified from native executable

- Source: cleaned user archive `hui(5).zip`; no script dialogue/media was extracted.
- PE32/i386 `hui/gss.exe`: transfer handlers at VA `0x00417000` (SCRIPT), `0x00417059` (SUBSCRIPT), `0x004170B2` (SCRIPTJUMP). Each reads only the Pool 2 index at record+0x0C, resolves the module name, calls the transfer callback, then advances the active PC by the current record length. No selector read at record+0x10 occurs.
- The following DWORD is only the next record length. Keep exact caller continuation in the module frame; remove any saved selector candidate. The earlier family-source interpretation of `m_stack[253]` / `AdjustScriptCall` belongs to another title/save-restore path and is not target runtime behavior.
- Existing SCRIPT/SUBSCRIPT push and SCRIPTJUMP replace behavior remain. Follow-up native disassembly of `gss.exe` at VAs `0x00409ACC`, `0x0040E560`, `0x00411CB0` and `0x004106E0` confirms exactly three executor slots (modes 0..2): sys/controller at slot 0, main module at slot 1, nested module at slot 2. Normal project start allows at most 2 call frames; direct scene replay starts from slot 1 and allows 1. The callback's Japanese overflow warning says no further lower-level script may be called. Ramen rejects overflow instead of overwriting slot 2. No CI, tests, lint or APK build were run, per current direction; no on-device/end-to-end test is claimed.


### 2026-11-06 — native SELECT/CASE verification

- Target `gss.exe`: SELECT handler at VA `0x0041720A`; CASE lookup at VA `0x00417570`.
- Full scan of all 20 primary SPT modules: 46 SELECT records; all option/prefix Pool 2 indexes resolve, and each SELECT has CASE numbers exactly `1..N` (N=1..4). The one prefix-row case is handled by the existing decoder. All 104 case bodies are brace-wrapped and use spans that resolve to the next CASE boundary after skipping a closing brace when present.
- All 46 records use the extended shape: `recordLengthWords = optionCount + prefixCount + 8`. After the option/prefix text references are three opaque metadata DWORDs, followed by the final prefix-count DWORD. All 138 metadata values are zero; prefix count is nonzero only once (`op1`, value 1).
- Native SELECT handler `0x0041720A` reads the final DWORD as prefix count and forwards the two immediately preceding metadata DWORDs in its UI callback payload. The earlier of the three metadata DWORDs is not accessed by this dispatch path; its possible `selectSerial` role remains unproven.
- `GetCase` at `0x00417570` receives the requested 1-based CASE number as a separate argument and searches the CASE records for that number. No evidence ties the suspected `selectSerial` word to CASE routing. The decoder intentionally leaves all three metadata DWORDs opaque; there is no nonzero sample in this archive from which to infer their runtime effect.
- Corrected initial brace handling: target GetCase skips an initial wrapper OPEN_BRACE by record length only. Ramen previously skipped the full brace span; it now mirrors the native behavior. Added a regression test with an outer wrapper brace.
- No CI/tests/lint/APK build run per current direction. The new regression test is committed but not executed.

### 2026-11-06 — native IF/ELSIF/ELSE and CALCU verification

- Target `gss.exe` IF dispatch at VA `0x00416611` evaluates the bounded RPN condition and branches on signed-positive result (`eax > 0`). A true condition resumes immediately after the IF/ELSIF record so its body executes.
- The false path advances past the condition record, skips the following OPEN_BRACE span, and resumes at the next ELSIF/ELSE marker. Once one branch has been taken, later ELSIF/ELSE markers skip their own bodies. Existing Ramen `decodeIfBranch`, `skipFollowingBranch` and `skipBraceBlock` match these transitions for the observed archive structures.
- Full scan of all 20 primary SPT modules: 490 IF/ELSIF/ELSE records; all 490 have bounded brace bodies whose declared spans land on valid record boundaries. Longest observed ELSIF chain is 11 (the runtime guard remains 64).
- The 341 inline-RPN IF/ELSIF records use only variable, number, inclusive comparison, logical, and strict comparison token families. Observed operators are covered by the current evaluator; no unhandled token family appeared in this conditional set.
- Confirmed one CALCU mismatch in native `gss.exe` at VA `0x00417690`: integer divide and modulo replace a zero divisor with 1 before the native divide operation. The same fallback is used by compound divide/modulo. Ramen now mirrors that behavior rather than throwing on a zero divisor.
- Added synthetic tests for false IF→ELSIF→ELSE traversal, skipping later alternatives after a true IF, and the division-by-zero fallback. Tests were not run, per current direction.

### 2026-11-06 — project-level System-NNN asset manager

- Metadata-only inventory of the cleaned `hui(5).zip`: `hui/dwq/sc0.gtb` + `sc0.gpk` is one indexed image bank with 69 rows; `hui/cdvaw/gg.vtb` + `gg.vpk` is one indexed Ogg audio bank with 90 rows and a terminal VTB offset sentinel.
- The same archive also contains two loose `.vaw` files (`vaw/se/asioto.vaw`, `vaw/sys/01kagami_off.vaw`) without a VTB index, plus `wgq/gss01_1.wgq` with a `PACKTYPE=6` Ogg signature and no GTB. These remain catalogued as unindexed candidates; no playback/index relationship is inferred.
- Added `SystemNnnAssetManager`: discovers all same-stem GTB↔GPK/WGQ and VTB↔VPK/VAW pairs below the imported game directory, validates each pair through the existing bounded parsers, exposes path-derived bank IDs/counts/warnings, and keeps image/audio index spaces separate.
- Name lookup must resolve to one bank. Unqualified numeric image/voice indexes work only with exactly one bank in that namespace; multiple banks require an explicit bank ID. No concatenated global index is invented.
- The manager implements both the scene bitmap resolver and audio repository contract and is now injected into the player for both services. Close is idempotent because the ViewModel releases both interfaces independently.
- Image/audio payloads remain in the imported folder; images decode on demand and selected Ogg streams are staged in app cache. Added synthetic tests for multiple image banks, duplicate-name/index ambiguity, VTB/VPK discovery, and an unindexed WGQ candidate.
- No CI, tests, lint or APK build was run. The standalone `PACKTYPE=6` WGQ and loose VAW candidates are catalogued but not yet decoded or mapped to SPT commands.

- Follow-up native asset disassembly: `gss.exe` formats `wgq\\%s.mml` and `wgq\\%s.wgq` from the same resource token in the loader at VA `0x0047B440`. It first attempts the MML path; if that open fails, it tries the WGQ path and loads that file instead. A separate loader at VA `0x0047B060` opens `wgq\\%s.wgq`, seeks past the 64-byte preamble, and passes the remaining file stream to a dedicated reader/decoder at `0x0047B790`.
- The archive contains `wgq/gss01_1.wgq` and `wgq/gsstxt01.mml`; the basenames differ. The code path uses one supplied token for both candidate paths, so there is no evidence that these two files form one pair. The asset catalog now inventories `.mml` files separately; it does not bind them to WGQ by folder order or proximity.
- The WGQ stream reader is not yet implemented in Ramen. Do not pass the post-header bytes directly to MediaPlayer merely because this WGQ carries an Ogg signature; the native path uses a dedicated streaming reader.

- Native DWQ image-manager initialization at VA `0x0043C100` enumerates the ten-entry prefix table at `0x493B40`; the non-empty native namespaces are `ta`, `bg`, `ev`, `sc`, `sm`, and `sys`. For each numbered slot it formats `dwq/<prefix><slot>.gtb` and the matching `.gpk`. GTB lookup normalizes ASCII case and searches the indexed names before resolving the GPK block.
- `SystemNnnAssetBankInfo` now retains the native image namespace and numeric slot when the index filename matches one of those target patterns (for example, `sc0.gtb` → namespace `sc`, slot `0`). Path-derived bank IDs remain the unique catalog keys; this is metadata, not a claim that SPT commands already choose the correct namespace.
- Candidate archive fallback is deterministic and validated: for a same-stem GTB/VTB, the manager tries preferred extensions first (`GPK` before `WGQ`, `VAW` before `VPK`) and continues to the next candidate if parsing fails. The chosen file is marked paired only after a successful parse. Synthetic tests cover both image and audio fallback cases.


### 2026-11-06 — loose VAW/WGQ audio materialization

- Added a separate bounded loose-audio reader for standalone `.vaw`/`.wgq` resources without a VTB row. It recognizes Black Cyc `PACKTYPE=0` WAVE, `PACKTYPE=1` differential PCM, `PACKTYPE=2` Ogg (64-byte resource header + 44-byte descriptor), and `PACKTYPE=6` Ogg with the native `OGG ` tag at header offset 0x10.
- `PACKTYPE=1` reconstructs signed 16-bit differential samples into a temporary WAVE stream using an LSB-first bit reader. `PACKTYPE=0/2/6` stages only the stream bytes after the native wrapper.
- `SystemNnnAssetManager` owns this loose-resource repository, exposes validated loose resource metadata, and deletes staged temporary files on close. Name lookup must resolve to one resource; a relative path is available when basenames collide.
- Indexed audio remains unchanged: the 90 VTB rows are the only numeric dialogue-voice namespace. Loose VAW/WGQ resources are name/path-addressed and are not appended to that index.
- Added synthetic coverage for PACKTYPE 0, 1 and 6 plus WGQ manager integration. CI, tests, lint and APK builds were not run. No game data, dialogue text or media was committed.


- Added explicit `loadBitmapFromNativeBank(namespace, slot, name)` and `loadBitmapByNativeBankIndex(namespace, slot, row)` APIs. They resolve only a unique metadata-matched bank and fail closed on duplicate namespace/slot pairs; no SPT operand is assumed to choose the namespace.
