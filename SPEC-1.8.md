# Jobbdialer 1.8 – dolda kontakter

Bygger på koden som den ser ut nu (läs ContactsPane, ContactStore, Contact, DialPane, MainActivity, pane_contacts.xml,
row_contact.xml, dialog_contact_edit.xml, menu_main.xml, strings_contacts.xml). Pure Java, framework-only, svenska
UI-strängar, engelska kodkommentarer. versionCode 9, versionName "1.8" (sätts av orkestreraren).

## Beteende
1. **Datamodell**: `Contact.hidden` (boolean, JSON-fält `hidden`, saknas → false; export/import v2 tar med det;
   `withNewId()` och övriga kopior bevarar fältet).
2. **Dold kontakt syns INTE**:
   - i kontaktlistans kategorigrupper, i `Övriga` eller i `Favoriter` (även om favorite=true) när sökfältet är tomt,
   - i rubrikernas antal,
   - i dialerns förslag (T9/nummer) och i dialerns favoritlista vid tomt fält,
   - vid `Markera alla` i urvalsläget (om de inte är synliga just då).
3. **Dold kontakt syns BARA**:
   - som träff när användaren söker i Kontakter-fliken (sökfältet icke-tomt) – vanlig sökning (namn/nummer),
   - eller när meny-toggeln **Visa dolda** är på (se 5).
   I båda fallen visas ett litet överstruket öga (ny vektor `ic_visibility_off.xml`, 16dp, tintad
   `?android:attr/textColorSecondary`) efter namnet (ny ImageView `hidden_icon` i row_contact.xml, GONE annars).
4. **Dölja/visa**:
   - Långtrycksmenyn på kontakt: `Dölj kontakt` resp. `Visa kontakt` (beroende på läge), placerad före `Ta bort`.
   - Redigeringsdialogen: CheckBox `Dold – visas bara vid sökning` (id `edit_hidden`).
   - Urvalsläget: urvalsraden görs om till `[N valda (weight 1)] [Åtgärder ▾] [Avbryt]`, där `Åtgärder` öppnar en
     `android.widget.PopupMenu` med `Markera alla`, `Dölj valda`, `Visa valda`, `Ta bort valda` (röd via SpannableString
     med ForegroundColorSpan `@color/destructive`). Dölj/Visa valda → `ContactStore.setHidden(Collection<String> ids,
     boolean)` (en save) → toast med plural ("%d kontakter doldes" / "%d kontakter visas igen") → urvalsläget avslutas →
     refreshPanes(). Ta bort valda som förut (bekräftelse).
5. **Meny-toggel `Visa dolda`** i toolbar-menyn (checkable, ej persistent, default av). När på: dolda kontakter visas i
   listan som vanligt (med ögon-ikonen), räknas i rubrikerna och kan markeras. Påverkar INTE dialern.
6. **Recents/namnuppslag** (ContactStore.findNameForCall) får fortsätta använda dolda kontakter.
7. **Import/export**: JSON v2 skriver `hidden`; import läser det. Bulkimport via text sätter hidden=false.
8. **Bakåtkompatibilitet**: gamla filer utan fältet → hidden=false.

## Implementation
- `ContactStore`: `List<Contact> getVisible(SortMode)` (= getAll utan hidden), `getFavorites` filtrerar bort hidden,
  `search(query, sort)` inkluderar hidden (sökning är den avsedda vägen), nytt `setHidden(Collection<String>, boolean)`.
  `matchNumber` (oanvänd) kan lämnas.
- `ContactsPane.buildRows`: när query tom → använd `showHidden ? getAll : getVisible`; när query icke-tom → `search`.
  Räkna rubriker på samma lista. `Markera alla` väljer de kontakter som för närvarande är synliga i listan.
- `DialPane.updateMatches`: använd `store.getVisible(...)` för ContactMatcher och `getFavorites` (som redan filtrerar).
- Strängar i `strings_contacts.xml` (svenska, åäö). README.md: nytt stycke om dolda kontakter.
- `tools/check.sh` → RESULT: PASS; `bash tools/jvmtest/run.sh` → PASSED (rör inte T9/ContactMatcher; om testet skapar
  Contact med 5/7-argskonstruktorn måste de fortsätta fungera – lägg hidden som ett extra fält med default false).
