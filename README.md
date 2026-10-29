# Jobbdialer

En enkel arbetsdialer för Android. Du slår ett nummer på appens egen knappsats och väljer
hur samtalet ska ringas: internt, till en patient (med dolt nummer) eller till växeln.
Appen har en egen telefonbok och en egen samtalshistorik. Den har inga annonser, ingen
molnsynk och inga beroenden utöver Android självt.

Kräver Android 8.0 (API 26) eller senare.

## Funktioner

### Tre ringlägen, en ringknapp

Ovanför knappsatsen finns en lägesväljare med tre segment: **Internt | Patient | Växeln**.
Det valda läget fylls med lägets färg (grönt, lila respektive blått) och appen minns ditt val.
Under knappsatsen finns en enda stor ringknapp i lägets färg. Rubriken ändras med läget och
underraden visar numret som faktiskt rings.

| Läge | Knapp | Vad som rings | Exempel |
|---|---|---|---|
| **Internt** | RING INTERNT | internprefix + slaget nummer (standard `03134`) | `30771` → `0313430771` |
| **Patient** | RING PATIENT | patientprefix + slaget nummer (standard `#31#` = dolt nummer) | `0701234567` → `#31#0701234567` |
| **Växeln** | RING VÄXELN | växelnumret, utan prefix (standard `0313427000`) | → `0313427000` |

- I lägena Internt och Patient är knappen grå och inaktiv så länge nummerfältet är tomt. I
  läget Växeln är den alltid aktiv.
- Om numret redan börjar med prefixet läggs prefixet inte till en gång till. Internprefixet läggs inte heller till om numret är ett fullständigt nummer (börjar med 0 eller + och har minst 8 siffror).
  (`0313430771` → `0313430771`).
- Numret som rings visas på ringknappens underrad, t.ex. `031 34 307 71` eller `#31# 070-123 45 67`.
- Prefixen och växelnumret ändrar du under **Inställningar**. Där finns också
  **Återställ standard**.
- Samtalet kopplas upp direkt. Första gången frågar Android om behörigheten **Ring**
  (”ringa och hantera telefonsamtal”). Om du nekar öppnas telefonens egen app med numret
  ifyllt, och du trycker på ring där.
- Om du håller in appikonen visas genvägen **Ring växeln**.

### Telefonbok

- Varje kontakt har förnamn, efternamn, jobbnr (kortnummer) och privatnr.
- Tryck på **Jobb 30771** för att ringa internt (med prefix). Tryck på
  **Privat 070-444 44 44** för att ringa privatnumret som det är, utan prefix.
- Håll in en kontakt för att välja **Ring privat med dolt nummer (#31#)**, **Lägg till i
  favoriter** / **Ta bort från favoriter**, **Redigera** eller **Ta bort**.
- Du kan söka på namn och nummer. Listan kan sorteras på efternamn, förnamn, jobbnr eller
  privatnr, och appen kommer ihåg valet.
- Kontakterna kan ordnas i **kategorier** och **favoriter** (se nedan).

### Sök kontakter från knappsatsen (T9)

När du slår siffror visas upp till fyra träffar under nummerfältet. En kontakt matchar om
jobbnumret eller privatnumret innehåller siffrorna, eller om namnet matchar siffrorna som på en
gammal mobiltelefon (T9). Knapparna visar bokstäverna: `2` = ABC, `3` = DEF, `4` = GHI,
`5` = JKL, `6` = MNO, `7` = PQRS, `8` = TUV, `9` = WXYZ (svenska Å, Ä och Ö ligger på 2 och 6).
`0` är mellanslag.

- Siffrorna ska matcha början av förnamnet eller efternamnet, eller början av hela namnet.
  `2662` hittar *Anna* (A-N-N-A) och `26620263` hittar *Anna Andersson*
  (förnamn, mellanslag, början av efternamnet).
- Namnsökningen kräver minst två siffror. Nummersökningen fungerar från en siffra.
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

- Tryck på ett samtal för att ringa exakt samma nummer igen.
- Håll in ett samtal för att **Ta bort** det eller **Rensa alla**.

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
