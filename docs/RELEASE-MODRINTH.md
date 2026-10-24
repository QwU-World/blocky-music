# Публикация Blocky Music на Modrinth: бета

Порядок действий на день загрузки. Всё проверено по официальным источникам и по исходникам самого
Modrinth (`github.com/modrinth/code`) — именно они решают, пустят ли проект на ревью.

⚠️ `modrinth-code.mintlify.app` и `mintlify.wiki/modrinth/code` выглядят как документация, но это не
домен Modrinth и местами они врут. Не пользоваться.

## Что нужно решить до загрузки

**Лицензия решена (17.09.2026), по модели osu!:** собственный код QwU World — MIT, собственные текстуры,
модели, blockstates, проекты Blockbench, анимации и звуки — CC BY-NC 4.0, названия «QwU World»/«Blocky
Music» и логотипы не лицензируются, музыка и контент игроков не покрываются ничем. Правообладатель
везде — QwU World. Файлы: `LICENSE` (стандартный текст MIT без правок), `LICENSE-ASSETS.md`, `NOTICE.md`
(точные пути и исключения; в его начале определено имя «Blocky Music License»). `build.gradle` кладёт все
три файла в `META-INF/` jar. В `gradle.properties` стоят `mod_license=MIT AND CC-BY-NC-4.0`,
`mod_authors=QwU World` и описание на английском с дисклеймером Minecraft.

**Цель** — правило Modrinth 5.1 ([Content Rules](https://modrinth.com/legal/rules)): метаданные «filled
out correctly and are consistent with information found elsewhere». Раздел 5 там назван правилами,
которые «will not be enforced as strictly», но расхождения всё равно дают лишние вопросы на ревью. Jar,
файлы, страница лицензии и страница на Modrinth должны говорить одно и то же. **Сейчас это не так.** Jar в
`build/libs` собран 19.09 в 13:47: `license = "MIT AND CC-BY-NC-4.0"`, `authors = "QwU World"`, описание с
дисклеймером и три файла в `META-INF/` в нём уже правильные, но он старше правок 24.10 — внутри лежат
`font/OFL.txt` вместо `font/license-ofl.txt`, прежняя `textures/item/music_link.png` и прежние тексты
`NOTICE.md` и `LICENSE-ASSETS.md`. Страница лицензии на qwu.world открывается (пункт 7), но несёт ровно те же
устаревшие тексты, то есть её надо перезалить. Листинг на Modrinth не заполнен. Правило будет соблюдено после
пунктов ниже, **в этом порядке**.

Готовый текст листинга (Summary, Description на двух языках, блок раскрытий, поля версии и лицензии) лежит в
`docs/MODRINTH-LISTING.md` — он написан под правила из этого файла и проверен по ним.

Что осталось до загрузки:

1. **Файлы, найденные аудитом происхождения** (решения владельца 17.09.2026):
   - `sounds/entity/smiley_damage.ogg` — **оставлен по решению владельца, из мода не убирается.** Звук с
     SampleFocus (тег Vorbis «Downloaded from Samplefocus.com»). И `NOTICE.md`, и `LICENSE-ASSETS.md` теперь
     говорят одно и то же: используется как часть мода по SampleFocus Standard License, вынесен из обеих
     лицензий QwU World, извлекать и распространять отдельно нельзя. **Остаточный риск принят (17.09.2026):**
     та же лицензия запрещает «readily extractable format», а jar — это zip-архив, то есть сэмпл из него
     достаётся. Владелец на это пошёл сознательно; ни один документ больше не обещает, что файл заменят
     к бете. Если SampleFocus или автор сэмпла пожалуется — тогда заменить звук своим и поправить
     `sounds.json`.
   - ~~`shaders/core/fx_blit.vsh`~~ **сделано:** файл удалён, все семь JSON в `shaders/core/` ссылаются на
     `"vertex": "minecraft:blit_screen"`. По `ShaderInstance.getOrCreate` (строки 184–186 исходников NeoForge
     21.1.230) пространство имён берётся из самого имени, так что грузится ванильный файл игрока.
     **В игре не проверено: при тесте убедиться, что полноэкранные эффекты рисуются.**
   - `textures/entity/test_rap_god_master.png` — **решено:** это скин владельца (dragon4ik), входит в
     CC BY-NC 4.0 как собственный арт QwU World.
2. ~~Переименовать «Rap God»~~ **решено: оставляем.** «Rap God» — имя NPC («бог рэпа»), а не отсылка к
   песне; скин собственный. ID `test_rap_god_master` и `rap_god_config_wand` не меняются.
3. **Происхождение.** Правило 4 Content Rules: загружающий «must own or have the necessary licenses,
   rights, consents, and permissions to store, share, and distribute the Content».
   - ~~`blockbench/player_anims.bbmodel`~~ **сделано:** копия в ресурсах удалена, в jar её больше нет.
     Корневой `player anims.bbmodel` в jar не попадает; подтвердить происхождение или удалить.
   - ~~`textures/item/music_link.png`~~ **решено 24.10.2026:** ответа «кто рисовал» так и не было, поэтому
     старый файл не подтверждали, а заменили своим. Теперь текстуру рисует
     `tools/make-music-link-texture.py` — пиксельная сетка ролей, раскрашенная токенами `client/ui/Palette`
     на hue 265, без сторонних библиотек; `python3 tools/make-music-link-texture.py` пересобирает PNG,
     `--preview` кладёт лист с проверкой читаемости на светлом и тёмном слоте. Это собственная работа QwU
     World по тому же принципу, что и `fft()` ниже: алгоритм писали под задачу владельца, генеративная
     модель изображение не рисовала, так что запрет Modrinth на AI-картинки сюда не попадает. Предмет
     MUSIC_LINK (`models/item/music_link.json`) продолжает работать, имя файла не менялось.
   - ~~`fft()` в `client/ClientAudioAnalyzer.java`~~ **решено:** анализатор написан ИИ по запросу владельца,
     это собственный код QwU World под MIT.
   - Подсказка яйца призыва шарика (`tooltip.music_battle.smiley_ball_spawn_egg.flavor`) — **решено:
     оставляем**, это пародийная отсылка без имени персонажа.
4. **`NOTICE.md` и `LICENSE-ASSETS.md` обновлены** под решения из пунктов 1–3 (последняя правка 24.10.2026,
   дата «Last updated» в `NOTICE.md` совпадает). В «Pending confirmation» обоих файлов остался один
   `player anims.bbmodel` из корня репозитория — в jar его нет, и оба файла это прямо говорят, поэтому
   строку про «Pending confirmation» в таблице «Licences at a glance» трогать не нужно. Если владелец
   подтвердит или удалит и его, раздел опустеет — тогда убрать и сам раздел, и упоминание в таблице.
5. ~~Согласие авторов~~ **получено 17.09.2026:** владелец подтвердил за SpaidyNator и dragon4ik, что их
   код и арт выходят от имени QwU World под этими лицензиями. CC-лицензии безотзывны, после публикации
   назад не забрать.
6. **Пересобрать jar и проверить его** (собирает владелец, не агент: во время параллельных правок gradle
   не запускать). Загружать только jar, прошедший все проверки:
   - `unzip -p build/libs/blocky_music-0.1.0.jar META-INF/neoforge.mods.toml` — там
     `license = "MIT AND CC-BY-NC-4.0"`, `authors = "QwU World"` и новое описание с дисклеймером;
   - `unzip -l build/libs/blocky_music-0.1.0.jar | grep META-INF` — есть `META-INF/LICENSE`,
     `META-INF/LICENSE-ASSETS.md`, `META-INF/NOTICE.md`;
   - `unzip -l build/libs/blocky_music-0.1.0.jar | grep -Ei 'fx_blit|player_anims'` — **пусто.** Только эти
     два файла из аудита происхождения действительно удалены. Старые SHA-1, если вдруг замена легла под тем
     же именем: `fx_blit.vsh` = `2bab2448d80364c1499017472190e1d4e0a748b9`;
   - `smiley_damage.ogg` и файлы `rap_god` в jar **должны быть** — это решения из пунктов 1 и 2, а не
     недосмотр. У звука ожидается прежний SHA-1 `727c0089bfed2ce2a8d4c1b07b1386c4b3916229`
     (`unzip -p … assets/music_battle/sounds/entity/smiley_damage.ogg | shasum`), у скина владельца —
     `70aef3043f62b6d0be9a84f6e008b521fb598c1d`. Если звук когда-нибудь всё-таки заменят, вместе с ним
     править `sounds.json` и обе лицензии;
   - `unzip -l build/libs/blocky_music-0.1.0.jar | grep -i 'font/.*ofl'` — ровно
     `assets/music_battle/font/license-ofl.txt`, и **никакого** `font/OFL.txt`. В нынешнем jar там ещё
     `OFL.txt`: Minecraft не принимает путь ресурса с заглавной буквой, и пока файл так назывался, в логе
     каждого игрока была строка `Invalid path in pack: music_battle:font/OFL.txt, ignoring` (видно в
     `run/logs/debug-2.log.gz` от 23.10.2026). Проверку держит `python3 tools/run-check.py ui-toolkit`;
   - `unzip -p build/libs/blocky_music-0.1.0.jar assets/music_battle/textures/item/music_link.png | shasum`
     — `74ce93ebb4852984d1f6a4f6be17a873a9064cdb`, 153 байта: то, что выдаёт
     `python3 tools/make-music-link-texture.py`. В нынешнем jar лежит старый файл на 196 байт
     (`fc2f285b159f2ebfe159d0392df5f71ce379ec79`), происхождение которого так и не подтвердили;
   - `unzip -p build/libs/blocky_music-0.1.0.jar META-INF/NOTICE.md | diff - NOTICE.md` — пусто; то же для
     `LICENSE` и `LICENSE-ASSETS.md`. Это текст после пункта 4, с датой «Last updated» 24.10.2026.
7. ~~**Страница лицензии на qwu.world**~~ **сделано 19.09.2026:**
   `https://qwu.world/mods/minecraft/blocky-music/license` открывается на двух языках и несёт полный текст
   `NOTICE.md`, `LICENSE` и `LICENSE-ASSETS.md`. Файлы лежат копией в `qwu-site/src/data/licences/blocky-music/`;
   при любой правке в моде — `node scripts/sync-blocky-music-license.mjs --apply` в `qwu-site` и деплой **до**
   того, как новый jar уйдёт на Modrinth. Ниже остаётся то, что относится к содержанию страницы.
   - Заголовок страницы — «Blocky Music License», первым идёт абзац из начала `NOTICE.md`, который
     объясняет это имя (оно будет в поле License на Modrinth).
   - Относительные ссылки `[LICENSE](LICENSE)`, `[LICENSE-ASSETS.md](LICENSE-ASSETS.md)` и
     `[NOTICE.md](NOTICE.md)` при переносе заменить якорями на этой же странице или абсолютными URL: как
     есть они ведут на `/mods/minecraft/blocky-music/LICENSE` и т. п., а там 404.
   - При любой правке `NOTICE.md` страницу перезаливать (вместе с датой «Last updated»), и до того, как
     URL уходит в поле License на Modrinth. **Сейчас она устарела:** копии в
     `qwu-site/src/data/licences/blocky-music/` от 19.09 всё ещё называют шрифтовую лицензию `font/OFL.txt`
     и держат `music_link.png` в «Pending confirmation», а про SampleFocus говорят, что файл заменят к бете.
     Перед отправкой на ревью — `node scripts/sync-blocky-music-license.mjs --apply` и деплой.
   - Валидатор Modrinth страницу не открывает: для `LicenseRef-*` он проверяет только, что URL не пустой,
     разбирается и не на запрещённом домене
     ([license.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/validate/project/license.rs)).
     Ссылка на 404 форму пройдёт, но нарушит правило 5.1 («filled out correctly»). Страница должна
     открываться до отправки на ревью.
8. **В описание на Modrinth** перенести из `NOTICE.md` строку «NOT AN OFFICIAL MINECRAFT PRODUCT. NOT
   APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT», отказ от связи с ppy и The Funkin' Crew,
   фразу «музыку приносит игрок» и контакт `it.qwuworld@gmail.com`. [Minecraft Usage
   Guidelines](https://www.minecraft.net/en-us/usage-guidelines) просят ставить этот дисклеймер «on your
   product, listing, description, website/webpage, and all other related materials» и указывать контакт
   (не чат и не форум). В самом моде он уже есть: в описании для списка модов (`mod_description` в
   `gradle.properties`) и в `META-INF/NOTICE.md`.

## Проект

- Создавать **под организацией**: в модалке есть селектор Owner. Организация уже есть и подтверждена:
  **QwU World**, id `1mEZGdT0`, slug `qwu-world`.
- **Name:** `Blocky Music`. **Никаких версий в названии** — регулярка `\d+(?:\.\d+)+` даёт блокирующее
  замечание (исключение только для названий со словом port или fork). «Minecraft» в коротком названии
  тоже нельзя.
- **Slug:** `blocky-music`.
- **Summary:** минимум 25 знаков, **по-английски**, без разметки, без ссылок, и не повторять название.
  Четыре отдельных блокирующих проверки.
- **Description:** минимум 125 знаков осмысленного текста, **английский первым**, русский ниже.
  Нельзя: дублировать summary, ставить два заголовка одного уровня подряд, заканчивать заголовком.
- **Environment:** `client_and_server` — «Client and server / Required on both». Старую пару
  `client_side`/`server_side` объявили неоднозначной и заменили, ставить надо новое поле.
- **License:** в выпадающем списке выбрать **Custom**, отметить **«License does not have a SPDX
  identifier»**, в имя вписать `Blocky Music License` (сохранится как
  `LicenseRef-Blocky-Music-License`). Это имя определено в начале `NOTICE.md` и в заголовке страницы
  лицензии (пункт 7) — иначе модератор увидит имя, которого нет ни в одном файле. **License URL обязателен:**
  `https://qwu.world/mods/minecraft/blocky-music/license`. Без URL валидатор даёт блокирующее
  замечание на любой `LicenseRef-*`, кроме `Unknown` и `All-Rights-Reserved`. Ссылку нельзя ставить на
  modrinth.com, Discord, YouTube, t.me и подобные: валидатор помечает их как неподходящие.
  - **Не вписывать `MIT AND CC-BY-NC-4.0` в поле SPDX.** Бэкенд это примет, но поиск Modrinth берёт
    только слово до первого пробела и покажет мод как «MIT, open source». Это неправда: на ассеты
    действует NC, а исходники закрыты.
  - В `gradle.properties` стоит `MIT AND CC-BY-NC-4.0`; в jar (`neoforge.mods.toml`) оно попадёт только
    после пересборки (пункт 6), в нынешнем jar ещё `MIT`. По
    [документации NeoForge](https://docs.neoforged.net/docs/gettingstarted/modfiles) это поле — «The license
    the mod(s) in this JAR are provided under», то есть однострочная сводка на весь jar без исключений.
    Исключения лежат в `META-INF/NOTICE.md` внутри jar и на странице лицензии. Там же, в начале
    `NOTICE.md`, записана связь имён: `LicenseRef-Blocky-Music-License` на Modrinth = `MIT AND
    CC-BY-NC-4.0` в jar с исключениями из `NOTICE.md`. Поле оставить чистым SPDX-выражением, ссылку в него
    не дописывать.
  - Ссылка на исходники не нужна: её требуют только GPL/LGPL/AGPL/MPL.
- **Icon:** до 256 КиБ. Требования к размеру в пикселях в официальной документации нет.
- **Галерея для мода не обязательна** (обязательна только для шейдеров и ресурспаков).
- **Изображения, сделанные нейросетью, запрещены** везде: иконка, галерея, описание, changelog.

## Версия

- **Номер:** `0.1.0-beta.1`. **Канал:** `beta`. Это две независимые вещи, ставить обе.
- **Loader:** только `neoforge`. `forge` — отдельный тег, его не трогать.
- **Game version:** только `1.21.1`. Не добавлять `1.21` только потому, что его указывает
  playerAnimator.
- **Файл:** `build/libs/blocky_music-0.1.0.jar` — **только после пересборки и проверок из пункта 6**
  (нынешний файл от 19.09 13:47 устарел: `font/OFL.txt`, старая `music_link.png`, прежние тексты лицензий).
  Не dev-jar и не sources.
- **Зависимость:** playerAnimator, **required**, проект `gedNE4y2`, slug `playeranimator`, автор KosmX.
  ⚠️ Есть двойник: `player-animation-library` (`ha1mEyJS`, автор ZigyTheBird) — это **другой** проект,
  и поиск выдаёт его первым. Подходящая сборка: `2.0.4+1.21.1-forge`, id `HJZB6bmA`.
  Флага «только клиент» у зависимости на Modrinth **нет** — сторонность пишется в описании.
- Java 21 указать в описании: поля для версии Java у Modrinth нет.

## Раскрытия (Content disclosures)

Для беты обязательных блока два: **Network access** (Мастерская уже ходит на qwu.world) и **External system
interactions** (мод запускает FFmpeg). Пустым остаётся только **Telemetry**. Разбор по пунктам:

- Правило 1.11 запрещает **отправлять** данные на сервер, который пользователь не выбирал в игре,
  **без явного раскрытия**. Скачивание под букву этого правила не попадает, но полагаться на узкое
  прочтение не стоит.
- **Telemetry** — если собираем статистику. Указать, кто имеет доступ, анонимно ли, и включено по
  умолчанию или нет.
- **External system interactions** — про действия вне игры (чужие папки, микрофон, запуск программ).
  Запись скачанных карт в свою же папку мода под это не подпадает, **а запуск `ffmpeg` подпадает**, поэтому
  блок заполняется (ниже).
- **Мастерская (волна M2a, 17.09.2026)** уже содержит сетевой клиент и экран согласия. Текст для блока
  раскрытий на странице (публичный, по-английски; совпадает с экраном согласия в игре):

  > **Network access (opt-in).** Nothing is sent until you allow it on the Workshop's first-run screen.
  > After that the mod contacts `blocky-music.qwu.world` to browse, download and check updates of beatmaps,
  > and for uploads, comments and ratings; `sessionserver.mojang.com` only when you sign in with your
  > Minecraft account (your Minecraft access token goes only to Mojang); `api.minecraftservices.com` and
  > `textures.minecraft.net` for skin heads of server-comment authors. These services see your IP address and
  > the mod version; the workshop also receives your searches, the maps you install or check, and your QwU World
  > account token after you sign in. Installed maps, drag-and-drop and the inbox folder work without network
  > access. You can turn network access off in the Workshop at any time.

  **External system interactions — требуется, и давно.** Раньше здесь было написано «не требуется», и это
  было неправдой: `NOTICE.md` в том же jar прямо говорит, что мод запускает FFmpeg отдельным процессом. Так
  оно и есть — `client/ClientFfmpeg.java` и `server/MusicBattleTracks.java` ищут `ffmpeg`, запускают его с
  `-version`, чтобы проверить, а потом с реальными аргументами. Три места у клиента (конвертация в редакторе
  чартов, аудио из импортированной карты osu!, копии трека на 1.5x и 2x при сохранении) и одно у сервера
  (подготовка ускоренного аудио к битве, `createSpeedVariant` на рабочем потоке). Ищут сперва внутри папки
  игры, затем в `C:\ffmpeg\bin`, `C:\Program Files\ffmpeg\bin`, `/opt/homebrew/bin`, `/usr/local/bin`,
  `/usr/bin`, `/snap/bin`, последним — в PATH; то есть мод берёт программу **вне** папки игры. Сам FFmpeg мод
  не кладёт и не качает, а при его отсутствии говорит об этом и работает дальше: просмотр, установка и игра
  без FFmpeg работают. Кроме него наружу смотрят ещё три вещи: системный диалог выбора файла
  (`TinyFileDialogs` в `WorkshopFiles`, игрок может выбрать файл где угодно, мод его читает и копирует к
  себе), `Util.getPlatform().openPath` на папках мода (просим ОС открыть проводник) и ссылки в браузере через
  ванильный `ConfirmLinkScreen`. Пишет мод только внутрь папки Minecraft, с одним исключением на несколько
  секунд: `ClientOsuManiaImporter.convertToOgg` и `ClientPreviewProcessor.processForPublication` создают по
  временному файлу через `Files.createTempFile` в системном временном каталоге и удаляют его. «Remember me»
  (refresh token) лежит внутри папки игры: `music_battle_tracks/.account/session.json`, права 0600 (папка
  0700), запись атомарная, файл появляется только после галочки игрока и удаляется при выходе из аккаунта.
  Готовый текст блока, по-английски и дословно, — в `docs/MODRINTH-LISTING.md`.

  **Микрофона мод не трогает.** Единственный «микрофон» здесь — предмет в игре. Захвата звука нет:
  `javax.sound.sampled` используется только ради `AudioFormat` при декодировании, ни одного `TargetDataLine`
  в коде нет.

### Помощник QwU Workshop Helper (волна I1, 18.09.2026) — своих раскрытий не добавляет

Установка «в один клик» с сайта сделана **отдельной программой**, не модом. Разделение важно именно для этого
блока раскрытий: помощник ничего к нему не прибавляет, но и пустым его не оставляет — пустым он не был уже
из-за FFmpeg.

- **Мод ничего не регистрирует в операционной системе.** Ни реестра Windows, ни `Info.plist`, ни `.desktop`,
  ни ассоциаций файлов, ни URL-схем. Тип файла `.blockymap` и схему `blockymusic://` берёт на себя помощник,
  которого игрок ставит сам и отдельно (`installer/` в репозитории мастерской). Мод его не скачивает,
  не запускает и не проверяет, есть ли он вообще.
- **Папку игры помощник не расширяет.** Он кладёт файл `<setId>.blockymap-request` в
  `music_battle_tracks/workshop_inbox/` — ту же папку входящих, что и раньше; мод её читает, как читал пакеты.
  Ничего нового за пределами папки Minecraft от него не появляется.
- **Помощник не выходит в сеть.** Он не качает карту и не знает про аккаунты: скачиванием занимается сам мод,
  под тем же согласием на сеть (`WorkshopRequests`, `WorkshopDownloader`). Если согласия нет, запрос просто лежит
  в папке, а при следующем открытии Мастерской показывается экран согласия.
- **Новых запусков программ помощник не добавляет.** Единственный процесс, который запускает сам мод, —
  это `ffmpeg` из раскрытия выше; помощника мод не вызывает никогда.

Итого: блок **External system interactions** заполняется из-за FFmpeg, а волна I1 в него ничего не
дописывает. Предупреждения Gatekeeper и SmartScreen относятся к помощнику и описаны на `/help/install#helper`
на сайте, а не на странице мода.
- Отдельного правила про аудио у Modrinth **нет**. Работают общие: не нарушать авторские права и не
  перезаливать чужое. Практический вывод для нас: **в jar не должно быть ни одного чужого трека**, а в
  описании надо прямо сказать, что музыку приносит пользователь и он же за неё отвечает.

## Отправка на ревью

Кнопка **Submit for review** разблокируется, только когда не осталось замечаний уровня required.
Дальше: draft → processing («Under review») → approved.

- Заявленный срок 24–48 часов, но там же предупреждают, что бывает и «upwards of two weeks».
- Пустая вкладка Moderation означает только очередь, а не потерю.
- Правки и новые версии в очереди позицию **не меняют**, а вот **удаление старых версий может
  отправить проект в конец очереди**. Ничего не удалять.
- Писать в поддержку бесполезно и может замедлить.
- Списка типовых причин отказа Modrinth не публикует. Чеклист выше и есть де-факто предпроверка.

## Ловушки

1. **Валидатор jar только предупреждает, но молча стоит вам primary-файла.** Если в jar нет
   `META-INF/neoforge.mods.toml`, загрузка всё равно пройдёт, просто файл не пометится основным.
   После загрузки **проверить, что primary-файл правильный**.
2. **Modrinth не импортирует лицензию и описание из `neoforge.mods.toml`.** Автоопределение берёт
   только номер версии, подзаголовок, лоадеры, версии игры и environment.
3. **Один и тот же jar нельзя загрузить в два проекта** — сверяется SHA-1. Лимит файла 500 МиБ.
4. **Канал beta скрывает вас от автообновления**: приложение Modrinth по умолчанию считает обновлением
   только release. Для первой беты это нормально, но охват будет меньше.
5. Если позже появится сборка под Fabric — держать её **в том же проекте**, а не заводить второй.

## Источники

[Content Rules](https://modrinth.com/legal/rules) · [Terms of Use](https://modrinth.com/legal/terms) ·
[Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures) ·
[Project Review Times](https://support.modrinth.com/en/articles/8793355-modrinth-project-review-times) ·
[Version Number Recommendations](https://support.modrinth.com/en/articles/8793369-version-number-recommendations) ·
[Additional files](https://support.modrinth.com/en/articles/8793363-additional-files) ·
[Multiple loaders on one project](https://support.modrinth.com/en/articles/8824810-multiple-loaders-on-one-project) ·
[Verify Your Environment Metadata](https://modrinth.com/news/article/new-environments/) ·
[Streamlined Version Creation](https://modrinth.com/news/article/streamlined-version-creation/) ·
[Licensing guide](https://modrinth.com/news/article/licensing-guide/) ·
[AI policy and disclosures](https://modrinth.com/news/article/ai-policy-and-disclosures/) ·
[API: Create a version](https://docs.modrinth.com/api/operations/createversion/) ·
[Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) ·
[NeoForge: mods.toml](https://docs.neoforged.net/docs/gettingstarted/modfiles) ·
[SampleFocus Standard License](https://samplefocus.com/license) ·
[FFmpeg: Legal](https://ffmpeg.org/legal.html) ·
[SIL Open Font License 1.1](https://openfontlicense.org/open-font-license-official-text/) ·
лицензия в исходниках Modrinth: [license.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/validate/project/license.rs),
[settings/license.vue](https://github.com/modrinth/code/blob/main/apps/frontend/src/pages/%5Btype%5D/%5Bproject%5D/settings/license.vue),
[search/indexing.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/search/indexing.rs) ·
исходники валидации: [validate/project](https://github.com/modrinth/code/tree/main/apps/labrinth/src/validate/project),
[neoforge.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/validate/neoforge.rs),
[version_creation.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/routes/v3/version_creation.rs)
