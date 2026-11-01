# Архитектура Blocky Music

Документ описывает текущую реализацию по исходникам. Он не является планом будущих функций. Это карта верхнего уровня; подробная карта подсистем и их проверок — в [DEVELOPMENT.md](../DEVELOPMENT.md).

## Точка входа и платформа

- `MusicBattleMod` регистрирует блоки, block entity, предметы, сущности, звуки, творческую вкладку, сеть, серверный конфиг `MusicBattleServerConfig`, команды `/musicbattle`, узлы прав и серверные события.
- Клиентские регистрации находятся в `ClientModEvents`, игровые клиентские события — в `ClientGameEvents`.
- Проект собирается под Java 21, Minecraft 1.21.1 и NeoForge 21.1.x.
- Единственная внешняя зависимость в `build.gradle` — playerAnimator от KosmX (`dev.kosmx.player-anim:player-animation-lib-forge`, версия задана `player_animator_version` в `gradle.properties`). В `neoforge.mods.toml` она объявлена как мод `playeranimator`, обязательный на клиенте.

## Серверная модель

### Битвы

`BattleManager` — серверный фасад жизненного цикла боя. Он отвечает за:

- вызов, принятие и завершение боя;
- участников, зрителей и мобов;
- готовность после загрузки ресурсов;
- ввод, оценку нот, удержания, очки и ИИ;
- позиционирование арены, безопасность окружения и источники звука;
- передачу состояния камер, внешнего вида панелей и анимаций.

Отдельные части вынесены из него: серверные `BattleArenaPlacement` и `BattleCrowdSafety` и чистые, без классов Minecraft, `CrowdSafetyMath`, `BattleAudienceFocus`, `NoteSpeed` и другие классы из `data/`.

### DJ

`DjPlaybackManager` хранит серверные DJ-сессии, плейлист, время воспроизведения, скорость, аудионастройки, подключённые источники и получателей. Постоянные настройки стойки хранятся в `DjPanelBlockEntity` через NBT.

`DjVisualEffects.compose` объединяет FX карты и стойки. FX стойки получают отдельный runtime-порядок и располагаются выше FX карты без изменения сохранённых рядов карты; FX карты обрезаются так, чтобы вместе с FX стойки их было не больше 512.

`DjWorkshopCache` помнит, какие карты Мастерской из серверного кеша `workshop_cache` стоят в плейлистах стоек (в NBT стойки и в `SavedData` мира), восстанавливает их после перезапуска и не даёт очистке кеша удалить такие наборы.

### Карты и публикация

`MusicBattleTracks` загружает личные и серверные карты, нормализует ссылки на ресурсы, ограничивает значения и кеширует сведения об OGG. Серверный каталог привязан к текущему миру, собирается из трёх источников (`Source.SERVER` — `server_tracks`, `Source.USER` — `user_tracks`, `Source.WORKSHOP` — карты Мастерской, добавленные на сервер) и очищается при остановке сервера. Копии песни на 1.5x и 2x для голосования за скорость делаются в фоне через `NativeAudio` и лежат рядом с оригиналом (`_x15.ogg`, `_x2.ogg`).

`TrackPublicationManager` принимает частями карту, которую игрок публикует на сервер (в `user_tracks`). Ограничения текущего кода:

- JSON: 2 МиБ;
- OGG: 128 МиБ;
- обложка: 8 МиБ;
- 4096 нот;
- 512 FX;
- 32 опубликованные карты на игрока.

Перед установкой проверяются ожидаемый размер и SHA-256. Операции записи и удаления проверяют нормализованный путь и символические ссылки. Карту с неизвестным типом FX публикация отклоняет.

### Мастерская на сервере

Классы `server/workshop/`:

- `ServerWorkshop` — жизненный цикл, объявление возможностей клиенту (`WorkshopServerInfo`) и обработчики сообщений Мастерской.
- `ServerBeatmaps` — наборы Мастерской, установленные на сервер: `<мир>/blocky_music/workshop/ws_<setId>/`, индекс `server_beatmaps.index`, квоты `maxServerBeatmaps` и `serverBeatmapsQuotaBytes`.
- `ServerBeatmapTransfers` — пакеты, которые присылает клиент: «Добавить на сервер», голос за карту, установленную только у голосующего, и выбор такой карты в DJ-плейлисте. Сначала сервер отвечает `have`, `need` или `denied`, затем принимает куски по 24 КиБ, по одной передаче на игрока, и пишет их вне главного потока. Пакеты голосов и стоек ложатся в `<мир>/blocky_music/workshop_cache/<contentSha256>/`; старые наборы вытесняются по `cacheQuotaBytes`.
- Пакет от клиента устанавливается только с действительной подписью Ed25519 (`beatmap.sig`); проверяет её `WorkshopSignature` с открытым ключом, вшитым в мод. Без подписи, при нехватке прав или квоты сервер передачу отклоняет. Снятый набор клиент сам не отдаёт (по последней проверке `InstalledCheck`), а уже помеченный снятым набор сервер не даёт поставить в DJ-плейлист.
- Сервер сам к Мастерской не обращается и ничего с неё не скачивает: `allowServerDownloads` зарезервирован и меняет только текст отказа `/musicbattle workshop add`. Единственный веб-сервис, к которому может обратиться серверная часть, — профили Mojang: когда тестовому NPC выдаётся скин игрока не в сети и когда команда `/musicbattle` получает ник, которого нет в кеше профилей сервера (обычное поведение аргумента-игрока в Minecraft).
- `ServerComments` — комментарии сервера: `<мир>/blocky_music/comments/<key>.json` (ключ `ws_<setId>` или `track_<trackId>`), жалобы, муты и выгрузки — в `comments_meta/`. Записи атомарные и идут через один отдельный поток. Лимиты задаёт раздел `serverComments` серверного конфига.

### Права и команды

`WorkshopPermissions` — список доступа сервера в `SavedData` мира (`blocky_music_workshop_permissions`). Старый список публикации (`blocky_music_publish_permissions`) при первой загрузке переносится в записи с правом `publish`. Права и соответствующие узлы PermissionAPI:

- `publish` — публикация своих карт на сервер (`music_battle.publish`);
- `server_beatmaps` — добавление и удаление карт Мастерской сервера (`music_battle.server_beatmaps.manage`);
- `server_comments_moderate` — удаление чужих комментариев сервера в игре (`music_battle.server_comments.moderate`); чистка, муты и выгрузка — только командой `/musicbattle comments` (оператор уровня 2).

По умолчанию узел разрешает действие оператору уровня 2, владельцу одиночного мира и игроку из списка с этим правом. `/musicbattle access grant|revoke <игрок> [право]` выдаёт и снимает право (без права — `publish`), `access check <игрок>` показывает права игрока, `access list` — весь список; старое имя `publishaccess` оставлено как алиас. `access` требует уровень оператора 2 и не смотрит ни на список, ни на узлы, поэтому игрок из списка не может расширить свои права.

Остальные ветви `/musicbattle`: `accept`, `decline`, `beatmaps`, `tracks`, `reload`, `workshop list|info|add|remove|keep`, `published list|remove`, `comments delete|purge|mute|unmute|export|enable|disable`. Регистрирует их `MusicBattleCommands`.

## Клиентская модель

`ClientBattleController` хранит клиентскую сессию боя и зрителя: часы трека, звук, HUD, камеры, внешний вид панелей, состояние анимаций и переходы FX.

Основные рендер-модули:

- `BattlePanelRenderer` — общая отрисовка панели нот для боя, предпросмотра в настройках и предпросмотра в редакторе, а также карточки заголовка боя;
- `ClientTrackEffects` — выбор активных FX и их порядок;
- `ClientFxShaderEngine` — полноэкранные шейдерные эффекты;
- `ClientModelGlowEngine` — свечение моделей;
- `DjCameraPreviewRenderer` и `DjCameraFxCompositor` — live-монитор камеры;
- `ClientUiForeground` — отделение игровых FX от модальных окон редактора;
- `client/ui` — набор интерфейса Мастерской: фигуры одним SDF-шейдером (`ui_sdf`), текст шрифтом Nunito, своё поле ввода `TextField`, кеш обложек `CoverCache`.

Клиентские миксины перечислены в `music_battle.mixins.json`: они затрагивают модели существ и игрока, плащи, броню и предметы в руке, клавиатуру и мышь (в том числе брошенные на окно файлы), звук и экран настроек звука, камеру, render target, частицы и совместимость с Figura; `MinecraftConnectionAccessor` только читает ожидающее подключение к серверу, чтобы вход в Мастерскую не пересекался со входом на сервер.

### Мастерская в игре

Сервис Мастерской (blocky-music.qwu.world) — отдельный закрытый проект, в этот репозиторий он не входит. Мод обращается к его HTTP API v1. Формат пакета и исходный замысел описаны в проектном документе [WORKSHOP.md](WORKSHOP.md).

Ядро без экранов и без классов Minecraft — пакеты `workshop/` и `workshop/api/`:

- `WorkshopApi` — блокирующий HTTP-клиент: прокси игры, только адрес Мастерской, без перенаправлений. Без согласия на сеть он не отправляет ни одного запроса (`CONSENT_REQUIRED`).
- `WorkshopConfig` — `config/music_battle-workshop.json`: согласие на сеть с версией текста и список `autoSignIn`.
- `WorkshopSession` — вход в Мастерскую. Access-токен живёт только в памяти. Refresh-токен пишется только при «Запомнить меня на этом компьютере», в `music_battle_tracks/.account/session.json` (права 0600, где их поддерживает файловая система), меняется при каждом использовании и удаляется при выходе.
- `DeviceFlow` — вход по коду устройства (RFC 8628).
- `MinecraftSignIn` и `ServerIdHash` — вход аккаунтом Minecraft: `serverId` считается из nonce Мастерской и 32 собственных случайных байт и передаётся в `joinServer`. Токен доступа Minecraft читает только `client/workshop/MinecraftProof` и отдаёт его сразу в `joinServer`, больше никуда.
- `AutoSignInGate` — автоматический вход при открытии Мастерской: не больше одной попытки за запуск игры (`MinecraftProof` повторяет её один раз через 5 секунд, если вход перебил другой) и только для аккаунта Minecraft, который уже входил в Мастерскую на этом компьютере (его UUID записан в `autoSignIn`). Выход убирает запись, выключение сети её сохраняет.
- `WorkshopDownloader` — очередь загрузок, не больше двух одновременно: файл `music_battle_tracks/.workshop_downloads/<setId>-<revision>.part` с проверкой размера и SHA-256, затем установка, карточка `workshop-card.json` и `preview.ogg` рядом с набором. Скачанный пакет хранится в `.workshop_packages/` 7 дней.
- `BeatmapPackage` и `BeatmapManifest` — проверка архива `.blockymap` (zip с манифестом `beatmap.meta`) и атомарная установка в `music_battle_tracks/workshop/ws_<setId>/` через `.workshop_staging/`.
- `WorkshopCatalog` — установленные карты. Это состояние только клиента: в каталог `MusicBattleTracks` они не попадают.
- `InstalledCheck` — спрашивает Мастерскую об обновлениях и снятых наборах (автоматически не чаще раза в 10 минут) и хранит ответы в `music_battle_tracks/.workshop_state.json`. Передать набор на сервер или скопировать его в `my_tracks` можно, только если последняя проверка не старше 7 дней.
- `WorkshopInbox` и `WorkshopRequest` — папка `music_battle_tracks/workshop_inbox/`. Пакеты `*.blockymap` ставятся из файла, отклонённые уходят в `rejected/` с причиной в `.txt`. Файл `<setId>.blockymap-request` от внешнего помощника Мастерской только просит скачать набор; скачивает его сам мод, под своим согласием на сеть.
- `WorkshopUploader` — публикация карты в Мастерскую из игры: подготовка, загрузка файлов, обработка на сервисе.

Связь с игрой — пакет `client/workshop/`:

- `WorkshopClient` — один на клиент: держит конфиг, API, сессию, каталог, проверку, загрузчик, папку входящих и их потоки.
- `WorkshopClientHooks` — клавиша Alt+B (переназначаемая), кнопка в главном меню и меню паузы, файлы `.blockymap`, брошенные на окно, тик клиента.
- `WorkshopConsentScreen` — окно о сети при первом открытии: что Мастерская отправляет и куда. До «Разрешить и продолжить» в сеть ничего не уходит. «Не сейчас» тоже запоминается, и Мастерская открывается без сети: файлы, папка входящих и свои карты работают. Переключатель «Доступ к сети» на странице аккаунта (`AccountView`) включает сеть через то же окно и выключает её сразу, отменяя загрузки.
- `WorkshopScreen` — экран с вкладками «Обзор», «Установленные» и, на сервере с Мастерской, «Серверные карты», с видами `ListingView`, `SetPageView`, `AccountView` и `UploadWizardView`. Через `openPicker` тот же экран служит выбором карты в бою. Геометрия считается в чистом пакете `client/workshop/layout/`.
- `WorkshopDrops` и `WorkshopFiles` — установка из брошенного или выбранного файла и из папки входящих.
- `InstalledActions` — действия с установленным набором: открыть папку, скопировать в `my_tracks`, выбрать песню для набора без аудио (`ChosenAudio`), удалить.
- `ServerWorkshopClient` и `WorkshopServerHooks` — связь с Мастерской сервера; включаются, когда сервер прислал `WorkshopServerInfo`.

## Звук и конвертация

- `audio/codec/` — собственные декодеры на чистой Java: Ogg Vorbis, Ogg Opus, Ogg FLAC, WebM и Matroska, MP3 (и MPEG Layer I/II), FLAC, WAV (PCM, float, G.711, ADPCM), AAC, ALAC и Opus в MP4/M4A/MOV, AAC в ADTS и MPEG-TS. `AudioDecoders` выбирает декодер по первым байтам файла, а не по имени; неподдерживаемый формат даёт `UnsupportedFormatException`.
- Там же собственный кодировщик Ogg Vorbis `VorbisEncoder` (два прохода, психоакустическая модель `PsyModel`), смена темпа `TimeStretch` и ресемплер `SincResampler`.
- Любая конвертация в Ogg Vorbis идёт через `NativeAudio.convertToVorbis`. Её вызывают копии на 1.5x и 2x на сервере (`MusicBattleTracks`), импорт песни в редакторе (`TrackEditorScreen`), импорт карт osu! (`ClientOsuManiaImporter`) и выбор песни для набора Мастерской без аудио (`ChosenAudio`).
- Для звука и конвертации внешних программ (FFmpeg и подобных) мод не запускает: в `src/main/java` нет `ProcessBuilder` и `Runtime.exec`. Окно выбора файла (LWJGL) и «Открыть папку» (средство Minecraft) обращаются к системе так же, как в самой игре. `NativeAudio` не использует классы Minecraft, поэтому выделенный сервер конвертирует так же, как клиент.

## Сеть

`MusicBattleNetworking` регистрирует протокол `69`: 40 payload от сервера клиенту и 44 от клиента серверу (число вызовов `playToClient` и `playToServer` в этом классе).

Группы сообщений:

- голосование и выбор карты, список карт сервера (`ServerTracksRequest`/`ServerTracks`), пауза и пропуск;
- передача карты и ресурсов частями (с предложением `TrackAssetOffer`/`TrackAssetAnswer`, чтобы не слать то, что у игрока уже есть);
- карты Мастерской на сервере: `WorkshopServerInfo`, `ServerBeatmapsRequest/Page`, `ServerBeatmapOffer/OfferResult`, `ServerBeatmapChunk`, `ServerBeatmapTransferCancel/Abort`, `ServerBeatmapRemove`, `ServerBeatmapResult`, `WorkshopSetStateReport`, `VoteInstalled`;
- комментарии сервера: `ServerCommentsRequest/Page`, `ServerCommentPost/Delete/Report`, `ServerCommentResult`;
- старт, остановка, ввод, анимации, микрофон и оформление боя;
- зрители, аудитория и камеры;
- DJ-стойка, плейлист, FX, preview, preload и добавление карты Мастерской в плейлист (`DjWorkshopAdd/Result`);
- публикация и удаление пользовательских карт;
- конфигурация камер, колонок и NPC.

`ClientPayloadBridge` вызывает клиентские классы (`ClientBattleController`, `WorkshopClientHooks`, `DjPanelScreen`, `ClientTrackAssets`) через рефлексию, чтобы общая сетевая регистрация не загружала клиентские классы на dedicated server.

## Данные

- `BattleNote`: сторона 0–1, дорожка 0–3, тик начала и длительность удержания.
- `TrackEffect`: тип, ряд, слой, время, общие визуальные параметры и `TrackEffectSettings`.
- FX-таймлайн: 8 слоёв по 6 рядов, всего 48 рядов; карты со старой разметкой в 4 ряда на слой переносятся при чтении.
- `NoteAppearance`: одна форма и четыре независимых непрозрачных цвета.
- `DjPlaylistEntry`: трек, стартовая позиция, режим FX (`INHERIT`, `TRACK`, `DJ`, `NONE`), свои FX записи и микшер `DjPlaylistMixer`; в плейлисте до 64 записей.
- `DjVisualEffectConfig`: одна конфигурация DJ FX; активный набор ограничен восемью элементами.

JSON карты читается терпимо: отсутствующие поля получают значения по умолчанию, числа ограничиваются допустимыми диапазонами, неизвестный тип эффекта становится `flash`. Ряды удалённого эффекта `glitch` отбрасываются, старый эффект `color` становится `flash` с вариантом `steady`. Некорректный файл пропускается с записью предупреждения в лог.

## Хранение

В папке игры:

- клиентские настройки: `config/music_battle-client.json`, `config/music_battle-fx-tools.json`, `config/music_battle-editor-preview.json`;
- согласие на сеть и запомненные аккаунты Мастерской: `config/music_battle-workshop.json`;
- личные карты: `music_battle_tracks/my_tracks/`;
- файлы для импорта в редактор: `music_battle_raw/`;
- временная папка мода: `music_battle_tracks/.temp/` (очищается при запуске игры);
- карты и ресурсы, скачанные с серверов: `music_battle_tracks/.server_cache/<хеш адреса сервера или мира>/`;
- принимаемые публикации на стороне сервера: `music_battle_tracks/.uploads/`;
- установленные карты Мастерской: `music_battle_tracks/workshop/ws_<setId>/`;
- служебные папки Мастерской: `.workshop_downloads/`, `.workshop_packages/`, `.workshop_staging/`, `.workshop_offers/` (пакеты, которые клиент собирает для передачи на сервер), `.workshop_state.json`, кеш обложек `.workshop_cache/covers/`, вход `.account/session.json` (всё внутри `music_battle_tracks/`);
- папка входящих: `music_battle_tracks/workshop_inbox/` и `workshop_inbox/rejected/`;
- DJ-пресеты: `music_battle_dj_presets/{effects,equalizer,audio}/`;
- статистика боёв игроков: `music_battle_player_stats.json`.

В мире:

- серверные карты: `<мир>/blocky_music/server_tracks/`;
- карты игроков: `<мир>/blocky_music/user_tracks/`;
- карты Мастерской сервера: `<мир>/blocky_music/workshop/` и индекс `server_beatmaps.index`;
- кеш пакетов от игроков: `<мир>/blocky_music/workshop_cache/`, принимаемые пакеты — `<мир>/blocky_music/.workshop_uploads/`;
- комментарии сервера: `<мир>/blocky_music/comments/` и `comments_meta/`;
- `SavedData`: права (`blocky_music_workshop_permissions`) и наборы Мастерской в DJ-стойках (`blocky_music_dj_workshop_sets`);
- серверный конфиг `MusicBattleServerConfig` (тип SERVER в NeoForge): разделы `battle_sync`, `workshop` и `serverComments`.

Запись DJ-пресетов использует имя файла на основе SHA-256 нормализованного отображаемого имени, временный соседний файл и атомарную замену там, где её поддерживает файловая система.

## Ресурсы

В `src/main/resources` находятся:

- RU/EN-локализация;
- модели, текстуры, шейдеры (FX и интерфейса) и описание звуков `sounds.json` (сам звук из `sounds/` есть только в jar, см. [NOTICE.md](../NOTICE.md));
- шрифты Nunito для интерфейса Мастерской;
- loot tables, рецепты и достижения;
- Blockbench-модели анимаций игрока, колонки, DJ-стойки, провода, камеры и микрофона;
- конфигурация mixin.

Данные и ресурсы поддерживаются вручную: provider-классов Data Generator в проекте не найдено.

## Проверки

Gradle собирает основной исходный набор, но `src/test` отсутствует. Регрессии реализованы как 104 отдельных PowerShell-скрипта `tools/check-*.ps1` (число файлов по этой маске). Они компилируют тестовые harness-классы из `tools/` и местами используют скрытый OpenGL-контекст. На macOS и Linux те же проверки без PowerShell запускает `python3 tools/run-check.py <имя>` (или `all`): он читает `.ps1` и выполняет ту же компиляцию и тот же запуск с JDK 21. Библиотеки он берёт из кеша Gradle, поэтому сначала нужна сборка (`./gradlew compileJava`, на Windows `gradlew.bat compileJava`). Проверки ядра Мастерской работают с локальным поддельным сервером.

Скрипты доказывают только заявленный ими контракт. Они не заменяют запуск Minecraft, проверку с двумя клиентами, акустическую проверку и измерение FPS внутри игры.
