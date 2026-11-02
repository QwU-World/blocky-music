# Публикация Blocky Music на Modrinth: бета

Порядок действий на день загрузки. Всё проверено по официальным источникам и по исходникам самого
Modrinth (`github.com/modrinth/code`) — именно они решают, пустят ли проект на ревью.

⚠️ `modrinth-code.mintlify.app` и `mintlify.wiki/modrinth/code` выглядят как документация, но это не
домен Modrinth и местами они врут. Не пользоваться.

## Что решено до загрузки

**Лицензия решена (17.09.2026), по модели osu!:** собственный код QwU World — MIT, собственные текстуры,
модели, blockstates, проекты Blockbench и анимации — CC BY-NC 4.0, названия «QwU World»/«Blocky Music» и
логотипы не лицензируются, музыка и контент игроков не покрываются ничем. Собственных звуков в моде нет:
единственный звук — сэмпл SampleFocus (пункт 1). Правообладатель везде — QwU World. Файлы:
[`LICENSE`](../LICENSE) (стандартный текст MIT без правок), [`LICENSE-ASSETS.md`](../LICENSE-ASSETS.md),
[`NOTICE.md`](../NOTICE.md) (точные пути и исключения; в его начале определено имя «Blocky Music License»).
`build.gradle` кладёт все три файла в `META-INF/` jar. В `gradle.properties` стоят
`mod_license=MIT AND CC-BY-NC-4.0`, `mod_authors=QwU World` и описание на английском (`mod_description`, без
дисклеймера Minecraft: его убрали 24.10.2026; дисклеймер есть в `META-INF/NOTICE.md`, см. пункт 8).
Исходники лежат на GitHub: https://github.com/QwU-World/blocky-music. Исходники 0.2.0-beta.2 выложены туда вместе с
релизом, 1 ноября 2026, чтобы ссылка Source в листинге вела на код того jar, который проходит ревью. Каждая следующая версия уходит туда
так же, вместе со своим релизом: публичная копия (`git archive` с `:(exclude)` для двух файлов SampleFocus, см.
`DEVELOPMENT.md`) одним коммитом поверх прошлого. С 2 ноября 2026 мод вышел на CurseForge (https://www.curseforge.com/minecraft/mc-mods/blocky-music), и главная
ссылка `README.md` и «Download Blocky Music» в `.github/ISSUE_TEMPLATE/config.yml` ведут туда. Ссылка «Modrinth» в
`README.md` ведёт на https://modrinth.com/mod/blocky-music, а до одобрения эта страница отвечает «Project not found» (01.11.2026 `api.modrinth.com/v2/project/blocky-music` ещё давал 404); так было и с
0.1.0-beta.1. Что проверить после одобрения — в разделе «После одобрения».

**Цель** — правило Modrinth 5.1 ([Content Rules](https://modrinth.com/legal/rules)): метаданные «filled
out correctly and are consistent with information found elsewhere». Раздел 5 там назван правилами,
которые «will not be enforced as strictly», но расхождения всё равно дают лишние вопросы на ревью. Jar,
файлы, страница лицензии и страница на Modrinth должны говорить одно и то же. В jar 0.2.0-beta.2 это
проверено (пункт 6). Страница лицензии на qwu.world перезалита 1 ноября 2026 и совпадает с `NOTICE.md` этого
релиза (пункт 7); листинг на Modrinth заполняется из готового текста.

Готовый текст листинга (Summary, Description по-английски, блок раскрытий, поля версии и лицензии) лежит в
[`MODRINTH-LISTING.md`](MODRINTH-LISTING.md) — он написан под правила из этого файла и проверен по ним.

Решения и проверки:

1. **Файлы, найденные аудитом происхождения** (решения владельца 17.09.2026):
   - `sounds/entity/smiley_damage.ogg` — **оставлен по решению владельца, из мода не убирается.** Звук с
     SampleFocus (тег Vorbis «Downloaded from Samplefocus.com»). `NOTICE.md` и `LICENSE-ASSETS.md` говорят
     одно и то же: используется внутри мода по SampleFocus Standard License, вынесен из обеих лицензий QwU
     World, извлекать его из мода и распространять отдельно нельзя. В публичном репозитории исходников его
     нет, как и раскодированной копии `tools/vorbis-decoder-support/smiley_damage.libvorbis.f32`: она лежит
     только в рабочей копии QwU World. Если звук когда-нибудь заменят своим, вместе с ним править
     `sounds.json` и обе лицензии.
   - ~~`shaders/core/fx_blit.vsh`~~ **сделано:** файл удалён. Из восьми JSON в `shaders/core/` шесть
     (`color_correction`, `fish_eye`, `fx_model_mask`, `object_outline`, `pulse`, `vignette`) ссылаются на
     `"vertex": "minecraft:blit_screen"`, `ui_rounded_image` — на `minecraft:position_tex_color`, `ui_sdf` — на
     свой `music_battle:ui_sdf`. По `ShaderInstance.getOrCreate` (строки 184–186 исходников NeoForge 21.1.230)
     пространство имён берётся из самого имени, так что грузится ванильный файл игрока.
   - `textures/entity/test_rap_god_master.png` — **решено:** это скин владельца (dragon4ik), входит в
     CC BY-NC 4.0 как собственный арт QwU World.
2. ~~Переименовать «Rap God»~~ **решено: оставляем.** «Rap God» — имя NPC («бог рэпа»), а не отсылка к
   песне; скин собственный. ID `test_rap_god_master` и `rap_god_config_wand` не меняются.
3. **Происхождение.** Правило 4 Content Rules: загружающий «must own or have the necessary licenses,
   rights, consents, and permissions to store, share, and distribute the Content».
   - ~~`blockbench/player_anims.bbmodel`~~ **сделано:** копия в ресурсах удалена, в jar её больше нет.
   - ~~Корневой `player anims.bbmodel`~~ **решено 25.10.2026:** собственная работа QwU World, ранний
     черновик анимаций игрока; сборка его не читает и в jar он не попадает. Шесть кубов `ref_` в нём — просто
     стандартная фигура игрока Minecraft, а оставшийся от шаблона `model_identifier` очищен 01.11.2026. Анимации в `player_animations/`
     собирает `tools/convert_blockbench_player_animations.mjs` из `blockbench/bm_player_animation.bbmodel`
     (все девять файлов выходят байт в байт).
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
4. **`NOTICE.md` и `LICENSE-ASSETS.md` обновлены** под решения из пунктов 1–3; «Last updated» в `NOTICE.md` —
   1 November 2026. Ничего не ждёт подтверждения: раздел «Pending confirmation» в обоих файлах пуст, и таблица
   «Licences at a glance» на него больше не ссылается. Обёртка Gradle (`gradlew`, `gradlew.bat`,
   `gradle/wrapper/gradle-wrapper.jar`, Apache 2.0) записана отдельной строкой: она есть только в репозитории.
5. ~~Согласие авторов~~ **получено 17.09.2026:** владелец подтвердил за SpaidyNator и dragon4ik, что их
   код и арт выходят от имени QwU World под этими лицензиями. CC-лицензии безотзывны, после публикации
   назад не забрать.
6. **Проверка jar.** Загружать только jar, прошедший все проверки; после любой пересборки повторить:
   - `unzip -p build/libs/blocky_music-0.2.0-beta.2.jar META-INF/neoforge.mods.toml` — там
     `license = "MIT AND CC-BY-NC-4.0"`, `authors = "QwU World"` и описание из `mod_description` (без
     дисклеймера Minecraft, он в `META-INF/NOTICE.md`; в jar 0.2.0-beta.2 проверено);
   - `unzip -l build/libs/blocky_music-0.2.0-beta.2.jar | grep META-INF` — есть `META-INF/LICENSE`,
     `META-INF/LICENSE-ASSETS.md`, `META-INF/NOTICE.md`;
   - `unzip -l build/libs/blocky_music-0.2.0-beta.2.jar | grep -Ei 'fx_blit|player_anims'` — **пусто.** Старый
     SHA-1 `fx_blit.vsh`, если вдруг файл вернётся под тем же именем: `2bab2448d80364c1499017472190e1d4e0a748b9`;
   - `smiley_damage.ogg` и файлы `rap_god` в jar **должны быть** — это решения из пунктов 1 и 2, а не
     недосмотр. У звука SHA-1 `727c0089bfed2ce2a8d4c1b07b1386c4b3916229`
     (`unzip -p … assets/music_battle/sounds/entity/smiley_damage.ogg | shasum`), у скина владельца —
     `70aef3043f62b6d0be9a84f6e008b521fb598c1d`;
   - `unzip -l build/libs/blocky_music-0.2.0-beta.2.jar | grep -i 'font/.*ofl'` — ровно
     `assets/music_battle/font/license-ofl.txt`, никакого `font/OFL.txt` (в jar 0.2.0-beta.2 проверено).
     Minecraft не принимает путь ресурса с заглавной буквой: пока файл назывался `OFL.txt`, в логе каждого
     игрока была строка `Invalid path in pack: music_battle:font/OFL.txt, ignoring`. Проверку держит
     `python3 tools/run-check.py ui-toolkit`;
   - `unzip -p build/libs/blocky_music-0.2.0-beta.2.jar assets/music_battle/textures/item/music_link.png | shasum`
     — `74ce93ebb4852984d1f6a4f6be17a873a9064cdb`, 153 байта: то, что выдаёт
     `python3 tools/make-music-link-texture.py` (в jar 0.2.0-beta.2 проверено; прежний файл на 196 байт,
     `fc2f285b159f2ebfe159d0392df5f71ce379ec79`, ушёл);
   - `unzip -p build/libs/blocky_music-0.2.0-beta.2.jar META-INF/NOTICE.md | diff - NOTICE.md` — пусто; то же для
     `LICENSE` и `LICENSE-ASSETS.md`. В `NOTICE.md` «Last updated: 1 November 2026».
7. ~~**Страница лицензии на qwu.world**~~ **сделано 19.09.2026:**
   `https://qwu.world/mods/minecraft/blocky-music/license` открывается на двух языках и несёт полный текст
   `NOTICE.md`, `LICENSE` и `LICENSE-ASSETS.md`. Файлы лежат копией в исходниках сайта qwu.world (закрытый
   репозиторий, папка `src/data/licences/blocky-music/`); при любой правке в моде в нём запускается
   `node scripts/sync-blocky-music-license.mjs --apply`, и сайт деплоится **до**
   того, как новый jar уйдёт на Modrinth. Ниже остаётся то, что относится к содержанию страницы.
   - Заголовок страницы — «Blocky Music License», первым идёт абзац из начала `NOTICE.md`, который
     объясняет это имя (оно будет в поле License на Modrinth).
   - Относительные ссылки `[LICENSE](LICENSE)`, `[LICENSE-ASSETS.md](LICENSE-ASSETS.md)` и
     `[NOTICE.md](NOTICE.md)` при переносе заменить якорями на этой же странице или абсолютными URL: как
     есть они ведут на `/mods/minecraft/blocky-music/LICENSE` и т. п., а там 404.
   - При любой правке `NOTICE.md` страницу перезаливать (вместе с датой «Last updated»), и до того, как
     URL уходит в поле License на Modrinth (`node scripts/sync-blocky-music-license.mjs --apply` в репозитории
     сайта и деплой). Для 0.2.0-beta.2 это сделано 1 ноября 2026.
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
   (не чат и не форум). В самом моде он есть только в `META-INF/NOTICE.md` (раздел о Minecraft). Описание
   для списка модов (`mod_description` в `gradle.properties`, `neoforge.mods.toml`, ключ
   `fml.menu.mods.info.description.music_battle` в `lang/`) его не несёт: его оттуда убрали 24.10.2026.

## Проект

- Создавать **под организацией**: в модалке есть селектор Owner. Организация уже есть и подтверждена:
  **QwU World**, id `1mEZGdT0`, slug `qwu-world`.
- **Name:** `Blocky Music`. **Никаких версий в названии** — регулярка `\d+(?:\.\d+)+` даёт блокирующее
  замечание (исключение только для названий со словом port или fork). «Minecraft» в коротком названии
  тоже нельзя.
- **Slug:** `blocky-music`.
- **Summary:** минимум 25 знаков, **по-английски**, без разметки, без ссылок, и не повторять название.
  Четыре отдельных блокирующих проверки.
- **Description:** минимум 125 знаков осмысленного текста, **только по-английски**; подробное описание
  по-русски — в репозитории, [`docs/OVERVIEW.ru.md`](OVERVIEW.ru.md).
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
    только слово до первого пробела и покажет мод как «MIT, open source». Это неправда: исходники открыты
    на GitHub, но на ассеты действует NC, так что мод целиком не open source.
  - В `gradle.properties` стоит `MIT AND CC-BY-NC-4.0`, и в jar 0.2.0-beta.2 (`neoforge.mods.toml`) было
    проверено именно оно (пункт 6). По
    [документации NeoForge](https://docs.neoforged.net/docs/gettingstarted/modfiles) это поле — «The license
    the mod(s) in this JAR are provided under», то есть однострочная сводка на весь jar без исключений.
    Исключения лежат в `META-INF/NOTICE.md` внутри jar и на странице лицензии. Там же, в начале
    `NOTICE.md`, записана связь имён: `LicenseRef-Blocky-Music-License` на Modrinth = `MIT AND
    CC-BY-NC-4.0` в jar с исключениями из `NOTICE.md`. Поле оставить чистым SPDX-выражением, ссылку в него
    не дописывать.
  - Ссылка на исходники лицензией не требуется (её требуют только GPL/LGPL/AGPL/MPL), но исходники
    открыты, так что в Links её можно поставить: Source — https://github.com/QwU-World/blocky-music,
    Issues — https://github.com/QwU-World/blocky-music/issues.
- **Icon:** до 256 КиБ. Требования к размеру в пикселях в официальной документации нет.
- **Галерея для мода не обязательна** (обязательна только для шейдеров и ресурспаков).
- **Изображения, сделанные нейросетью, запрещены** везде: иконка, галерея, описание, changelog.

## Версия

- **Номер:** `0.2.0-beta.2` (было `0.1.0-beta.1`; номер берётся из `mod_version` в `gradle.properties`, по нему же Gradle называет jar). **Канал:** `beta`. Это две независимые вещи, ставить обе.
- **Loader:** только `neoforge`. `forge` — отдельный тег, его не трогать.
- **Game version:** только `1.21.1`. Не добавлять `1.21` только потому, что его указывает
  playerAnimator.
- **Файл:** `build/libs/blocky_music-0.2.0-beta.2.jar` — **только после проверок из пункта 6.**
  Не dev-jar и не sources.
- **Зависимость:** playerAnimator, **required**, проект `gedNE4y2`, slug `playeranimator`, автор KosmX.
  ⚠️ Есть двойник: `player-animation-library` (`ha1mEyJS`, автор ZigyTheBird) — это **другой** проект,
  и поиск выдаёт его первым. Подходящая сборка: `2.0.4+1.21.1-forge`, id `HJZB6bmA`.
  Флага «только клиент» у зависимости на Modrinth **нет** — сторонность пишется в описании.
- Java 21 указать в описании: поля для версии Java у Modrinth нет.

## Раскрытия (Content disclosures)

У Modrinth в Settings → Disclosures такие переключатели: AI-generated content, AI functionality, Advertisements,
Paid features, Telemetry, Derivative content, Photosensitivity, External system interactions, Archive. Отдельного
блока «Network access» **нет** (раньше этот файл ошибался): сеть Мастерской раскрывается в **Telemetry**
(включено, consent model opt-in). Включаются Telemetry, External system interactions, Photosensitivity и
AI-generated content (code и text), остальное выключено. Готовые тексты — в `docs/MODRINTH-LISTING.md`. Разбор
по пунктам:

- Правило 1.11 запрещает **отправлять** данные на сервер, который пользователь не выбирал в игре,
  **без явного раскрытия**. Скачивание под букву этого правила не попадает, но полагаться на узкое
  прочтение не стоит.
- **Telemetry** — если собираем статистику. Указать, кто имеет доступ, анонимно ли, и включено по
  умолчанию или нет.
- **External system interactions** — про действия вне игры (чужие папки, микрофон, запуск программ).
  Запись скачанных карт в свою же папку мода под это не подпадает, **а выбор файла где угодно на компьютере и
  просьба к ОС показать папку — подпадают**, поэтому блок заполняется (ниже).
- **Мастерская** содержит сетевой клиент и экран согласия. Пункты Telemetry в
  `docs/MODRINTH-LISTING.md` говорят то же, что экран согласия в игре (ключи
  `screen.music_battle.workshop.consent.*`). Исправлено 01.11.2026: `sessionserver.mojang.com` нужен не только
  для входа через аккаунт Minecraft, но и для голов скинов авторов серверных комментариев (`client/ui/SkinHead`:
  имя → `api.minecraftservices.com`, профиль с подписанными текстурами → session server, текстура →
  `textures.minecraft.net`), и экран согласия теперь так и пишет. Скин тестового NPC, заданный ником, в мире
  грузится штатным механизмом Minecraft (`getProfileCache` на сервере, session server и
  `textures.minecraft.net` у клиента), как у ванильной головы игрока. Превью ника в экране настройки NPC
  (`RapGodConfigScreen`) ищет ник само: клиент через `SkinHead` отправляет его в
  `api.minecraftservices.com` (поиск профиля по имени), потом берёт профиль у session server и текстуру с
  `textures.minecraft.net`. Это вне Мастерской и без её согласия, наружу уходит только набранный ник, и
  только к серверам Mojang; в Telemetry это одна строка.
  Автоматический вход в Мастерскую через Minecraft идёт только для аккаунта Minecraft, который на этом
  компьютере хотя бы раз уже вошёл в Мастерскую: мод запоминает это локально по UUID Minecraft
  (`config/music_battle-workshop.json`), а явный выход из аккаунта эту отметку стирает. Тогда при первом
  открытии Мастерской за запуск мод отправляет имя и UUID, чтобы войти снова. Игрок, который ни разу не входил,
  автоматически ничего не отправляет. Это же пишут экран первого запуска и `MODRINTH-LISTING.md`.

  **External system interactions — требуется.** До 27.10.2026 причиной был FFmpeg: мод искал его вне папки игры
  и запускал отдельным процессом. Теперь звук в Ogg Vorbis (редактор чартов, песня импортированной карты osu!,
  копии на 1.5x и 2x на сервере) переводит собственный код мода, `audio/NativeAudio` и `audio/codec/`; ни
  `ProcessBuilder`, ни `Runtime.exec` в исходниках нет, и это проверяет `native-audio`. Наружу смотрят три вещи:
  системный диалог выбора файла (`TinyFileDialogs` в `WorkshopFiles`, игрок может выбрать файл где угодно, мод
  его читает и копирует к себе; в Linux LWJGL показывает его через zenity или kdialog),
  `Util.getPlatform().openPath` на папках мода (просим ОС открыть проводник) и ссылки в браузере через
  ванильный `ConfirmLinkScreen`. Пишет мод только внутрь папки Minecraft: временный PNG картинки карты, который
  `ClientPreviewProcessor.processForPublication` готовит для публикации карты на сервер, создаётся внутри папки
  игры (`music_battle_tracks/.temp`) и удаляется, когда отправка кончилась или сорвалась (то, что оставила убитая
  игра, удаляется при следующем запуске). Туда же идёт песня, выбранная для карты Мастерской без песни, пока она
  конвертируется (`ChosenAudio`). Остальные конвертации звука (редактор, импорт osu!, копии 1.5x и 2x,
  `NativeAudio.convert`) пишут недоделанный файл `.<имя>.<число>.part` рядом с готовым и удаляют его в конце;
  такой файл, оставленный убитой игрой, удаляет следующая конвертация в ту же папку, когда он устарел. «Remember me» (refresh token) лежит внутри папки игры: `music_battle_tracks/.account/session.json`,
  права 0600 (папка 0700), запись атомарная, файл появляется только после галочки игрока и удаляется при выходе
  из аккаунта.
  Готовый текст блока, по-английски и дословно, — в `docs/MODRINTH-LISTING.md`.

  **Микрофона мод не трогает.** Единственный «микрофон» здесь — предмет в игре. Захвата звука нет:
  `javax.sound.sampled` используется только ради `AudioFormat` при декодировании, ни одного `TargetDataLine`
  в коде нет.

### Помощник QwU Workshop Helper — своих раскрытий не добавляет

Установка «в один клик» с сайта сделана **отдельной программой**, не модом. Разделение важно именно для этого
блока раскрытий: помощник ничего к нему не прибавляет, но и пустым его не оставляет — пустым он не был и без
него.

- **Мод ничего не регистрирует в операционной системе.** Ни реестра Windows, ни `Info.plist`, ни `.desktop`,
  ни ассоциаций файлов, ни URL-схем. Тип файла `.blockymap` и схему `blockymusic://` берёт на себя помощник,
  которого игрок ставит сам и отдельно (он входит в сервис Мастерской, его исходники не публичные). Мод его не скачивает,
  не запускает и не проверяет, есть ли он вообще.
- **Папку игры помощник не расширяет.** Он кладёт файл `<setId>.blockymap-request` в
  `music_battle_tracks/workshop_inbox/` — ту же папку входящих, что и раньше; мод её читает, как читал пакеты.
  Ничего нового за пределами папки Minecraft от него не появляется.
- **Помощник не выходит в сеть.** Он не качает карту и не знает про аккаунты: скачиванием занимается сам мод,
  под тем же согласием на сеть (`WorkshopRequests`, `WorkshopDownloader`). Если согласия нет, запрос просто лежит
  в папке, а при следующем открытии Мастерской показывается экран согласия.
- **Новых запусков программ помощник не добавляет.** Сам мод программ не запускает (FFmpeg ему больше не
  нужен); помощника мод не вызывает никогда.

Итого: блок **External system interactions** заполняется из-за диалога выбора файла и «Открыть папку», а
помощник в него ничего не дописывает. Предупреждения Gatekeeper и SmartScreen относятся к помощнику и описаны на `/help/install#helper`
на сайте, а не на странице мода.
- Отдельного правила про аудио у Modrinth **нет**. Работают общие: не нарушать авторские права и не
  перезаливать чужое. Практический вывод для нас: **в jar нет ни одной чужой песни**, а в описании прямо
  сказано, что музыку приносит игрок и он же за неё отвечает. Карты с песнями игроки загружают в Мастерскую
  по её правилам загрузки и под свою гарантию прав; правообладатель может добиться удаления карты по
  уведомлению (см. [`LICENSING.md`](LICENSING.md)).

## Отправка на ревью

Кнопка **Submit for review** разблокируется, только когда не осталось замечаний уровня required.
Дальше: draft → processing («Under review») → approved.

- Заявленный срок 24–48 часов, но там же предупреждают, что бывает и «upwards of two weeks».
- Пустая вкладка Moderation означает только очередь, а не потерю.
- Правки и новые версии в очереди позицию **не меняют**, а вот **удаление старых версий может
  отправить проект в конец очереди**. Ничего не удалять.
- Писать в поддержку бесполезно и может замедлить.
- Списка типовых причин отказа Modrinth не публикует. Чеклист выше и есть де-факто предпроверка.

## После одобрения

Ссылка «Modrinth» в `README.md` ведёт на https://modrinth.com/mod/blocky-music. Пока проект на модерации, там
«Project not found»; после одобрения она заработает сама, исходники для этого перевыкладывать не нужно. Главная ссылка
`README.md` и «Download Blocky Music» в шаблоне issue ведут на CurseForge.

1. Дождаться статуса approved.
2. Проверить, что страница открыта всем: `curl -s -o /dev/null -w '%{http_code}\n'
   https://api.modrinth.com/v2/project/blocky-music` — `200`, а https://modrinth.com/mod/blocky-music в окне
   без входа показывает мод, а не «Project not found».

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
[SIL Open Font License 1.1](https://openfontlicense.org/open-font-license-official-text/) ·
лицензия в исходниках Modrinth: [license.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/validate/project/license.rs),
[settings/license.vue](https://github.com/modrinth/code/blob/main/apps/frontend/src/pages/%5Btype%5D/%5Bproject%5D/settings/license.vue),
[search/indexing.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/search/indexing.rs) ·
исходники валидации: [validate/project](https://github.com/modrinth/code/tree/main/apps/labrinth/src/validate/project),
[neoforge.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/validate/neoforge.rs),
[version_creation.rs](https://github.com/modrinth/code/blob/main/apps/labrinth/src/routes/v3/version_creation.rs)
