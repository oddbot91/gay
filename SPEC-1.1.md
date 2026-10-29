# Jobbdialer 1.1 – tilläggsspecifikation

Bygger vidare på SPEC.md (v1.0). Allt nedan är bindande. Pure Java, framework-only, svenska UI-strängar.
versionCode 2, versionName "1.1" (sätts av orkestreraren).

## A. Ring-panelen (ägare: agent A – DialPane.java, pane_dial.xml, Dialer.java, Numbers.java, ny T9.java,
##    colors.xml/values-night/colors.xml, nya drawables, res/values/strings_dial.xml, res/values/dimens_dial.xml)

A1. **Lägesväljare ovanför knappsatsen** (segmenterad kontroll, tre segment i en rad):
    `Internt | Patient | Växeln`. Valt segment fylls med lägets färg och vit text; övriga har transparent
    bakgrund och lägets färg som textfärg. Rundade hörn (en gemensam bakgrund `seg_bg.xml` + per-segment
    tint via `setBackgroundTintList`). Valet sparas i Prefs (`dial_mode`, default INTERNAL) så appen minns det.
A2. **En enda ringknapp** under knappsatsen (full bredd, hög). Bakgrundsfärgen = valt läges färg
    (`setBackgroundTintList` på `btn_call.xml` = ripple + rundad vit shape). Rubrik: `RING INTERNT` /
    `RING PATIENT` / `RING VÄXELN`. Underrad: numret som faktiskt rings (formaterat med Numbers.formatForDisplay),
    t.ex. `031 34 307 71`, `#31# 070-123 45 67`, eller växelnumret. I Internt/Patient är knappen inaktiverad
    (grå, `btn_disabled`) när fältet är tomt; i Växeln alltid aktiv (fältets innehåll ignoreras).
    Den gamla preview-raden och de tre gamla knapparna tas bort.
A3. **Lägesfärger** (byt värden i colors.xml, behåll namnen så Senaste-fliken följer med automatiskt):
    `mode_internal` = grön `#2E7D32` (night `#66BB6A`), `mode_patient` = lila `#6A1B9A` (night `#BA68C8`),
    `mode_switchboard` = blå `#1565C0` (night `#64B5F6`), `mode_private` oförändrad.
A4. **T9-sökning**: när användaren slår siffror visas kontaktträffar (max 4 rader) under nummerfältet.
    Träff om (a) jobbnr eller privatnr innehåller siffrorna (som förut), eller (b) namnet matchar siffrorna
    som T9: svensk knappsats 2=ABCÅÄ, 3=DEFÉ, 4=GHI, 5=JKL, 6=MNOÖ, 7=PQRS, 8=TUV, 9=WXYZ, 0=mellanslag.
    Matchning (skiftlägesokänslig, diakritiska tecken enligt tabellen): sekvensen matchar början av något
    ord i namnet (förnamn eller efternamn), eller början av hela namnet med mellanslag borttagna,
    eller början av "förnamn efternamn" där 0 = mellanslag. Minst 2 siffror krävs för T9-namnträff
    (nummerträff fungerar från 1 siffra). Ny klass `T9.java` med `static String toDigits(String text)` och
    `static boolean matchesName(String firstName, String lastName, String digits)`.
    Radtext: `Anna Andersson · Jobb 30771` (nummerträff visar det matchade numret; T9-träff visar jobbnr om
    det finns, annars privatnr). Tryck på rad → fyll fältet med det numret, markör sist.
    Namnträffar via T9 ska INTE radera det användaren slagit förrän man trycker på raden.
A5. Knappsatsen ska visa T9-bokstäverna små under siffran (`2` / `ABC`, `6` / `MNO` …) – gör knapparna till
    vertikala LinearLayouts eller använd en TextView med två rader (siffra 26sp, bokstäver 10sp,
    textColorSecondary). `1` tom, `0` visar `+`, `*`/`#` utan undertext.
A6. Dialer.java: `CallMode` får inte ändras i namn (INTERNAL/PATIENT/SWITCHBOARD/PRIVATE används av
    CallRecord-JSON). Lägg till `static CallMode parseDialMode(String)` om det behövs. Behåll
    fullnummer-skyddet för INTERNAL. Prefs får `getDialMode()/setDialMode()` (nyckel `dial_mode`).
    OBS: Prefs.java ägs av agent A i denna runda; agent B rör inte Prefs.
A7. Genvägen "Ring växeln" fungerar som förut (Dialer.callSwitchboard()).

## B. Kontakter (ägare: agent B – Contact.java, ContactStore.java, ContactsPane.java, MainActivity.java,
##    pane_contacts.xml, row_contact.xml, dialog_contact_edit.xml, nya layouter/dialoger,
##    res/values/strings_contacts.xml, res/values/dimens_contacts.xml, README.md)

B1. **Datamodell**: Contact får `category` (String, "" = ingen) och `favorite` (boolean). JSON-fält
    `category`, `favorite`; saknade fält → "" / false (bakåtkompatibelt med v1.0-filer).
    contacts.json byter format till ett objekt `{"format":"jobbdialer-contacts","version":2,
    "categories":["Läkare","Avdelningar","Operationssalar","Konsulttelefoner"],"contacts":[...]}`.
    Inläsning accepterar både gammal bar array och nytt objekt. Första gången (ingen kategorilista finns)
    seedas de fyra kategorierna ovan. Export skriver version 2 med kategorier; import läser båda och lägger
    till okända kategorier från filen.
B2. **Kategorier**: `ContactStore.getCategories()` (ordning = användarens, alfabetisk sv-SE vid seed),
    `addCategory(name)` (trim, ej tom, ej dubblett skiftlägesokänsligt), `removeCategory(name)`
    (kontakter i kategorin får category=""), `renameCategory(old,new)` (valfritt). Kontakter med okänd
    kategori visas under den ändå (kategorin läggs till i listan vid inläsning).
B3. **Kontaktlistan blir grupperad och kollapsbar** – egen BaseAdapter med två radtyper (rubrik/kontakt),
    INTE ExpandableListView. Ordning: en grupp per kategori (i kategorilistans ordning), sedan `Övriga`
    (kontakter utan kategori; döljs om tom), och **sist gruppen `Favoriter`** som ALLTID är expanderad och
    visar max 10 favoriter (sorterade enligt vald sortering; är fler markerade visas de 10 första).
    Rubrikrad: pil (▸ kollapsad / ▾ expanderad, rotera en vektor-chevron), kategorinamn fetstil,
    antal kontakter i grått till höger. Tryck på rubrik växlar kollaps. Favoriter-rubriken har ingen pil
    (stjärnikon i stället) och går inte att kollapsa. Kollapsläget hålls i minnet i ContactsPane och är
    ALLTID helt kollapsat när appen startar (sparas inte). Vid aktiv sökning visas träffarna platt utan
    rubriker (eller med alla grupper expanderade) – välj platt.
    Kontaktraden inom en grupp: som v1.0 (namn, Jobb-rad, Privat-rad, långtryck-meny), indragen 8dp.
    Favoritkontakter visar en liten stjärna efter namnet i alla grupper.
B4. **Redigeringsdialogen** får: Spinner `Kategori` (poster: "Ingen kategori", kategorierna, sist
    "+ Ny kategori…" som öppnar en liten inmatningsdialog och väljer den nya) och en CheckBox/Switch
    `Favorit`. Max 10 favoriter: om man försöker markera en elfte → toast "Max 10 favoriter" och rutan
    avmarkeras.
B5. **Långtrycksmenyn** på kontakt får även `Lägg till i favoriter` / `Ta bort från favoriter`.
B6. **Hantera kategorier**: ny post i toolbar-menyn `Kategorier…` (MainActivity menu_main.xml ägs av B)
    → dialog med listan över kategorier, varje rad har en ×-knapp (ta bort, med bekräftelse som säger hur
    många kontakter som flyttas till Övriga) och längst ner ett fält + `Lägg till`. Alternativt två steg
    med AlertDialog-items – välj det som blir enklast men funktionellt.
B7. **Bulkimport**: menyposten `Importera kontakter` öppnar en valdialog: `Från fil (JSON eller CSV)` /
    `Klistra in lista`.
    - Fil: SAF-väljaren accepterar även `text/csv`, `text/comma-separated-values`, `text/plain`.
      JSON hanteras som förut; annars tolkas texten som CSV/lista enligt nedan.
    - Klistra in: dialog med flerradigt EditText och hjälptext med formatet. En kontakt per rad:
      `Förnamn Efternamn ; Jobbnr ; Privatnr ; Kategori` – separatorer `;`, tab eller `,` (första som
      förekommer på raden avgör). Namnfältet delas: sista ordet = efternamn, resten = förnamn (ett ord =
      förnamn). En ev. rubrikrad hoppas över om den innehåller "namn" eller "jobb" (skiftlägesokänsligt).
      Tomma rader ignoreras. Kategori som inte finns skapas. Dubblettregler som JSON-importen.
      Resultat-toast: `12 kontakter lades till. 2 hoppades över.`
    - All parsning i ContactStore (`importText(String)` → ImportResult) så den kan återanvändas.
B8. MainActivity: menu_main.xml får `Kategorier…`; import-flödet anpassas (valdialog). Kontaktflikens
    `refresh()` behåller kollapsläget men bygger om grupperna.
B9. README.md: uppdatera funktionslistan (lägesväljare + en ringknapp, T9, kategorier, favoriter, bulkimport
    med formatexempel) – agent B skriver detta när agent A:s del är känd (beskriv A enligt denna spec).

## Gemensamt
- Nya strängar läggs i egna filer (`strings_dial.xml` resp. `strings_contacts.xml`) – rör inte
  `strings.xml` utom för att ta bort strängar som blivit oanvända (bara agent A får ta bort dial-strängar).
- `tools/check.sh` ska ge RESULT: PASS. Ingen Gradle-körning (offline).
- Inga AndroidX/Material-beroenden. Java 17, API 26+.
