package com.riad.rrlkr.metadata;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

public class SmsCollector {

    private final Context context;
    private final MetadataDatabase db;

    public SmsCollector(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void collect() {
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, null, null, null,
                Telephony.Sms.DATE + " DESC")) {
            if (cursor == null) return;
            int colAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int colBody = cursor.getColumnIndex(Telephony.Sms.BODY);
            int colDate = cursor.getColumnIndex(Telephony.Sms.DATE);
            int colType = cursor.getColumnIndex(Telephony.Sms.TYPE);
            while (cursor.moveToNext()) {
                String address = cursor.getString(colAddress);
                String body = cursor.getString(colBody);
                String date = cursor.getString(colDate);
                String typeRaw = cursor.getString(colType);
                String type;
                switch (Integer.parseInt(typeRaw)) {
                    case Telephony.Sms.MESSAGE_TYPE_INBOX: type = "RECEIVED"; break;
                    case Telephony.Sms.MESSAGE_TYPE_SENT: type = "SENT"; break;
                    case Telephony.Sms.MESSAGE_TYPE_DRAFT: type = "DRAFT"; break;
                    default: type = "OTHER"; break;
                }
                db.insertSms(address, body, date, type);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
}
