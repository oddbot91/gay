# Signeringsnyckel för Jobbdialer

Release-APK:n signeras (v1 + v2 + v3) med en nyckel som **inte** ligger i repot, eftersom repot
är publikt. Nyckelfilen och lösenordet finns bara hos dig (och som secrets i GitHub).

| | |
|---|---|
| Fil (lokalt, ej i repot) | `keystore/jobbdialer.jks` (PKCS12) |
| Alias | `jobbdialer` |
| Lösenord (keystore och nyckel) | samma lösenord; står i `signing.properties` (ej i repot) |
| Certifikat | `CN=Jobbdialer, OU=Privat, O=Jobbdialer, L=Goteborg, C=SE`, giltigt till 2056 |
| SHA-256 | `14:E0:CA:9F:61:E8:EE:74:26:32:17:CA:95:21:2F:59:A6:BE:66:99:15:D9:9A:88:C0:06:0C:9E:0C:A7:D9:AC` |

## Viktigt: spara nyckeln

Android installerar bara en uppdatering ovanpå en befintlig app om den nya APK:n är signerad
med **samma nyckel**. Spara därför `jobbdialer.jks` och lösenordet på ett säkert ställe, t.ex. i
lösenordshanteraren. Om nyckeln försvinner måste appen avinstalleras före nästa installation,
och då försvinner kontakter och historik (exportera kontakterna först i så fall).

## GitHub Actions: lägg in nyckeln som secrets

Gå till repot → **Settings → Secrets and variables → Actions → New repository secret** och
skapa:

| Secret | Värde |
|---|---|
| `KEYSTORE_B64` | hela innehållet i `jobbdialer.jks` som base64 (`base64 -w0 keystore/jobbdialer.jks`) |
| `KEYSTORE_PASSWORD` | lösenordet |
| `KEY_ALIAS` *(valfri)* | `jobbdialer` (default) |
| `KEY_PASSWORD` *(valfri)* | samma som `KEYSTORE_PASSWORD` (default) |

Utan `KEYSTORE_B64` bygger workflowet ändå, men signerar då med en **tillfällig nyckel** som
skapas för just den körningen. En sådan APK går att installera men kan inte uppdateras av nästa
bygge utan avinstallation. Bygget skriver ut en varning när det sker.

## Lokalt bygge

`./gradlew assembleRelease` och `build-manual.sh` läser `signing.properties` i repots rot:

```
storeFile=keystore/jobbdialer.jks
storePassword=…
keyAlias=jobbdialer
keyPassword=…
```

Miljövariablerna `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` och `KEY_PASSWORD` gäller
före filen. Båda filerna är git-ignorerade.

## Kontrollera en APK

```sh
$BUILD_TOOLS/apksigner verify --print-certs app/build/outputs/apk/release/*.apk
```

SHA-256-värdet ska stämma med tabellen ovan.
