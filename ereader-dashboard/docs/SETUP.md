# E-ink dashboard – installatie

> **Huidige opzet (sinds 3 nov 15:30): de reader werkt zelfstandig.** De app haalt Google Agenda, Google Taken en het weer zelf op. De laptop is alleen nodig om de Google-login te vernieuwen.
>
> **Google-login vernieuwen** (staat er "Google-login verlopen" onderaan het scherm, wat elke 7 dagen gebeurt zolang de Google-app op "Testing" staat):
> 1. Sluit de reader met de kabel aan op de laptop.
> 2. Run `bash tools/renew_google_login.sh` (of vraag Claude dat te doen).
> 3. Log in de browser in en geef toestemming. Het script stuurt de nieuwe toegang via de kabel naar de reader.
>
> De laptopserver hieronder is alleen nog een reserve. De Taakplanner-taak "Ereader dashboard server" staat uitgeschakeld.

Twee onderdelen:

- **`android/`**: de dashboard-app die *op de Storytel Reader zelf* draait (Java, minSdk 17, zonder libraries).
- **`backend/`**: een kleine Python-server op je pc of Raspberry Pi. Die bewaart de Google-tokens, haalt Agenda, Taken en weer op en levert één JSON-bestand aan de reader. Tokens komen nooit op de reader.

## 1. Backend

Vereist: Python 3.10 (aanwezig) met `google-auth`, `google-auth-oauthlib`, `google-api-python-client` en `requests` (geïnstalleerd).

```bash
cd backend
cp config.example.json config.json
py -3.10 dashboard_server.py serve --demo     # demo-agenda + echt weer, zonder Google
```
Normaal start de server vanzelf bij het aanmelden op Windows (Taakplanner-taak **"Ereader dashboard server"**, zonder venster, log in `backend/logs/server.log`). Handmatig starten kan met de dubbelklik op `backend/start_server.cmd` (met venster) of `backend/start_server_hidden.vbs` (zonder venster). Uitzetten doe je in Taakplanner door de taak uit te schakelen. Bij het starten toont de server het adres voor de reader (bijv. `http://<laptop-ip>:8765/dashboard.json`). De server ververst Agenda, Taken en weer elke 10 minuten. De reader haalt het resultaat elke 10 minuten op, of na 2 minuten opnieuw als het mislukt.

De eerste keer vraagt Windows Firewall of Python netwerktoegang mag. Kies **alleen "Privénetwerken"**. Die keuze maak jij zelf.

Laptop dicht of in slaap: dan blijft de reader de laatst opgehaalde gegevens tonen, met "offline" onderaan.

### Google Cloud (eenmalig, door jou zelf)
1. Open console.cloud.google.com, maak een project en zet **Google Calendar API** en **Google Tasks API** aan.
2. Stel het OAuth-toestemmingsscherm in op type "External" en voeg jezelf toe als testgebruiker.
3. Maak onder Credentials een OAuth-client van het type **Desktop app**. Download de JSON naar `backend/secrets/google_client_secret.json`.
4. Run `py -3.10 dashboard_server.py auth`. Er opent een browser; geef toestemming. De app vraagt alleen `calendar.readonly` en `tasks.readonly`.
5. Run `py -3.10 dashboard_server.py serve`.

Opmerking: zolang de app in de status "Testing" staat, laat Google refresh-tokens na 7 dagen verlopen. Dan staat er "deels oud" op het scherm en run je stap 4 opnieuw. Wil je dit permanent: zet het project op "In production" (voor eigen gebruik is geen verificatie nodig, wel een waarschuwingsscherm).

Waarom niet inloggen op de reader zelf? Google's device-flow ondersteunt Calendar- en Tasks-scopes niet (zie de lijst "allowed scopes" op developers.google.com/identity/protocols/oauth2/limited-input-device). Daarnaast is Android 4.2/6 met root geen veilige plek voor tokens.

De sleutel voor de reader staat in `backend/secrets/device_key.txt`. Die wordt bij de eerste `serve` aangemaakt.

## 2. App bouwen

```bash
bash android/build.sh        # -> android/build/dashboard.apk
```
Gebruikt de JDK 17 en Android SDK (platform 23, build-tools 30.0.3) in `tools/`.

## 3. Installeren op de reader (vereist ADB, zie STATUS.md)

```bash
tools/platform-tools/adb.exe install -r android/build/dashboard.apk
bash tools/renew_google_login.sh
```
Configuratie gaat **alleen** via een broadcast naar `ConfigReceiver`. Alleen de ADB-shell mag die sturen (recht `WRITE_SECURE_SETTINGS`); extras bij `am start` worden genegeerd. Voorbeeld voor de draairichting (`90` of `270`, afhankelijk van naar welke kant de reader ligt):

```bash
tools/platform-tools/adb.exe shell am broadcast -a nl.japandi.dashboard.CONFIGURE -n nl.japandi.dashboard/.ConfigReceiver --ei rotation 90
```
Het dashboard is het enige startscherm, omdat de boot-patch `com.storytel.bootstrap` uitschakelt (zie STATUS.md).

## Tests
```bash
bash android/test/run.sh
py -3.10 -m unittest discover -s tests
```
