# E-ink dashboard voor de Storytel Reader 2

Een eigen Android-app (zonder Gradle gebouwd) die de Storytel Reader 2 (Onyx, Rockchip RK312x, Android 6.0.1) verandert in een vast huisdashboard in retro-handheldstijl. Het dashboard toont:
- de klok en datum;
- het weer in Den Bosch (Open-Meteo);
- de Google Agenda van vandaag;
- Google Taken;
- verjaardagen uit de agenda "Verjaardagen".

De app haalt de gegevens zelfstandig op via wifi; Google wordt alleen gelezen. Hij start automatisch op als Home-scherm.

- `android/`: de app (`build.sh`, tests in `android/test/run.sh`).
- `tools/`: release (`release.sh`), de boot-patch (`make_adb_boot.py`) en het flashen via maskrom (`flash_boot.sh`).
- `backend/`: de optionele laptopserver (reserve).
- `docs/`: installatie (`SETUP.md`), status, ontwerp (`REDESIGN-1.1.md`), code reviews en releases.

**Let op:** het flashen van de bootpartitie gebeurt op eigen risico. Maak altijd eerst een back-up (`tools/read_backup.sh`). Firmware, geheimen, apparaatdumps en tools van derden staan bewust niet in deze repository (zie `.gitignore`).
