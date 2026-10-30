# Jobbdialer

En enkel arbetsdialer för Android. Du slår ett nummer på appens egen knappsats och väljer
hur samtalet ska ringas: internt, till en patient (med dolt nummer) eller till växeln.
Appen har en egen telefonbok och en egen samtalshistorik. Den har inga annonser, ingen
molnsynk och inga beroenden utöver Android självt.

Kräver Android 8.0 (API 26) eller senare.

## Funktioner

### Två ringlägen och växeln, en ringknapp

Ovanför knappsatsen finns en lägesväljare med två segment: **Internt | Patient**.
Det valda läget fylls med lägets färg (grönt respektive lila) och appen minns ditt val.
Under knappsatsen finns en enda stor ringknapp, till vänster om den backstegsknappen
(tryck = radera en siffra, håll in = rensa fältet) och till höger en **SMS-knapp** (se nedan).

- **Tomt nummerfält:** knappen heter **RING VÄXELN**, är blå och ringer växelnumret utan prefix
  (standard `0313427000`). Underraden visar växelnumret. Är inget växelnummer inställt är
  knappen grå och inaktiv.
- **Nummer ifyllt:** knappen heter **RING INTERNT** eller **RING PATIENT** beroende på valt läge,
  har lägets färg och underraden visar numret som faktiskt rings.

| Läge | Knapp | Vad som rings | Exempel |
|---|---|---|---|
| **Internt** | RING INTERNT | internprefix + slaget nummer (standard `03134`) | `30771` → `0313430771` |
| **Patient** | RING PATIENT | patientprefix + slaget nummer (standard `#31#` = dolt nummer) | `0701234567` → `#31#0701234567` |
| *(tomt fält)* | RING VÄXELN | växelnumret, utan prefix (standard `0313427000`) | → `0313427000` |

- Om numret redan börjar med prefixet läggs prefixet inte till en gång till. Internprefixet läggs inte heller till om numret är ett fullständigt nummer (börjar med 0 eller + och har minst 8 siffror).
  (`0313430771` → `0313430771`).
- Numret som rings visas på ringknappens underrad, t.ex. `031 34 307 71` eller `#31# 070-123 45 67`.
- Prefixen och växelnumret ändrar du under **Inställningar**. Där finns också
  **Återställ standard**.
- Samtalet kopplas upp direkt. Första gången frågar Android om behörigheten **Ring**
  (”ringa och hantera telefonsamtal”). Om du nekar öppnas telefonens egen app med numret
  ifyllt, och du trycker på ring där.
- Om du håller in appikonen visas genvägen **Ring växeln**.

### SMS-knappen

Pratbubblan till höger om ringknappen öppnar telefonens SMS-app med numret i fältet som
mottagare. Knappen är grå och inaktiv när fältet är tomt. Ett inledande `#31#`/`*31#` tas
bort (dolt nummer gäller bara samtal). Ett fullständigt nummer (börjar med 0 eller + och har
minst 8 siffror) används som det är, ett kortnummer får internprefixet, t.ex. `30771` →
`0313430771`. Appen behöver ingen SMS-behörighet; finns ingen SMS-app visas
”Hittade ingen SMS-app”.

### Byt flik genom att svepa

Svep åt vänster eller höger var som helst på skärmen för att gå till fliken bredvid
(Ring ↔ Kontakter ↔ Senaste). Det går inte runt från sista till första fliken. Panelerna
glider in från sidan, både när du sveper och när du trycker på flikarna längst ner. Listor
rullar som vanligt uppåt och nedåt; bara tydligt vågräta svep byter flik.

### Telefonbok

- Varje kontakt har förnamn, efternamn, jobbnr (kortnummer) och privatnr.
- Under namnet visas numren bredvid varandra som två knappar: **Jobb 30771** och
  **Privat 070-444 44 44**. Saknas det ena numret tar det andra hela bredden.
- Tryck på ett nummer för att **fylla i det i knappsatsen**: appen byter till fliken Ring med
  numret inslaget. Inget rings förrän du trycker på ringknappen. Lägesväljaren ändras inte
  (ett mobilnummer rings som det är även i läget Internt).
- Håll in en kontakt för menyn: **Ring jobbnr direkt**, **Ring privat direkt**, **Ring privat
  med dolt nummer (#31#)**, **Skicka SMS** (till privatnumret), **Redigera**, **Lägg till i
  favoriter** / **Ta bort från favoriter**, **Välj flera…**, **Dölj kontakt** / **Visa kontakt**
  och **Ta bort**. Menyposter för
  nummer som saknas visas inte.
- Du kan söka på namn och nummer. Listan kan sorteras på efternamn, förnamn, jobbnr eller
  privatnr, och appen kommer ihåg valet.
- Kontakterna kan ordnas i **kategorier** och **favoriter** (se nedan).
- **Markera flera och dölj eller ta bort på en gång:** välj **Välj flera…** i kontaktens meny eller
  **Välj kontakter** i menyn (⋮). Varje kontakt får en kryssruta; tryck på en kontakt för att
  markera eller avmarkera den. Tryck på en grupprubrik för att markera (eller avmarkera) alla
  kontakter i gruppen – gruppen fälls ut. Rubriken Favoriter markerar alla favoriter.
  Längst ner visas i stället för sökfältet `N valda`, **Åtgärder ▾** och **Avbryt**.
  **Åtgärder** öppnar en meny med **Markera alla** (kontakterna som visas i listan, eller
  sökträffarna om du har sökt), **Dölj valda**, **Visa valda** och **Ta bort valda**.
  **Ta bort valda** frågar först ”Ta bort N kontakter?”. Bakåtknappen avslutar markeringsläget.
- Sökfältet, sorteringen och knappen **+** (ny kontakt) ligger längst ner, nära tummen, och
  listan fylls nerifrån, så att gruppen Favoriter hamnar precis ovanför sökfältet.

### Dolda kontakter

En kontakt som du sällan behöver kan **döljas** i stället för att tas bort: håll in kontakten och
välj **Dölj kontakt**, kryssa i **Dold – visas bara vid sökning** i redigeringsdialogen, eller
markera flera kontakter och välj **Åtgärder ▾ → Dölj valda**. En dold kontakt visas inte i
kategorigrupperna, i Övriga eller i Favoriter (även om den är favorit), räknas inte i rubrikernas
antal och föreslås aldrig i knappsatsen (varken som sökträff eller bland favoriterna). Den hittas
däremot när du söker i fliken Kontakter, och då visas ett litet överstruket öga efter namnet.
Med **Visa dolda kontakter** i menyn (⋮) visas även de dolda kontakterna i listan (med ögat), så
att du kan markera dem och välja **Visa valda** eller hålla in en och välja **Visa kontakt**.
Valet sparas inte – det är avslaget varje gång appen startar. Samtalshistoriken visar fortfarande
namnet på en dold kontakt. Dolda kontakter följer med vid export och import.

### Sök kontakter från knappsatsen (T9)

När du slår siffror visas upp till fem träffar under nummerfältet, en rad per kontakt. Först
visas kontakter vars namn matchar siffrorna som på en gammal mobiltelefon (T9), därefter
kontakter vars nummer matchar. Knapparna visar bokstäverna: `2` = ABC, `3` = DEF, `4` = GHI,
`5` = JKL, `6` = MNO, `7` = PQRS, `8` = TUV, `9` = WXYZ (svenska Å, Ä och Ö ligger på 2 och 6).
`0` är mellanslag.

- Siffrorna ska matcha början av förnamnet eller efternamnet (eller ett ord i dem), eller
  början av hela namnet. `2662` hittar *Anna* (A-N-N-A) och `26620263` hittar *Anna Andersson*
  (förnamn, mellanslag, början av efternamnet). Namnsökningen fungerar från första siffran.
- Nummersökning: med en eller två siffror visas nummer som *börjar* med siffrorna, från tre
  siffror även nummer som *innehåller* dem (nummer som börjar med siffrorna visas först).
- Raden visar t.ex. `Anna Andersson · Jobb 30771`. Tryck på raden för att fylla i numret.
  Det du har slagit raderas inte förrän du trycker på en rad.

### Kategorier

Kontaktlistan är grupperad i kollapsbara kategorier. Från start finns **Avdelningar**,
**Konsulttelefoner**, **Läkare** och **Operationssalar**. Kontakter utan kategori hamnar under
**Övriga**.

- Tryck på en grupprubrik för att fälla ut eller in den. Alla grupper är hopfällda när appen
  startar.
- Välj kategori när du lägger till eller redigerar en kontakt. Väljer du *+ Ny kategori…* kan du
  skapa en ny direkt.
- **Kategorier…** i menyn (⋮) låter dig lägga till och ta bort kategorier. När du tar bort en
  kategori flyttas dess kontakter till Övriga (appen säger först hur många det gäller).
- Under sökning visas träffarna som en vanlig lista utan grupper.

### Favoriter

Markera en kontakt som **Favorit** i redigeringsdialogen, eller håll in kontakten och välj
**Lägg till i favoriter**. Favoriterna visas sist i listan i gruppen **Favoriter**, som alltid är
utfälld, och en liten stjärna visas efter namnet. Du kan ha högst 10 favoriter.

### Senaste

Här listas de samtal som har ringts från appen: namn (om det är känt), nummer, ringläge och
tidpunkt. Listan sparar högst 200 samtal.

- Tryck på ett samtal för att fylla i numret som ringdes (inklusive ett ev. `#31#`) i
  knappsatsen. Inget rings förrän du trycker på ringknappen.
- Håll in ett samtal för **Ring igen direkt** (exakt samma nummer och läge), **Ta bort** eller
  **Rensa alla**.

### Export och import av kontakter

Välj **Exportera kontakter** eller **Importera kontakter**, antingen i menyn (⋮) eller under
Inställningar. Exporten sparas som en JSON-fil via Androids filväljare, t.ex. på telefonen,
i Google Drive eller på ett SD-kort. Filen innehåller även kategorier och favoritmarkeringar
(filformat version 2). Äldre exportfiler utan kategorier går fortfarande att importera.

Import slår ihop filen med kontakterna som redan finns. En kontakt hoppas över om det redan
finns en kontakt med samma jobbnr, eller med samma namn och samma privatnr. Kategorier som
finns i filen men inte i appen skapas. Med en export/import kan du alltså flytta telefonboken
till en ny telefon.

### Importera många kontakter på en gång

**Importera kontakter** frågar först om du vill importera **Från fil (JSON eller CSV)** eller
**Klistra in lista**. Båda använder samma format: en kontakt per rad.

```
Förnamn Efternamn ; Jobbnr ; Privatnr ; Kategori
Anna Andersson; 30771; 070-123 45 67; Läkare
Bo Berg; 30772; ; Avdelningar
Cecilia Ek	30773
```

- Skilj fälten åt med semikolon, tab eller komma (det första av dem som finns på raden gäller).
- Det sista ordet i namnet blir efternamn och resten förnamn. Ett enda ord blir förnamn.
- Jobbnr, privatnr och kategori är valfria. En kategori som inte finns skapas.
- Tomma rader hoppas över, liksom en rubrikrad (en första rad som innehåller *namn* eller
  *jobb* utan siffror i nummerfälten).
- Kontakter som redan finns hoppas över enligt samma regler som ovan. Efteråt visas t.ex.
  *12 kontakter lades till. 2 hoppades över.*
- Filer som börjar med `{` eller `[` tolkas som JSON-export, allt annat som en lista enligt ovan.

### Dölj appens samtal i telefonens samtalslogg (valfritt)

Inställningen är **av** från början. Vid allra första start frågar appen en gång om du vill slå på den (knappen *Slå på* ber om behörigheten, *Inte nu* lämnar den avstängd); du kan alltid ändra den under Inställningar. Android sparar alltid samtal i telefonens egen
samtalslogg och har inget sätt att låta bli. När inställningen är på raderar appen därför
posterna i efterhand.

**Så fungerar det:**

1. När du slår på inställningen frågar appen efter behörigheten att läsa och ändra
   samtalsloggen. Om du nekar stängs inställningen av igen.
2. Varje samtal som du ringer från appen sparas som en väntande post (nummer + tid).
3. Ett bakgrundsjobb (Androids `JobScheduler`) körs när samtalsloggen ändras, alltså när
   samtalet är avslutat. Jobbet letar upp utgående samtal till samma nummer från samma tid och
   raderar dem. Samma städning körs också varje gång du öppnar appen.
4. Väntande poster som är äldre än 6 timmar tas bort.

**Begränsningar:**

- Allt sker på bästa möjliga sätt (best effort). Ibland dröjer det en stund innan posten
  försvinner. Om telefonen begränsar bakgrundsjobb hårt kan det dröja tills du öppnar appen
  nästa gång.
- Utan behörigheterna för samtalsloggen händer ingenting. Om du drar tillbaka dem i
  systeminställningarna stängs inställningen av nästa gång du öppnar Inställningar i appen.
- Bara samtal som har kopplats upp direkt från appen raderas. Det gäller alltså inte samtal
  som har gått via telefonens egen app när behörigheten att ringa saknades.
- Samtalet syns fortfarande hos **operatören**, på fakturan, i en annan telefonapp med egen
  logg och i säkerhetskopior som hann skapas innan posten raderades. Appen kan inte dölja
  något hos operatören.

## Installation (sideload)

1. Ladda ner `Jobbdialer-vX.Y.apk` (se *Bygga* nedan; den färdiga filen finns under GitHub
   Releases eller Actions-artefakter).
2. Öppna filen på telefonen. Första gången ber Android dig tillåta installation från okänd
   källa för appen du öppnade filen med (t.ex. Filer eller Chrome). Det gör du under
   *Inställningar → Appar → Särskild åtkomst → Installera okända appar*.
3. Installera och öppna **Jobbdialer**.
4. Godkänn behörigheten **Ring** när den efterfrågas vid ditt första samtal.

För att uppdatera installerar du den nya APK-filen ovanpå den gamla. Det fungerar så länge
den nya filen är signerad med samma nyckel (se *Signering*). Kontakter och historik finns
kvar.

## Bygga själv

Det finns tre sätt att bygga appen. Alla tre ger en signerad release-APK (v1 + v2 + v3).

### 1. GitHub Actions (enklast)

1. Lägg upp projektet i ett eget GitHub-repo (gratis byggminuter om repot är publikt).
2. Lägg in signeringsnyckeln som repo-secrets: `KEYSTORE_B64` (base64 av .jks-filen) och
   `KEYSTORE_PASSWORD` – se `keystore/README.md`. Utan dem bygger workflowet ändå, men med
   en tillfällig nyckel som inte går att uppdatera ovanpå.
3. Pusha till `main` eller `master`. Arbetsflödet `.github/workflows/build-apk.yml` kör
   kontrollerna, bygger med `./gradlew assembleRelease` och signerar.
4. Ladda ner APK-filen under **Actions → (senaste körningen) → Artifacts → `jobbdialer-apk`**
   (zip-fil; packa upp och installera `.apk`-filen i den). APK-filen heter `Jobbdialer-vX.Y.apk` (versionsnumret hämtas från `versionName`).
5. Varje bygge på `main` publicerar dessutom APK-filen under **Releases** (https://github.com/oddbot91/gay/releases/latest) som direkt nedladdning.
   skapas också en **GitHub Release** med APK-filen bifogad direkt som nedladdning.

### 2. Android Studio / Gradle lokalt

Kräver JDK 17+ och Android SDK (platform 35).

- **Android Studio:** öppna projektmappen och välj *Build → Generate App Bundles or APKs →
  Build APK(s)*, med varianten `release`.
- **Kommandorad:**

  ```sh
  ./gradlew assembleRelease
  ```

  APK-filen hamnar i `app/build/outputs/apk/release/`.

Signeringsuppgifterna läses från `signing.properties`. Miljövariablerna `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` och `KEY_PASSWORD` gäller före filen om de är satta.

### 3. `build-manual.sh` (utan Gradle)

Bygger direkt med SDK-verktygen (aapt2 → javac → d8 → zipalign → apksigner). Kräver JDK 17+
och Android SDK build-tools.

```sh
export ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar
export BUILD_TOOLS=$ANDROID_HOME/build-tools/35.0.0
./build-manual.sh
```

Resultatet blir `build-manual/jobbdialer-release.apk`. Kör `./build-manual.sh --help` för
fler alternativ.

### Snabbkontroll utan bygge

`tools/check.sh` kontrollerar XML-filer och resursreferenser, kompilerar all Java-kod med
`javac` mot `android.jar` (sätt `ANDROID_JAR`) och kontrollerar att inga AndroidX- eller
Material-beroenden har smugit sig in.

## Signering

- Nyckeln (`keystore/jobbdialer.jks`, alias `jobbdialer`) och lösenordet (`signing.properties`)
  ligger **inte** i repot – de är git-ignorerade. GitHub Actions får dem som secrets, lokala
  byggen från filerna.
- **Spara keystoren och lösenorden.** Android installerar bara en uppdatering ovanpå en
  befintlig app om den är signerad med samma nyckel. Om nyckeln försvinner måste appen
  avinstalleras, och då försvinner kontakterna. Exportera dem först i så fall.
- Mer om nyckeln finns i `keystore/README.md`.

## Behörigheter

| Behörighet | Varför |
|---|---|
| `CALL_PHONE` | Koppla upp samtal direkt från appen. |
| `READ_CALL_LOG`, `WRITE_CALL_LOG` | Bara för den valfria inställningen *Dölj appens samtal i telefonens samtalslogg*. Efterfrågas först när du slår på den. |

Appen har ingen internetbehörighet. Inga uppgifter lämnar telefonen, förutom de filer du själv
exporterar.
