# Retro-redesign 1.1.0 – 3 november 2026

## Wat het apparaat kan (gemeten via ADB, niet aangenomen)
- **Aanraakscherm:** `fts_ts` (multitouch, `INPUT_PROP_DIRECT`), herkend als `touchScreen` in `dumpsys input`.
- **Knoppen:** de keypad-layout `rk29-keypad.kl` definieert PAGE_UP, PAGE_DOWN, BACK, MENU, HOME en volume. De driver meldt de codes 104, 109, 143, 144 en 158. **Niet geverifieerd:** of er fysieke pagina-knoppen op de behuizing zitten. De app vangt ze af als ze bestaan.
- **Scherm:** 758 x 1024 px, 240 dpi, grijstinten. Er staat geen pixel-lettertype op het systeem (`/system/fonts`), daarom is er een eigen lettertype getekend.

## Ontwerp
Vier tinten (zwart, donkergrijs, lichtgrijs, wit). Vensters hebben een dubbele rand met afgeknipte hoeken en zwarte titeltabs, in de stijl van een retro handheld-menu. Een eigen proportioneel 5x7-pixel-lettertype (`PixelFont`) met accenten via Unicode-decompositie. Pixel-sprites (`Sprites`) voor het weer (zon en maan, half bewolkt, bewolkt, mist, motregen, regen, sneeuw, onweer), een taart, een ster, selectievakjes en een status-LED. Alles is zelf ontworpen; er zijn geen assets of handelsmerken van derden gebruikt.

- **Links:** de klok in groot pixelschrift met een schaduw, daaronder de dag en datum (Nederlands). Dan het weer voor Den Bosch: icoon, temperatuur, omschrijving, max/min/regen en 3 dagen vooruit. Daaronder het kleine verjaardagsvenster en de status-LED met "bijgewerkt hh:mm".
- **Rechts:** **VANDAAG** (alleen de afspraken van vandaag; de lopende afspraak staat omgekeerd, als geselecteerd menu-item; voorbije afspraken in grijs) en **TAKEN**. De vensterhoogtes passen zich aan de inhoud aan.

## Takenlijst
- Geen limiet meer (was 4). Volgorde: te laat → vandaag → zonder datum → later → **KLAAR VANDAAG** (doorgestreept, met een afgevinkt vakje).
- Lange titels lopen over op maximaal 2 regels, daarna "...".
- Labels: "TE LAAT" (omgekeerd), "VANDAAG" (omrand), "MORGEN" en een datum.
- Er wordt alleen gelezen (scope `tasks.readonly`). Afvinken vanaf de reader zou schrijfrechten vereisen en is bewust niet gebouwd.

## Bladeren (agenda en taken)
`Paging` verdeelt de rijen over pagina's; er valt nooit een rij weg. Rechtsboven in het venster staat bijvoorbeeld "2/4 TIK".
- **Tik** op een venster: volgende pagina. **Tik ernaast:** terug naar pagina 1.
- **PAGE- of volumetoetsen:** de takenlijst bladert vooruit of terug.
- **Zonder bediening:** elke 2 minuten gaat een venster dat niet past één pagina verder, tegelijk met de klokupdate (dus zonder extra verversingen). Na een tik blijft de gekozen pagina 3 minuten staan.

## Verjaardagen
Bron: een Google-agenda die precies **"Verjaardagen"** heet. Die agenda wordt ook gelezen als hij in Google Agenda verborgen is, en hij staat niet in het agenda-venster.
- **Wat telt als verjaardag:** een hele-dag-afspraak die jaarlijks terugkeert (`RRULE:FREQ=YEARLY`), of een afspraak met `eventType` "birthday". Al het andere in die agenda wordt genegeerd.
- **Naam:** de titel, zonder het jaartal en woorden als "verjaardag (van)" of "jarig".
- **Leeftijd:** alleen als het geboortejaar er expliciet staat: "Anna (1990)" of "Anna *1990" in de titel, of "geboren 1990" in de beschrijving. Anders wordt er geen leeftijd getoond.
- **Weergave:** vandaag omgekeerd met "VANDAAG!" en de titeltab "JARIG VANDAAG!"; daarna de volgende verjaardagen met "MORGEN" of "OVER n D".
- **Bereik:** 366 dagen vooruit. Jaarwisselingen en verplaatste exemplaren worden door Google uitgerekend.
- **Status op 3-11-2026:** de agenda bestaat **nog niet** in je account (gecontroleerd via de calendarList: alleen de hoofdagenda, "Gedeelde agenda" en "Feestdagen in Nederland"). Het venster toont daarom de uitleg. De app maakt die agenda **niet** zelf aan.

### Verjaardagen instellen (jij, eenmalig)
1. Ga naar calendar.google.com, klik naast "Andere agenda's" op **+**, kies **Nieuwe agenda** en noem hem `Verjaardagen`.
2. Per persoon: maak een nieuwe afspraak op de verjaardag. Vink **Hele dag** aan en zet Herhalen op **Jaarlijks**. Kies als agenda **Verjaardagen**. Titel: `Anna` of `Anna (1990)`.
3. Binnen 10 minuten (of na een tik op het scherm en de volgende sync) verschijnen ze op de reader. Er is geen nieuwe login nodig: de app heeft al leesrechten op al je agenda's.

## Tests
- `android/test/run.sh`: 17 TimeUtil-controles en **61 nieuwe controles** (`LogicTest`) op verjaardagsregels (inclusief jaarwisseling en zomer/wintertijd), taakvolgorde, pagina-indeling (geen rij kwijt, koppen niet los) en het lettertype (meten, afkappen, regelafbreking, accenten, terugval). Alles OK.
- `javac -Xlint:all -Werror`: 0 waarschuwingen. 13 Python-tests en flake8: OK.
- **Op het apparaat, met echte gegevens:** 5 afspraken van vandaag; de lopende afspraak (18:00) staat omgekeerd; 6 open taken waarvan 2 over twee regels; weer met maan-icoon ('s avonds); de uitleg voor de ontbrekende verjaardagsagenda. Herstart om 18:14:25, terug om 18:15:39, bijgewerkt om 18:15, 0 crashregels (`docs/device/gameboy-1.1.0.png`).
- **Op het apparaat, met testdata** (tijdelijke aparte app `nl.japandi.dashboard.preview`, daarna verwijderd): 13 afspraken vandaag en 1 morgen (die morgen werd terecht niet getoond), 25 taken, 3 verjaardagen. Getest: een tik op taken gaf 1/4 → 2/4, een tik op de agenda gaf 1/3 → 2/3, PAGE_DOWN twee keer gaf 4/4, PAGE_UP liep rond naar 4/4. Een losse kop "KLAAR VANDAAG" onderaan een pagina is gevonden en hersteld.

## Niet geverifieerd
- **Tikken:** getest met `adb shell input tap`. Dat gaat via dezelfde Android-invoerweg, maar niet via een echte vinger op het glas.
- **Fysieke knoppen:** of die er zijn, is onbekend.
- ~~Echte verjaardagen uit Google~~: om 19:00 geverifieerd met de echte agenda "Verjaardagen" (zie code review 2).
- ~~Afgevinkte taken van vandaag~~: om 19:00 geverifieerd (doorgestreept getoond).
- **Ghosting en weergave:** het gedrag van de grijstinten en de verversing per paginawissel op dit e-ink-paneel over langere tijd.
- **Laptopserver (reserve):** levert nog geen verjaardagen of afgevinkte taken. Het scherm toont dan "nog niet geladen".
