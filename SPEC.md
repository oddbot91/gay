# Jobbdialer – specifikation och arkitektur (v1.0)

Enkel arbetsdialer för Android. Svensk UI. Inga onödiga funktioner.
Spec-ägare: Fable (orkestrerare). Implementation: subagenter. Alla avvikelser från spec ska motiveras i rapporten.

## 1. Krav (prioriterade)

### Måste (P0)
1. **Tre ringlägen** från en knappsats:
   - **Ring internt**: ringer `<prefixInternt> + <slaget nummer>`. Default prefix `03134`. Ex: `30771` → `0313430771`.
     Om inmatningen redan börjar med prefixet läggs det inte till igen.
   - **Ring patient**: ringer `<prefixPatient> + <slaget nummer>`. Default prefix `#31#` (dolt nummer). Ex: `0701234567` → `#31#0701234567`.
   - **Ring växeln**: ringer `<växelnummer>` utan prefix. Default `0313427000`.
   - Alla tre värden redigerbara i Inställningar (default enligt ovan, "Återställ"-knapp).
2. **Samtal placeras direkt** med `Intent.ACTION_CALL` + `Uri.fromParts("tel", nummer, null)` (URL-kodar `#` → `%23`, krävs för att `#31#` ska fungera).
   Kräver runtime-behörighet `CALL_PHONE`; begär vid första ring, ring direkt när den beviljas.
   Fallback om behörighet nekas/SecurityException/ActivityNotFoundException: `ACTION_DIAL` med samma URI (öppnar systemets dialer med numret ifyllt) + kort toast.
3. **Telefonbok** med fälten: förnamn, efternamn, jobbnr (kortnummer), privatnr. Lägg till / redigera / ta bort.
   - Rad visar: `Förnamn Efternamn` (fet), under det `Jobb 30771` och `Privat 070-444 44 44` (rad döljs om fältet är tomt).
   - **Ett tryck på "Jobb …"** ringer internt (prefix). **Ett tryck på "Privat …"** ringer privatnumret som det är (utan prefix).
   - Långtryck på raden → meny: `Ring privat med dolt nummer (#31#)`, `Redigera`, `Ta bort`.
   - Sortering väljbar: Efternamn (default), Förnamn, Jobbnr, Privatnr. Valet sparas.
   - Sökfält som filtrerar på namn och nummer.
4. **Egen samtalshistorik ("Senaste")** i appen: lista över samtal som ringts från appen (namn om känt, slaget nummer, läge, tidpunkt). Tryck = ring igen med samma läge. Långtryck = ta bort post / rensa allt. Max 200 poster.
5. **Signerad release-APK** (v1+v2+v3) med en keystore som levereras till användaren så att framtida uppdateringar kan installeras över.

### Bör (P1)
6. **Dölj appens samtal i telefonens samtalslogg** (opt-in i Inställningar, default AV).
   Android loggar alltid samtal i systemets CallLog; det enda sättet är att radera posterna efteråt.
   - När inställningen slås på: begär `READ_CALL_LOG` + `WRITE_CALL_LOG` (visa en kort förklaring först). Nekas de → slå av inställningen igen.
   - Vid varje samtal från appen (när inställningen är på): spara en "pending"-post `{nummer, tidsstämpel}` i SharedPreferences (JSON-array) och schemalägg ett `JobScheduler`-jobb med `addTriggerContentUri(CallLog.Calls.CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS)`, `setTriggerContentUpdateDelay(2000)`, `setTriggerContentMaxDelay(10000)`, `setOverrideDeadline(10 min)`. Jobbet (`CallLogCleanupJobService`) kör städningen och schemalägger om sig själv så länge pending-poster finns och är yngre än 6 h.
   - Städning körs också i `MainActivity.onResume()`.
   - Städlogik: query `CallLog.Calls` med `TYPE = OUTGOING_TYPE AND DATE >= (pending.ts - 120000)`; för varje rad: normalisera både loggat nummer och pending-nummer (ta bort `#31#`/`*31#`-prefix, behåll endast siffror och inledande `+`); matcha om `PhoneNumberUtils.compare(a, b)` eller om den ena slutar med den andra och den kortare är ≥ 5 siffror. Radera via `_ID`. Ta bort pending-posten när den raderats eller är äldre än 6 h.
   - Allt best-effort: fånga alla exceptions, logga, krascha aldrig.
7. **Kontaktmatchning i knappsatsen**: när slagna siffror matchar (contains) en kontakts jobbnr eller privatnr visas max 3 träffar under nummerfältet; tryck fyller i numret.
8. **Förhandsvisning** under nummerfältet: `Internt → 03134 30771 · Patient → #31# 30771` (uppdateras live, döljs när fältet är tomt).
9. **Export/Import av kontakter** (JSON) via Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`, mime `application/json`). Import = slå ihop (hoppa över kontakt med identiskt jobbnr eller identiskt namn+privatnr).
10. **Mörkt läge** följer systemet (DayNight-tema).
11. **Statisk app-genväg** (långtryck på appikonen): "Ring växeln".

### Ska INTE finnas (medvetet bortvalt)
- Ingen integration med telefonens kontakter, ingen inkommande-samtals-hantering, ingen default-dialer-roll, ingen SMS, ingen molnsynk, inga annonser/analytics, ingen multi-SIM-väljare (systemet frågar själv om det behövs), ingen Kotlin, inga AndroidX/Material-beroenden.

## 2. Teknikval (bindande)
- **Språk:** Java 17 (AGP desugarar). **Inga externa beroenden alls** – endast Android-ramverket (`android.*`, `org.json`). Motiv: reproducerbart bygge utan Maven-nedladdningar utöver AGP, och projektet kan verifieras med `javac` mot `android.jar`.
- **minSdk 26**, **targetSdk 34**, **compileSdk 35**. AGP `8.11.1`, Gradle `8.14.3` (wrapper), JDK 17+.
- **applicationId / paket:** `se.jobbdialer`. **App-namn:** `Jobbdialer`. versionCode 1, versionName `1.0`.
- **Lagring:** JSON-filer i `context.getFilesDir()`: `contacts.json`, `recents.json` (atomisk skrivning: skriv till `.tmp`, sedan `renameTo`). Inställningar i `SharedPreferences` (`jobbdialer_prefs`).
- **UI:** ett `MainActivity` med framework-`android.widget.Toolbar` (titel + overflow-meny) + `ViewFlipper` med tre paneler (Ring / Kontakter / Senaste) + egen bottenflik-rad (tre `TextView`-knappar med ikon). `ListView` + `BaseAdapter` för listor. `AlertDialog` för dialoger. `SettingsActivity` separat (enkel `ScrollView` med `EditText`/`Switch`).
- **Tema:** `values/themes.xml`: `Theme.Jobbdialer` parent `android:Theme.DeviceDefault.Light.NoActionBar`; `values-v29/themes.xml`: parent `android:Theme.DeviceDefault.DayNight.NoActionBar`. Egna färger: primär `#1565C0`, primär mörk `#0D47A1`, accent `#FF8F00`, patientknapp `#6A1B9A`, växel `#2E7D32`. Färger som fungerar i både ljust/mörkt (använd `?android:attr/textColorPrimary` m.fl. för text, egna `values-night/colors.xml` för ytor).
- **Ikon:** adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`) med vektor-foreground (telefonlur) på primärfärgad bakgrund. Inga PNG:er.
- **Nummerfält:** `EditText` med `inputType="phone"`, `setShowSoftInputOnFocus(false)` (egen knappsats, men markör/klistra in fungerar). Knappsats 3×4: `1–9`, `*`, `0` (långtryck = `+`), `#`. Backsteg-knapp (långtryck = rensa).
- **Ringknappar:** `RING INTERNT` (primär), `RING PATIENT` med undertext `dolt nummer`, och full bredd `RING VÄXELN` med undertext = växelnumret. Internt/patient inaktiveras när fältet är tomt.
- **Inställningar:** prefix internt, prefix patient, växelnummer, switch "Dölj appens samtal i telefonens samtalslogg", knappar Exportera/Importera kontakter, "Återställ standard", versionsrad.

## 3. Projektstruktur (bindande)
```
jobbdialer/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/wrapper/gradle-wrapper.properties  (+ gradle-wrapper.jar genereras med lokal gradle)
  gradlew, gradlew.bat
  signing.properties               # keystore-sökväg/alias/lösenord (läses av app/build.gradle.kts; env-vars kan skriva över)
  keystore/jobbdialer.jks          # genereras av Fable
  keystore/README.md               # lösenord + instruktioner
  app/build.gradle.kts
  app/proguard-rules.pro           # minify av, men filen finns
  app/src/main/AndroidManifest.xml
  app/src/main/java/se/jobbdialer/
     MainActivity.java             # toolbar, flikar, panelväxling, onResume-städning, hantering av genvägs-intent
     SettingsActivity.java
     DialPane.java                 # knappsats, förhandsvisning, kontaktmatchning, ringknappar
     ContactsPane.java             # sök, lista, sortering, lägg till/redigera/ta bort, långtrycksmeny
     RecentsPane.java              # historiklista
     Contact.java                  # modell + JSON (to/from)
     ContactStore.java             # laddning/sparning/sortering/sök/merge-import
     CallRecord.java               # modell + JSON
     RecentsStore.java
     Prefs.java                    # SharedPreferences-wrapper (prefix, växel, sortering, hideInCallLog)
     Dialer.java                   # bygg nummer per läge, ACTION_CALL/ACTION_DIAL, behörighetsflöde, formatering av nummer för visning
     CallLogCleaner.java           # pending-lista + städlogik
     CallLogCleanupJobService.java # JobService
     Numbers.java                  # normalisering, formatering ("031 34 307 71"), matchning
  app/src/main/res/
     layout/activity_main.xml, pane_dial.xml, pane_contacts.xml, pane_recents.xml, row_contact.xml, row_recent.xml, dialog_contact_edit.xml, activity_settings.xml
     values/strings.xml (svenska), colors.xml, themes.xml, dimens.xml
     values-night/colors.xml
     values-v29/themes.xml
     drawable/*.xml (vektorikoner: lur, backsteg, kontakt, klocka, plus, sök; bakgrunder för knappar/keypad)
     mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
     menu/menu_main.xml
     xml/shortcuts.xml
  .github/workflows/build-apk.yml  # bygger + signerar + laddar upp artefakt + skapar GitHub Release med APK
  build-manual.sh                  # bygger utan Gradle: aapt2 → javac → d8 → zip → zipalign → apksigner (kräver ANDROID_BUILD_TOOLS + ANDROID_JAR)
  tools/gen_r.py                   # genererar R.java från res/ (för lokal javac-kontroll)
  tools/check.sh                   # XML-validering + resursreferenskontroll + javac mot android.jar
  README.md                        # svensk: funktioner, installation (sideload), bygga själv (3 sätt), behörigheter
```

## 4. Manifest
- Behörigheter: `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`. Inga andra.
- `MainActivity`: launcher, `android:launchMode="singleTop"`, `android:windowSoftInputMode="adjustResize"`, `android:exported="true"`, `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts"/>`.
- `SettingsActivity`: `exported="false"`, `parentActivityName`.
- `CallLogCleanupJobService`: `android:permission="android.permission.BIND_JOB_SERVICE"`, `exported="false"` (Android 14-krav med explicit value).
- `android:allowBackup="true"`, `android:supportsRtl="true"`, `android:theme="@style/Theme.Jobbdialer"`.

## 5. Kodkvalitet
- Ingen kod får referera till AndroidX/Material. Inga lambdas som kräver externa bibliotek (vanliga Java-lambdas är OK).
- Alla strängar i `strings.xml` (svenska). Inga hårdkodade UI-strängar i Java.
- Null-säkerhet, try/catch runt all I/O och alla ContentResolver-anrop.
- Kommentarer på engelska, UI på svenska.
- `tools/check.sh` ska passera utan fel innan leverans.

## 6. Byggvägar (alla tre ska fungera)
1. **GitHub Actions** (`.github/workflows/build-apk.yml`): `ubuntu-latest`, `actions/setup-java@v4` (temurin 17), `android-actions/setup-android@v3` (eller SDK:n som redan finns på runnern), `./gradlew assembleRelease`, signering via `signing.properties`/keystore i repot (eller env-secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` om de finns), ladda upp `app/build/outputs/apk/release/*.apk` som artefakt, och vid tag `v*` även skapa Release med APK:n.
2. **Android Studio / lokal Gradle**: öppna projektet, `./gradlew assembleRelease`.
3. **build-manual.sh** utan Gradle (dokumenterat i README).
