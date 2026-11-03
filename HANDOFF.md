# Ramen — единый handoff проекта

> **Это главный файл для восстановления контекста между сессиями.** Перед любыми изменениями сначала прочитай его целиком, затем проверь актуальное состояние GitHub. Здесь сведены карта репозитория, архитектура, статус функций, хронология, ветки/PR, результаты аудита и открытые вопросы.
>
> Снимок подготовлен **2026-11-04** по доступному репозиторию и материалам текущей сессии. Статусы GitHub и CI изменяются; перед продолжением всегда перепроверь их.

## Как возобновить работу в новом чате

Передай следующему чату одну фразу:

> **Продолжаем Ramen. Репозиторий `HelterFall/Ramen`. Сначала прочитай корневой `HANDOFF.md` в ветке `feat/system-nnn-spt-loader`, затем проверь актуальный PR #27 и CI. Не начинай работу с main, пока не сравнишь ветки и не проверишь статус.**

Важные ссылки:
- Репозиторий: https://github.com/HelterFall/Ramen
- Этот файл: https://github.com/HelterFall/Ramen/blob/feat/system-nnn-spt-loader/HANDOFF.md
- Текущая рабочая ветка: https://github.com/HelterFall/Ramen/tree/feat/system-nnn-spt-loader
- Основной PR с System-NNN: https://github.com/HelterFall/Ramen/pull/27
- SPT-заметки: [docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md](docs/SYSTEM_NNN_SPT_FORMAT_NOTES.md)
- Хронологический журнал: [docs/DEVELOPMENT_LOG.md](docs/DEVELOPMENT_LOG.md)
- Матрица веток и сборок: [docs/BUILD_MATRIX.md](docs/BUILD_MATRIX.md) — какие функции реально попадают в APK и какие Actions их проверяли

**Важно:** на дату снимка System-NNN находится в незлитой ветке и PR #27. Корневой `main` отстаёт от этой реализации. Не делай вывод, что код уже в main, и не переключайся туда автоматически.

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

## 10. Ветки и PR — критически важно

На момент аудита три PR открыты и не merged:

| PR | Ветка | Статус на снимке | CI, известный при проверке | Содержание |
|---|---|---|---|---|
| [#25](https://github.com/HelterFall/Ramen/pull/25) | `research/gpk-vpk-readonly-parsers` | Open, not merged; 191 commits / 63 files | Run #219 success | GPK/GTB, VPK/VTB parsers and resource work; branch очень крупная |
| [#26](https://github.com/HelterFall/Ramen/pull/26) | `feature/blackcyc-resource-preview` | Open, not merged; 6 commits / 20 files | Run #224 success | Black Cyc resource preview, DWQ, opaque SPT detection |
| [#27](https://github.com/HelterFall/Ramen/pull/27) | `feat/system-nnn-spt-loader` | Open, not merged; 53 commits / 46 changed files | Run #270 success on code snapshot `4a400535...`; run #275 queued for docs-only head `e0c33ae...` | Resource inspector + experimental System-NNN loader/player |

Снимок Git:
- `main` head в ходе аудита: `305f3f25c09c7f2d13c9d0ad7027f9124dcf7884`.
- Точный PR #27 head не фиксируем статически: после handoff/log/README обновлений SHA изменился; смотри актуальный `head SHA` в [PR #27](https://github.com/HelterFall/Ramen/pull/27). В ходе этого аудита PR diff относительно main показывал 45 changed files; изменения после проверенного кодового snapshot `648c4d...` были документационными.
- PR #27 основан на main и не содержит #26 как ancestor: branches разошлись от main.
- Compare #26 → #27 показал diverged branches: обе ветки основаны на main, #27 не содержит unique commits из #26 как ancestor. После этого в #27 добавлялись только handoff/readme/log commits. Всегда перепроверяй live graph before merge.
- Compare #25 → #26: ветки тоже diverged; #26 не включает длинную 191-commit историю #25.
- Все три PR меняют одни и те же resource/importer/detector файлы в разных комбинациях.

**Не сливать PR #25, #26 и #27 по очереди без reconciliation.** Сначала решить, какая ветка становится канонической, затем сравнить diff и перенести необходимые изменения так, чтобы не потерять fixes и не получить повторные/конфликтующие реализации.

Дополнительный риск PR #25:
- среди его файлов есть `ramen-inventory-d9c1071b.json` размером около 12 451 строк;
- inventory reports могут включать названия локальных файлов и структуру каталогов;
- перед merge надо проверить этот JSON на приватные пути/данные и понять, нужен ли он в репозитории вообще.

PR #27 на снимке был mergeable по metadata, но это не заменяет проверку тестов, code review и проверку конфликтующих веток. Не делай merge без явного решения пользователя.

## 11. CI и известные проблемы

### PR #27 — текущий CI

- Старый run #230: https://github.com/HelterFall/Ramen/actions/runs/41605660823
- Build и `assembleDebug` прошли.
- Unit tests: 85 завершено, 1 failure.
- Падающий тест: `HtmlInlineResourceExtractorTest > leavesUnsupportedDataUrisUntouched`.
- Стек указывает на `HtmlInlineResourceExtractorTest.kt:54`, вызов extractor.
- Задача `:app:testDebugUnitTest` упала; lint-анализ запускался, APK upload был пропущен.
- Run #265: https://github.com/HelterFall/Ramen/actions/runs/41671391772 — **success** на промежуточном commit snapshot `648c4d6052dcd3a95d0ca9f14c210392c33eed70`.

Причина старого test failure установлена по сравнению кода до/после. При записи временного `.index.html.rewritten`, который лежит в `gameDirectory`, старый код передавал `StorageSpaceGuard(assetsDirectory)`. В тесте с неподдерживаемым data URI папка `assets` не создаётся; проверка `assetsDirectory.usableSpace` возвращала ноль, и guard выдавал IOException. Исправлено на `StorageSpaceGuard(gameDirectory)`. После исправления run #265 прошёл. Для будущих storage tests всё равно предпочтительно использовать injected `availableSpaceBytes` / controlled fake, чтобы тесты не зависели от реального свободного места.

### Сопоставление последних PR builds
- PR #25 run #219 — success, head `b388d449...`, branch `research/gpk-vpk-readonly-parsers`.
- PR #26 run #224 — success, head `e6daf72f...`, branch `feature/blackcyc-resource-preview`.
- PR #27 run #265 — success, intermediate head `648c4d...`.
- PR #27 run #270 — success на текущем head `4a400535...`; это последняя проверенная сборка.
- PR #19, #20, #22, #23, #24 имели успешные CI по development log; ссылки записаны в `docs/DEVELOPMENT_LOG.md`.

### Обязательная последовательность после правок
Из корня проекта:

```bash
./gradlew --version
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Windows:

```powershell
.\gradlew.bat --version
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

CI не заменяет реальный Android device acceptance для HTML5, System-NNN, orientation, audio, memory и lifecycle.

## 12. Приоритетный план продолжения

### P0 — зафиксировать базу и CI
1. Перепроверить branch/head PR #27, PR status и самый новый Android CI run.
2. Исторический failure #230 исправлен изменением storage guard root; run #270 зелёный на code snapshot. Последующий run #275 относится только к docs-only изменениям. При новых code errors извлекать точную причину по логу, не гадать.
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
1. Решить, какие commits из #25/#26 нужны в #27 или наоборот.
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


## Дополнение 2026-11-04 — контроль включённых функций в APK

Подробная матрица: [docs/BUILD_MATRIX.md](docs/BUILD_MATRIX.md).

- Сборка из main на проверенном head 305f3f25... не содержит ResourceInspectorActivity / shortcut и не содержит System-NNN player.
- Сборка из PR #27 включает Inspector намеренно: Activity зарегистрирована в Manifest, shortcut показывается всегда, карточки импортированных игр имеют действие просмотра пар ресурсов.
- Сборка из PR #27 также включает System-NNN Activity и экспериментальный decoder; запуск доступен для SPT, который loader классифицирует как PARSER_AVAILABLE.
- У decoder пока есть подтверждённые расхождения с исследовательскими SPT notes; это не production-ready interpreter.
- CI workflow собирает только debug APK, запускает unit tests и lint, затем загружает артефакт на 7 дней. Release signing / Play Store publish отсутствуют.
- PR #27 run #270 прошёл на exact head 4a400535...; его APK — debug artifact. Для выбора правильной ветки и проверки комплектации APK сначала сверяй BUILD_MATRIX.md.
