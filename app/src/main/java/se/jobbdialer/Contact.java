package se.jobbdialer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A phone book entry. All string fields are non-null (empty when not set). */
final class Contact {

    private static final String J_ID = "id";
    private static final String J_FIRST = "firstName";
    private static final String J_LAST = "lastName";
    private static final String J_WORK = "workNumber";
    private static final String J_PRIVATE = "privateNumber";
    private static final String J_CATEGORY = "category";
    private static final String J_FAVORITE = "favorite";

    final String id;
    String firstName;
    String lastName;
    String workNumber;
    String privateNumber;
    /** Category name; "" = none (shown under "Övriga"). Never null. */
    String category;
    boolean favorite;

    Contact(String id, String firstName, String lastName, String workNumber, String privateNumber) {
        this(id, firstName, lastName, workNumber, privateNumber, "", false);
    }

    Contact(String id, String firstName, String lastName, String workNumber, String privateNumber,
            String category, boolean favorite) {
        this.id = isBlank(id) ? newId() : id.trim();
        this.firstName = trim(firstName);
        this.lastName = trim(lastName);
        this.workNumber = trim(workNumber);
        this.privateNumber = trim(privateNumber);
        this.category = trim(category);
        this.favorite = favorite;
    }

    static Contact create(String firstName, String lastName, String workNumber, String privateNumber) {
        return new Contact(newId(), firstName, lastName, workNumber, privateNumber);
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    /** "Förnamn Efternamn", trimmed; empty when the contact has no name. */
    String displayName() {
        return (firstName + " " + lastName).trim();
    }

    boolean hasWork() {
        return !workNumber.isEmpty();
    }

    boolean hasPrivate() {
        return !privateNumber.isEmpty();
    }

    boolean hasName() {
        return !firstName.isEmpty() || !lastName.isEmpty();
    }

    /** True when the contact has neither a name nor a number. */
    boolean isEmpty() {
        return !hasName() && Numbers.clean(workNumber).isEmpty() && Numbers.clean(privateNumber).isEmpty();
    }

    /** Copy with a fresh id (used when an imported id collides with an existing one). */
    Contact withNewId() {
        return new Contact(newId(), firstName, lastName, workNumber, privateNumber, category, favorite);
    }

    /** Copy with another category. */
    Contact withCategory(String newCategory) {
        return new Contact(id, firstName, lastName, workNumber, privateNumber, newCategory, favorite);
    }

    /** Copy with another favorite flag. */
    Contact withFavorite(boolean newFavorite) {
        return new Contact(id, firstName, lastName, workNumber, privateNumber, category, newFavorite);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put(J_ID, id);
        o.put(J_FIRST, firstName);
        o.put(J_LAST, lastName);
        o.put(J_WORK, workNumber);
        o.put(J_PRIVATE, privateNumber);
        o.put(J_CATEGORY, category);
        o.put(J_FAVORITE, favorite);
        return o;
    }

    /** Lenient parser; missing fields become empty strings, a missing id gets a new UUID. */
    static Contact fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        return new Contact(
                o.optString(J_ID, ""),
                o.optString(J_FIRST, ""),
                o.optString(J_LAST, ""),
                o.optString(J_WORK, ""),
                o.optString(J_PRIVATE, ""),
                o.optString(J_CATEGORY, ""),
                o.optBoolean(J_FAVORITE, false));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
