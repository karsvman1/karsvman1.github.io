# Code review – 3 november 2026

## Toegepaste standaarden en hoe ze hier van toepassing zijn
| Standaard | Herkomst | Toepassing in dit project |
|---|---|---|
| **The Power of 10: Rules for Developing Safety-Critical Code** | G. J. Holzmann, NASA/JPL, 2006 | Geschreven voor C. Toegepast waar de regel vertaalt: (1) eenvoudige controleflow, geen recursie; (2) **elke lus een vaste bovengrens**; (3) geen onbegrensd geheugen: Java/Python gebruiken garbage collection, dus hier vertaald naar *begrensde buffers en lijsten* en geen allocatie per item in `onDraw`; (4) **functies van hoogstens ±60 regels**; (5) **invoercontroles die niet uitgeschakeld kunnen worden**; (6) kleinste scope; (7) **retourwaarden en fouten controleren**; (8) preprocessor: n.v.t.; (9) pointers: n.v.t.; (10) **compileren met alle waarschuwingen aan, zonder waarschuwingen**. |
| JPL Institutional Coding Standard (C) | NASA/JPL, 2009 | Dezelfde kern als hierboven. De C-specifieke regels (MISRA-subset) zijn niet toepasbaar op Java/Python/shell. |
| PEP 8 + flake8 | Python-community | `flake8 --max-line-length=110`: 0 meldingen. |
| javac `-Xlint:all -Werror` | Java | 0 waarschuwingen. |
| Bandit B101 | Python-beveiliging | Geen `assert` voor validatie. |
| Android-beveiliging: exported components | developer.android.com | Een exported component mag geen configuratie van willekeurige apps accepteren. |
| OWASP: geen geheimen in broncode | OWASP | Keystore-wachtwoord uit de code gehaald. |

Niet gebruikt omdat de tools hier ontbreken: Android Lint (vereist Gradle/AGP), ShellCheck en PSScriptAnalyzer. Daarvoor zijn de scripts gecontroleerd met `bash -n` en de PowerShell-parser, plus een handmatige review.

## Bevindingen (alle reproduceerbaar; ernst: K = kritiek, H = hoog, M = midden, L = laag)
| # | Ernst | Bestand | Bevinding (bewijs) | Fix | Verificatie |
|---|---|---|---|---|---|
| 1 | **K** | `AndroidManifest.xml`, `MainActivity` | De Home-activity is exported en nam `url`, `key` en `refresh_token` aan uit **elke** intent. Elke app op het apparaat kon de sync omleiden of de Google-login vervangen. | Nieuwe `ConfigReceiver`, beschermd met `WRITE_SECURE_SETTINGS` (`signature\|privileged\|development`; de shell heeft het, `granted=true` gecontroleerd op het apparaat). De activity negeert extras. | Op de reader: `am start … --ei rotation 270 --es url http://evil…` → genegeerd (schermafbeelding `review-extras-ignored.png`). Broadcast → `saved 1 setting(s)`. Het recht staat in het manifest van de APK (`aapt dump`). |
| 2 | **H** | `dashboard_server.py` | Het logfilter hing aan de root-*logger*; Python past dat niet toe op berichten van de logger `dashboard`. **Getest:** `refresh_token=abc123` verscheen onversleuteld. | Filter op de *handlers*. | Dezelfde test toont nu `[redacted …]`; unittest `RedactionTest`. |
| 3 | **H** | `make_adb_boot.py` | Firmwarecontroles waren `assert`. Met `python -O` verdwijnen die, en zou een verkeerd image toch gebouwd worden. | Expliciete `BootImageError`. Extra controles op paginagrootte, groottes, een al gepatchte invoer en de vorm van de sleutel. | Weigert al gepatchte invoer, ook onder `-O`. De herbouw is **byte-identiek aan de geflashte v4** (SHA-256 `2f038325…34cb`). |
| 4 | **H** | `DashboardView` | `morgen = vandaag + 24 uur`. Op 25-10 (25 uur) en 29-03 (23 uur) gaf dat een verkeerde dag voor "morgen", de groepering en taken. | `TimeUtil.addDays` met kalenderrekenkunde. | `TimeUtilTest`: dagen van 25 en 23 uur, `addDays` over de klokwissel. |
| 5 | M | `DataFetcher` | `getResponseCode()` werd aangeroepen ná `disconnect()`. Dat start een nieuwe HTTP-aanvraag. | De code wordt één keer opgeslagen. | Code-inspectie. Compileert. |
| 6 | M | `DataFetcher`/`MainActivity` | "Offline" volgde uit "≥ 3 foutmeldingen". Een verlopen login (2 meldingen) plus een weerstoring gaf daardoor ten onrechte "offline". | Alleen netwerkfouten tellen (`wasUnreachable`). | Code-inspectie. Op het apparaat zonder fouten getest. |
| 7 | M | `MainActivity` | Data race: `fetcher` werd op de UI-thread op `null` gezet en op de werkthread gebruikt. | Wordt aangemaakt op de UI-thread en als `final` doorgegeven; het veld is `volatile`. | Compileert. Sync op het apparaat werkt. |
| 8 | M | `DataFetcher`, `dashboard_server.py` | Pagineerlussen zonder bovengrens (`do/while`, `while True`). | `MAX_PAGES = 10`, `MAX_CALENDARS = 50`, takenlijsten ≤ 100. | `BoundedPaginationTest`: een nep-API met eindeloze pagina's stopt na 10. |
| 9 | M | `DataFetcher` | De grootte van een antwoord was onbegrensd. | `MAX_RESPONSE_BYTES = 2 MiB`, en de stream wordt gesloten in `finally`. | Code-inspectie. |
| 10 | M | `dashboard_server.py` | Het IP voor de keepalive werd gezet **vóór** de sleutelcontrole, dus elke host kon het ping-doel worden. Er was geen socket-timeout. | Na de sleutelcontrole. `Handler.timeout = 15`. | Code-inspectie. |
| 11 | M | `dashboard_server.py` | Een kapotte cache liet de server crashen bij het starten. Afspraken werden op tekst gesorteerd, wat rond de klokwissel fout gaat. | `try/except` rond de cache. Sorteren op `datetime`. | Code-inspectie. flake8. |
| 12 | M | `android/build.sh` | Keystore-wachtwoord `android` stond vast in de code. | Willekeurig wachtwoord in `tools/keystore.pass` (git-ignored). Het bestaande keystore is gemigreerd. | Hetzelfde certificaat (`2F:EF:E4…50:BC`). Het oude wachtwoord wordt geweigerd. Update op het apparaat werkte en behield de login. |
| 13 | L | `DashboardView` | `drawAgenda` was ±85 regels. Per tekenbeurt werden datums opnieuw geparsed en lijsten aangemaakt. | Gesplitst in `drawToday` en `drawUpcoming`. Parsen gebeurt één keer in `setData`. `MAX_EVENTS = 200`. | Compileert. Scherm op het apparaat ongewijzigd (`review-after-update.png`). |
| 14 | L | `DashboardView` | Een afspraak die gisteren begon, toonde de begintijd van gisteren. | Toont "eerder". | Code-inspectie. |
| 15 | L | `read_backup.sh` | Controleerde op `KRNL`, maar Gen 2 gebruikt `ANDROID!`. Dat gaf een valse waarschuwing. | Beide formaten herkend. | `bash -n`. |
| 16 | L | `watch_maskrom.ps1` | `$dev -ne $null` op een array geeft een array in plaats van een boolean. Het log werd UTF-16. | `@(...)` en `.Count -gt 0`. UTF-8 via `Add-Content`. | De PowerShell-parser geeft 0 fouten. |
| 17 | L | `flash_boot.sh` | Controleerde niet of het image een Android-bootimage is. | Controle op `ANDROID!`. | `bash -n`. |
| 18 | L | Java | `-Xlint:all`: `serialVersionUID` ontbrak. | Toegevoegd. | 0 waarschuwingen met `-Werror`. |
| 19 | L | Python | flake8: E731, E501, E114/E116, E306. | Opgelost. | flake8: 0. |

## Tests (nieuw)
- `android/test/run.sh`: **17 controles** op `TimeUtil` (verschuivingen, `Z`, fracties, ongeldige invoer, dagen van 25 en 23 uur, opmaak). Resultaat: OK.
- `py -3.10 -m unittest discover -s tests`: **13 tests**. Ze dekken cpio heen en terug, afgekapte invoer, invoer zonder trailer, de controles op de boot-header, het patchen van de echte stock-back-up (precies 3 bestanden gewijzigd, kernel en second identiek, grootte gelijk), dubbel patchen geweigerd, het logfilter en de begrensde paginering. Resultaat: OK.

## Eindtest op het apparaat (na alle wijzigingen)
- De update-installatie behield de opgeslagen Google-login. Sync om 16:04 zonder fouten.
- Herstart om 16:05:47, opgestart om 16:06:16. Het dashboard was bijgewerkt om 16:06, zonder "offline" of "deels oud". Het crash-log bevat geen regels van `nl.japandi.dashboard` (`review-after-reboot.png`).

## Bewuste afwijkingen
- **Power of 10, regel 3** (geen dynamische allocatie na initialisatie): niet letterlijk haalbaar in Java/Python. Vertaald naar begrensde buffers en lijsten en geen allocatie per item in `onDraw`. Strings voor tekst worden per tekenbeurt nog aangemaakt (één tekenbeurt per minuut).
- **Brede `except Exception`** in `build_dashboard` en `startSync`: bewust, voor foutisolatie. Eén defecte bron mag het scherm nooit leeg maken. De fout wordt per bron gemeld.
- **De Google-login staat onversleuteld** in de privé-SharedPreferences van de app. Andere apps en de ADB-shell kunnen die niet lezen (de app is niet `debuggable`). Wie fysiek toegang heeft via maskrom, kan wel de userdata lezen. Versleuteling met Android Keystore is mogelijk op API 23, maar niet gedaan. De scopes zijn alleen-lezen.
- **De laptopserver** gebruikt HTTP met een sleutel in de query op het LAN. Hij is alleen nog reserve (de taak is uitgeschakeld).

## Niet getest (eerlijk)
- Of een **gewone app zonder het recht** `ConfigReceiver` echt niet kan aanroepen. Het attribuut staat in het manifest en Android handhaaft dat standaard, maar het is niet dynamisch getest met een aparte app.
- Het offline-gedrag in de zelfstandige modus (wifi kan niet via ADB worden uitgezet).
- De fixes 5, 6, 9, 10, 11 en 14 alleen via code-inspectie en compilatie, niet met een gerichte test op het apparaat.

---

# Code review 2 – 3 november 2026 (na de retro-redesign 1.1.0)
Dezelfde standaarden als hierboven (Power of 10 aangepast, `-Xlint:all -Werror`, PEP 8/flake8, Bandit B101, Android exported components, OWASP geheimen). **Alleen nieuwe bevindingen.** De punten 1–19 van review 1 zijn niet opnieuw beoordeeld of gerapporteerd.

| # | Ernst | Bestand | Bevinding (bewijs) | Fix | Verificatie |
|---|---|---|---|---|---|
| 20 | **H** | `Paging`, `DashboardView` | Een kop "KLAAR VANDAAG" werd achteraf naar de volgende pagina verschoven. Die pagina kon daardoor meer bevatten dan past: bij 110 px was dat 30 + 50 + 50 = 130 px, en de onderste taak liep over de rand. | De regel zit nu in `pageStarts(…, keepWithNext)`: een kop begint alleen op een pagina waar ook de rij erna past. | `LogicTest`: "header starts page 2" en "every page fits". |
| 21 | M | `PixelFont` | Een emoji (surrogaatpaar, bijvoorbeeld 🎂) werd als 2 kapotte tekens getekend. De variatiekiezer U+FE0F gaf een extra leeg vakje. | Een surrogaatpaar is één eenheid. Tekens zonder breedte (FE0E/FE0F/200D/200B) worden overgeslagen. `fit` splitst nooit een paar. | `LogicTest`: "emoji is one unit", "emoji width", "fit never splits a pair". |
| 22 | M | `PixelFont` | `ellipsize` en `wrap` waren kwadratisch: per weggehaald teken werd de hele string opnieuw gemeten, met een lijst per aanroep. Elke tekenbeurt brak alle taken af (tot 500). | `measure` werkt zonder allocatie, met een begrensde breedtecache. `fit` is één lineaire doorloop. `ellipsize` en `wrap` gebruiken `fit` en zijn begrensd door `maxLines`. | `LogicTest`: een titel van 1920 tekens in < 200 ms, en "fit is the longest prefix". |
| 23 | M | `MainActivity` | `readAll` (cachebestand en antwoord van de laptopserver) had geen groottegrens. De stream werd niet gesloten bij een fout. | `MAX_READ_BYTES = 4 MiB`, met `close` in `finally`. | Compileert met `-Werror`. Cache-load op het apparaat werkt. |
| 24 | M | Firmware (boot v4) | De Onyx-updatedienst `com.onyx.android.onyxotaservice` stond aan (`enabled=0`, standaard), met een receiver `check_update`. Een OTA-update kan de bootpartitie vervangen, waarna ADB en het dashboard stilletjes weg zijn. | Boot **v5**: dezelfde patch plus `pm disable com.onyx.android.onyxotaservice` (uitgeschakeld, niet verwijderd). | Alleen `init.rc` verschilt van v4: 3 regels erbij, kernel en second byte-gelijk. Geflasht met read-back identiek. Na het opstarten `enabled=2` en het dashboard actief. |
| 25 | L | `Birthdays` | Woorden werden zonder woordgrenzen verwijderd: "Tom Jarigsma" werd "Tom sma", "Verjaardagsfeest Tom" werd "sfeest Tom". | `\b` rond alle woorden en jaartallen. | `LogicTest`: "no partial word removal", "compound kept". |
| 26 | L | `TimeUtil` | `Calendar` was lenient: "2026-02-30" werd 2 maart, uur 25 werd de volgende dag. | `setLenient(false)`. `IllegalArgumentException` geeft 0. | `TimeUtilTest`: 30 februari, maand 13, uur 25 en schrikkeldag. |
| 27 | L | `DataFetcher` | De ontdubbeling op taak-id liet bij een ontbrekend id (lege string) alle taken op één na weg. | Alleen ontdubbelen bij een niet-leeg id. | Code-inspectie. |
| 28 | L | `.gitignore` | `tools/` werd volledig genegeerd, inclusief de eigen scripts. De keystore en `config.json` (e-mailadres, IP van de reader) waren niet uitgesloten. | Fijnmazige regels: geheimen, persoonlijke data, apparaatdumps en tools van derden zijn uitgesloten; eigen scripts worden meegenomen. | `git add --dry-run`: 39 bestanden, zonder geheimen of e-mailadres (gecontroleerd met grep). |

**Bewust ongewijzigd:** allocaties per tekenbeurt voor tekst (één beurt per minuut; deviatie op Power of 10 regel 3, zoals in review 1), `MAX_TASKS = 500` in de view, en de brede `catch` in `loadCache` en `startSync`.

**Tests na review 2:** 21 TimeUtil-controles en 73 logica-controles. `javac -Xlint:all -Werror`: 0 waarschuwingen. 13 Python-tests. flake8: 0 meldingen (de gate hield de release eerst tegen op E501; dat is hersteld).

**Op het apparaat (19:00):** firmware v5 en app 1.1.1. Afspraken, het weer, **echte verjaardag** ("Test", vandaag, "wordt 27") en **echte afgevinkte taken** (doorgestreept, met pagina 2/2) uit Google. 0 crashregels.
