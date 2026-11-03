# Status – Storytel Reader Gen 2 dashboard (3 november 2026)

## Update 19:00 – app 1.1.1 + firmware boot v5 (OTA-updater uit), code review 2
Zie `docs/CODE_REVIEW.md` (bevindingen 20–28) en `docs/RELEASES.md`.

## Update 18:15 – versie 1.1.0, retro-redesign
Nieuw scherm met pixel-lettertype, alleen de agenda van vandaag, taken zonder limiet (bladeren met tikken of de knoppen) en een verjaardagsvenster. Draait op de reader en is na een herstart geverifieerd. Zie `docs/REDESIGN-1.1.md`. De agenda "Verjaardagen" is inmiddels aangemaakt en wordt gelezen (19:00).

## Update 15:50 – Google-app "In production"
- Homepage en privacybeleid staan op GitHub Pages: `https://karsvman1.github.io/` en `/privacy.html` (beide 200). De bron staat in `docs/github-pages/`.
- De gebruiker heeft de OAuth-app gepubliceerd ("In production", niet geverifieerd, alleen voor eigen gebruik). De login is daarna vernieuwd (15:50) en via USB naar de reader gestuurd. Sync om 15:50 zonder fouten.
- Verwachting volgens de Google-documentatie: refresh-tokens van een app in productie verlopen niet na 7 dagen. **Nog niet te bewijzen**: dat blijkt pas na 10 november 2026.

## Update 15:31 – zelfstandig, zonder laptop
- De app haalt Google Agenda en Taken (alleen-lezen, via `DataFetcher.java`) en het Open-Meteo-weer **zelf** op. De laptop is niet meer nodig.
- HTTPS op Android 6 getest op het apparaat (`analysis/tlstest`): Google werkt met de systeemcertificaten. Open-Meteo faalde ("Trust anchor not found"), omdat de keten eindigt bij ISRG Root X1, die Android 6 niet kent. Opgelost door ISRG Root X1 en X2 mee te leveren in `res/raw` (vingerafdrukken gecontroleerd: X1 `96:BC:EC…08:C6`, X2 `69:72:9B…14:70`), naast de systeemcertificaten en met volledige ketencontrole.
- De Google-toegang (client-id, client-secret en refresh-token) staat in de privé-opslag van de app op de reader. Hij is via USB overgezet (`tools/renew_google_login.sh`). Scopes: alleen `calendar.readonly` en `tasks.readonly`.
- **Getest met de laptopserver uit** (poort 8765 dicht, Taakplanner-taak uitgeschakeld): sync om 15:29 zonder fouten. Na een herstart bijgewerkt binnen een minuut (15:30).
- De Taakplanner-taak is **uitgeschakeld, niet verwijderd**, zodat je terug kunt naar de laptopserver.
- Niet apart getest in deze modus: offline-gedrag. De code is dezelfde als eerder getest (opgeslagen gegevens plus "offline"), maar wifi uitzetten kan ik via ADB niet.

## Samenvatting
Het dashboard **draait op de reader zelf** als Android-app en is het enige startscherm. Het start automatisch na een herstart, haalt via wifi gegevens van de server op de laptop en toont klok, datum en weer (live). Agenda en Taken zijn gebouwd, maar wachten op de Google-koppeling, die jij moet doen (zie SETUP.md).

## Bewezen op het apparaat (via ADB, met schermafbeeldingen in `docs/device/`)
| Wat | Resultaat |
|---|---|
| Hardware | `Storytel Reader 2` (codenaam `salamander`), RK312x, Android **6.0.1** (SDK 23), build `2025-03-05_09-31_rel_e1be5a3`, scherm **758×1024** @ 240 dpi, 1 GB RAM, 4,9 GB vrij |
| Fabrikant | Onyx (Boox): `android.onyx.*`-framework, Onyx-instellingen en -OTA |
| Waarom Windows de reader niet zag | Stock `default.prop`: `persist.sys.usb.config=none` |
| ADB via USB | Werkt, alleen met de sleutel van deze laptop (`/adb_keys`). Authenticatie blijft aan. |
| Dashboard als startscherm | `com.storytel.bootstrap` uitgeschakeld (`enabled=2`), niet verwijderd |
| Autostart na herstart | Getest (3×): het dashboard verschijnt binnen ±30 s na het opstarten |
| Klok | Ververst elke minuut (12:10 → 12:11, 13:20 → 13:25) |
| Weer | Live van Open-Meteo, via de laptopserver |
| Offline | Getest: de laatste gegevens blijven staan, met "offline" onderaan, en het dashboard herstelt vanzelf |
| Wifi | Het dashboard zet wifi zelf aan (Storytels launcher deed dat eerder) en houdt een WifiLock vast |
| Auto-uitschakeling | Oorzaak gevonden: Onyx `auto_poweroff_timeout=900000` (15 min). Gezet op 2147483647; Onyx bevestigt dat in het log. Ook `screen_off_timeout` is verhoogd, `wifi_sleep_policy=2` (nooit) en `stay_on_while_plugged_in=7`. **Duurtest geslaagd:** 24 minuten uptime zonder uitschakelen, de klok op 13:47 en het weer automatisch ververst om 13:39 (`docs/device/screen-longrun.png`). |
| Flashmodus zonder knop | `adb reboot bootloader` → rockusb `2207:310C` |

## Firmwarewijziging (alleen de boot-ramdisk)
- Back-up van de originele boot: `backup/20261103-113527/boot.img` (SHA-256 `08009ede…bf06b`). **Bewaar een kopie buiten deze sessiemap.**
- Huidige boot = v4, gebouwd met `tools/make_adb_boot.py`. De kernel en de resource (device tree) zijn byte-voor-byte gelijk aan stock. Na elke flash is de partitie teruggelezen en was die identiek.
- Wijzigingen: `default.prop` (USB `mtp,adb`), het blok `on property:sys.boot_completed=1` in `init.rc` (adb_enabled=1, ADB ook op TCP 5555, `pm disable com.storytel.bootstrap`, Home openen) en `/adb_keys`.
- De bootloader controleert de header-ID niet (bewezen: alle 4 gepatchte images startten op).
- Herstel: `adb reboot bootloader` en daarna `bash tools/flash_boot.sh backup/20261103-113527/boot.img`. Start Android niet meer op, gebruik dan maskrom (oranje knop + kabel).
- Geleerd: `/system/bin/pm` en `settings` hebben geen `#!`, dus `init` moet ze via `/system/bin/sh` starten (v3 → v4).

## Nog open
1. ~~Google Agenda en Taken~~ **Werkt (14:59)**: 23 afspraken op de reader, met de lopende afspraak gemarkeerd en de komende dagen gegroepeerd. Taken: de API werkt, maar er staan op dit moment 0 open taken, dus het scherm toont "Niets open." (`docs/device/screen-tasks.png`). Let op: de OAuth-app staat in "Testing", dus Google laat het token na 7 dagen verlopen. Daarna moet `auth` opnieuw.
2. **TimeTree**: de officiële API is beëindigd op 22-12-2023. Er is geen officiële koppeling; dit vraagt een keuze van jou.
3. ~~Server-autostart~~ **Gedaan (15:05)**: het project staat in `Documents\ereaderdashboard` en de bestanden zijn met hashes gecontroleerd. De Taakplanner-taak "Ereader dashboard server" start `backend/start_server_hidden.vbs` bij het aanmelden (zonder venster, log in `backend/logs/server.log`). Getest via `Start-ScheduledTask`. Er komt ook een keepalive-ping naar de reader bij (`reader_ip` in config.json), omdat de reader na een herstart soms "No route to host" gaf door ARP. **Getest:** na een herstart van de reader is die 36 s na het opstarten online.
4. **E-ink**: de flash per uur tegen ghosting is nog niet bekeken op het paneel zelf.
5. **Storytel/Onyx-OTA** (`com.onyx.android.onyxotaservice`) kan een update installeren die de boot overschrijft. Nog niet uitgeschakeld.
