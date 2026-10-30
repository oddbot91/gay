# Jobbdialer 1.7 – tilläggsspecifikation

Bygger på SPEC.md, SPEC-1.1.md och koden som den ser ut nu (läs den!). Pure Java, framework-only, svenska UI-strängar,
engelska kodkommentarer. versionCode 8, versionName "1.7" (sätts av orkestreraren).

## 1. Kontaktraden: numren bredvid varandra
- `row_contact.xml`: under namnraden ligger EN horisontell rad med två "chips" (klickbara LinearLayouts med ikon + text,
  `?android:attr/selectableItemBackground`, rundad bakgrund `chip_bg.xml` i `@color/keypad_bg`):
  `[📞 Jobb 30771]  [📱 Privat 070-123 45 67]`. Varje chip `layout_width=0dp, weight=1`, text `maxLines=1`, `ellipsize=end`.
  Saknas ett nummer → den chipen GONE och den andra tar hela bredden. Saknas båda → raden GONE.
- Ikonfärger som förut (`mode_internal` för jobb, `mode_private` för privat).

## 2. Tryck på nummer = fyll i dialern (ring inte direkt)
- Tryck på Jobb-chip → MainActivity.openDialerWith(workNumber) → visar fliken Ring med numret inslaget (markör sist).
  Tryck på Privat-chip → samma med privatnumret. Lägesväljaren rörs inte (fullnummerskyddet gör att ett mobilnummer
  rings som det är även i läget Internt). Ingen uppringning sker förrän användaren trycker RING.
- `DialPane.setNumber(String)` sätter fältet och uppdaterar förslag/knapp.
- Senaste: tryck på en post → `openDialerWith(record.dialedNumber)` (numret som faktiskt rangs, inkl. ev. #31#).
  Långtrycksmenyn i Senaste får överst `Ring igen direkt` (nuvarande redial), sedan `Ta bort`, `Rensa alla`.
- Långtrycksmenyn på kontakt: `Ring jobbnr direkt` (om jobbnr), `Ring privat direkt` (om privat), `Ring privat med
  dolt nummer (#31#)` (om privat), `Skicka SMS` (om privat), `Redigera`, `Lägg till i/Ta bort från favoriter`,
  `Välj flera…`, `Ta bort`.

## 3. Markera flera + bulkradering (ContactsPane)
- Urvalsläge startas via långtrycksmenyn `Välj flera…` eller toolbar-menyn `Välj kontakter`. I urvalsläget:
  - Varje kontaktrad visar en CheckBox till vänster (id `select_box`, GONE annars); tryck på raden eller rutan växlar val.
    Chips är inte klickbara i urvalsläget (tryck går till valet).
  - Tryck på en grupprubrik växlar val för ALLA kontakter i gruppen (och expanderar den om den var kollapsad).
    Favoriter-rubriken väljer alla favoriter.
  - Bottenraden (sök/sortera/+) byts mot en urvalsrad: `N valda` · knapp `Markera alla` (alla synliga = alla kontakter,
    eller sökträffarna om sökning pågår) · knapp `Ta bort` (röd text, inaktiv vid 0) · knapp `Avbryt`.
  - `Ta bort` → AlertDialog "Ta bort N kontakter?" → ContactStore.deleteAll(Collection<String> ids) (en save) → toast
    "N kontakter togs bort" → urvalsläget avslutas → refreshPanes().
  - Bakåt-knappen (MainActivity.onBackPressed – framework, @SuppressWarnings("deprecation") ok) avslutar urvalsläget
    om det är aktivt, annars default.
  - Valet hålls som `Set<String>` av kontakt-id i panelen (inte persistent). Byte av flik avslutar inte urvalsläget,
    men sökning/sortering behåller valet.

## 4. Swipe mellan flikarna
- MainActivity: `GestureDetector` matas från `dispatchTouchEvent` (anropa super också). Horisontell fling
  (|dx| > |dy|·2, |dx| > 80dp, |velocityX| > 400 dp/s) → `showTab(current ± 1)` med gräns (ingen wrap).
  Ignorera gesten om den startar i nummerfältet? Nej – hela ytan får swipas, men ListView/ScrollView ska fortsätta
  fungera normalt (vi konsumerar inte eventen, vi bara observerar).
- ViewFlipper får slide-animationer: vid byte åt höger (högre index) in från höger/ut åt vänster, och omvänt.
  Egna `res/anim/slide_in_right.xml`, `slide_out_left.xml`, `slide_in_left.xml`, `slide_out_right.xml` (200 ms,
  `translate` 100%p ↔ 0). Flikknapparna längst ned använder samma animationer. Vid återställning (onCreate med
  savedInstanceState) ingen animation.

## 5. SMS-knapp i dialern
- Bottenraden i `pane_dial.xml` blir `[sudda][RING …][SMS]`: SMS = ImageButton (ny vektor `ic_sms.xml`, en pratbubbla),
  samma storlek/stil som sudda (`btn_backspace`), 8dp mellanrum. Aktiv bara när fältet inte är tomt (alpha 0.4 när
  inaktiv).
- Tryck → `Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(target)))` med try/catch
  (ActivityNotFoundException → toast "Hittade ingen SMS-app"). target = numret utan `#31#`/`*31#`:
  om `Numbers.isFullNumber(cleaned)` → cleaned, annars internprefix + cleaned (Dialer.buildNumber INTERNAL).
  Inga nya behörigheter.
- Kontaktmenyn: `Skicka SMS` → samma intent med privatnumret.

## 6. Övrigt
- `tools/check.sh` ska ge RESULT: PASS; `bash tools/jvmtest/run.sh` ska fortfarande passera (rör inte T9/ContactMatcher).
- Nya strängar i `res/values/strings_contacts.xml` resp. `strings_dial.xml` (svenska, korrekt åäö).
- README.md: uppdatera avsnitten (kontaktrad, tryck = fyll i dialern, markera flera/bulkradering, swipe, SMS-knapp).
- Behåll T9-självtestraden i Inställningar.
