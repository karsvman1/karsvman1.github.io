# Releases

## 1.1.1 (versionCode 4) + firmware boot v5 – 3 november 2026
- **App:** de bevindingen 20–23 en 25–27 uit code review 2 (`docs/CODE_REVIEW.md`). APK SHA-256 `8706f790be71f0faa76d1db829c83982f6c39ff2e90c0a36bfd518b9405e4092`, hetzelfde certificaat.
- **Firmware boot v5** (SHA-256 `b686cb3b6e518e91f1fa4f9c745ef1041982171afee2c688895cc5abe3c807a9`): v4 plus het uitschakelen van de Onyx-updatedienst (bevinding 24). Geflasht via maskrom met een identieke read-back. Herstellen naar v4: `bash tools/flash_boot.sh build/boot-adb.img`; naar de originele firmware: met de stock-back-up.
- **Na het opstarten:** OTA `enabled=2`, `com.storytel.bootstrap` nog steeds uitgeschakeld, ADB werkt, het dashboard 1.1.1 is actief, 0 crashregels.


## 1.1.0 (versionCode 3) – 3 november 2026 – retro-redesign
Geïnstalleerd met `bash tools/release.sh`. APK SHA-256 `75dadda4d90c324a836024c5cc8bffc75f8f0647302b8a3359c41edd9ac60417`, hetzelfde certificaat (`2F:EF:E4…50:BC`). De login en instellingen zijn bewaard gebleven.

Volledig nieuw scherm in retro-handheldstijl met een eigen pixel-lettertype en sprites. Alleen de agenda van vandaag; geen limiet op het aantal taken (met bladeren); afgevinkte taken van vandaag; een verjaardagsvenster vanuit de agenda "Verjaardagen". Details, tests en wat nog niet geverifieerd is: `docs/REDESIGN-1.1.md`.

Poorten: 17 + 61 Java-controles, 0 waarschuwingen, 13 Python-tests, flake8 0, `bash -n`. Na de herstart: automatisch gestart, bijgewerkt om 18:15, 0 crashregels. Firmware ongewijzigd.


## 1.0.0 (versionCode 2) – 3 november 2026 – productie
Geïnstalleerd op de Storytel Reader 2 met `bash tools/release.sh`.

APK SHA-256: `acc47475c4aca7fec748bd986521bd81bbe55e521f57bbaf87b5fecb99b8d3b9`
Ondertekend met certificaat SHA-256 `2F:EF:E4:2B:…:1D:50:BC`, hetzelfde als alle eerdere builds. Daardoor blijven de opgeslagen Google-login en instellingen behouden.

**Release-poorten (alle geslaagd):**
1. `TimeUtilTest`: 17 controles. `javac -Xlint:all -Werror`: 0 waarschuwingen.
2. Python-unittests: 13. flake8: 0 meldingen.
3. `bash -n` op alle scripts.
4. Build en controle van het signeercertificaat.
5. Installatie via USB. Geïnstalleerde `versionCode` = 2 gecontroleerd op het apparaat.

**Verificatie op het apparaat na installatie:** herstart om 16:21:46, opgestart om 16:22:15. `versionName=1.0.0` actief als Home-scherm. Bijgewerkt om 16:22 zonder fouten. 0 regels in het crash-log (`docs/device/release-1.0.0.png`).

**Inhoud** (zie `docs/CODE_REVIEW.md` voor details en bewijs):
- Configuratie alleen nog via `ConfigReceiver`, beschermd met `WRITE_SECURE_SETTINGS`. De Home-activity negeert extras.
- DST-correcte dagberekening (`TimeUtil`), getest op dagen van 23 en 25 uur.
- Begrensde lussen, lijsten en responsgroottes. Geen dubbele HTTP-aanvraag meer bij een weerfout.
- "Offline" alleen bij netwerkfouten, niet bij een verlopen Google-login.
- Geen data race op de fetcher.
- Keystore-wachtwoord niet meer in de broncode.

**Firmware:** ongewijzigd (boot v4). `make_adb_boot.py` maakt na de review een byte-identiek image (`2f038325…34cb`), dus opnieuw flashen is niet nodig.

## 0.1 (versionCode 1) – 3 november 2026
Eerste versie op het apparaat: zelfstandig ophalen van Google Agenda, Google Taken en Open-Meteo, liggende Japandi-layout, autostart als Home.
