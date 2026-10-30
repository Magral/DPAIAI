# Источники стратегий DPI Pilot TV 0.4.1

Исследование: 31 октября 2026 года. 279 уникальных вариантов, включая первые восемь и подстановки fake-SNI. Это объём каталога, а не число подтверждённых обходов у провайдеров.

## Принятые источники

| Источник | Присутствует в вариантах | Доступ и ограничения |
|---|---:|---|
| [ByeByeDPI built-in list](https://github.com/romanvht/ByeByeDPI/blob/master/app/src/main/assets/proxytest_strategies.list) | 128 | Снимок опубликованных команд; эффективность на сети пользователя не подтверждена. |
| [ByeByeDPI-x-tg built-in list](https://github.com/EwenLoy/ByeByeDPI-x-tg/blob/main/app/src/main/assets/proxytest_strategies.list) | 128 | Снимок опубликованных команд; эффективность на сети пользователя не подтверждена. |
| [NetFix author strategies (discovered via Habr)](https://github.com/rupleide/NetFixMobile/blob/main/app/src/main/java/com/rupleide/netfix/core/dpibypass/StrategyTester.kt) | 145 | Снимок опубликованных команд; эффективность на сети пользователя не подтверждена. |
| [Byedpi-Setup settings](https://github.com/fatyzzz/Byedpi-Setup/blob/main/assets/settings.txt) | 39 | Снимок опубликованных команд; эффективность на сети пользователя не подтверждена. |
| [4PDA ByeByeDPI page 25](https://4pda.to/forum/index.php?showtopic=1099318&st=480) | 2 | Public author posts; runtime effectiveness unverified. |
| [4PDA ByeByeDPI page 151](https://4pda.to/forum/index.php?showtopic=1099318&st=3000) | 1 | Public author posts; runtime effectiveness unverified. |
| [4PDA ByeDPI page 69](https://4pda.to/forum/index.php?showtopic=1092092&st=1360) | 1 | Public author posts; runtime effectiveness unverified. |
| [4PDA ByeByeDPI page 108](https://4pda.to/forum/index.php?showtopic=1099318&st=2140) | 1 | Public author posts; runtime effectiveness unverified. |
| [4PDA ByeByeDPI page 153](https://4pda.to/forum/index.php?showtopic=1099318&st=3040) | 2 | Public author posts; runtime effectiveness unverified. |
| [Webhamster published command collection](https://webhamster.ru/mytetrashare/index/mtb0/1750407487732ubp4o9x) | 29 | Public author posts; runtime effectiveness unverified. |
| [Public Telegram instructions](https://t.me/s/AL_bloger?q=%23ByeDpi&before=474) | 1 | Public author posts; runtime effectiveness unverified. |
| [Independent configuration article](https://byebyedpi.org/config/) | 4 | Site authorship/official status not verified; commands only, performance claims not adopted. |
| [NTC author post (indexed excerpt)](https://ntc.rkn.quest/t/byedpi-%D0%BE%D0%B1%D1%81%D1%83%D0%B6%D0%B4%D0%B5%D0%BD%D0%B8%D0%B5/9057?page=23) | 1 | Exact command from sk9d3 post #452 in search excerpt; full forum fetch blocked. |
| [ByeDPI 17.3 author documentation](https://github.com/hufrea/byedpi/blob/ba532298de7b28cfe854aea83d061369d13ca290/README.md) | 4 | Снимок опубликованных команд; эффективность на сети пользователя не подтверждена. |

Количество в строках не суммируется: один вариант может происходить из нескольких источников. Шаблоны `{sni}` развёрнуты в пять доменов из списка NetFix: google.com, youtube.com, googlevideo.com, ytimg.com, ggpht.com. Они не считаются независимыми пользовательскими свидетельствами.

## Прочитано вне GitHub

- [Статья автора NetFix на Habr](https://habr.com/ru/articles/1094866/) привела к его первичной базе; сама статья не дала отдельного большого списка команд.
- [Инструкция ByeByeDPI на Telegraph](https://telegra.ph/ByeByeDPI-quick-start-05-08) описывает настройку и ведёт к мануалу, без новых команд.
- [Статья Habr об OpenWRT](https://habr.com/ru/articles/856312/) прочитана, новых пригодных строк в каталог не добавила.
- Публичные сообщения 4PDA, Telegram и коллекция Webhamster дали дополнительные команды; ссылки выше.
- Основной домен NTC не открылся через инструмент чтения, зеркало вернуло защитную страницу. Использована только одна точная команда из индексированной выдержки авторского сообщения, это явно отмечено в данных.
- Поиск также охватил Reddit, видеоинструкции, GoodCheck и Let Me In. Доступного нового полного списка с проверяемым происхождением из этих результатов не получено; объём их баз не приписывается нашему каталогу.

## Что исключено

Отклонены 26 записи: повреждённый текст, шаблонные разделители/примеры, чужие флаги GoodbyeDPI/NFQWS, ограничение по внешним файлам или параметры вне разрешённого набора. Полные причины — в `tv/strategies/catalog.json`. Команды не передаются оболочке. Внешние установщики, APK и скрипты из найденных публикаций не запускались.

Сайт byebyedpi.org проверен как независимая публикация; заявленный им официальный статус и рекламные обещания не приняты за факты. В каталог взяты только четыре командные строки, прошедшие локальную проверку.

## Воспроизведение

`python3 tv/strategies/build_catalog.py` строит каталог из сохранённых строк; `--check` проверяет генерируемый Kotlin и запуск каждого варианта. Сеть для локальных SOCKS-тестов не нужна. Версия движка закреплена подмодулем; исходная MIT-лицензия сохранена.

Исследование охватывает найденные доступные источники и не является исчерпывающим просмотром всего интернета. Опубликованная команда не считается рабочей до проверки на целевой сети и сервисе.
