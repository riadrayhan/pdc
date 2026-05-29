package com.riad.rrlkr.metadata;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SMS for bKash/Nagad/telecom recharge/ride-hailing events.
 */
public class SmsAnalyzer {

    private final Context context;
    private final MetadataDatabase db;

    private static final String[] BKASH_SENDERS = {"bKash", "16247", "01234016247"};
    private static final String[] NAGAD_SENDERS = {"Nagad", "16167", "01234016167"};
    private static final String[] UBER_SENDERS = {"Uber"};
    private static final String[] PATHAO_SENDERS = {"Pathao"};
    private static final String[] TELECOM_SENDERS = {
        "GP", "Grameenphone", "16800", "Robi", "16222",
        "Banglalink", "16616", "Airtel", "16746", "Teletalk", "16400"
    };

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?:Tk\\.?|BDT|Taka)\\s*[:\\.]?\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
        "(?:balance|bal|remaining)[:\\s]*(?:Tk\\.?|BDT)?\\s*([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TXN_ID_PATTERN = Pattern.compile(
        "(?:TrxID|Txn|Transaction\\s*(?:ID|No))[:\\s]*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:01[3-9]\\d{8})");

    public SmsAnalyzer(Context context) {
        this.context = context;
        this.db = MetadataDatabase.getInstance(context);
    }

    public void analyze() {
        try (Cursor cursor = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, null, null, null, Telephony.Sms.DATE + " DESC")) {
            if (cursor == null) return;
            int colAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS);
            int colBody = cursor.getColumnIndex(Telephony.Sms.BODY);
            int colDate = cursor.getColumnIndex(Telephony.Sms.DATE);
            while (cursor.moveToNext()) {
                String address = cursor.getString(colAddress);
                String body = cursor.getString(colBody);
                String date = cursor.getString(colDate);
                if (address == null || body == null) continue;
                String upperBody = body.toUpperCase();
                if (matches(address, BKASH_SENDERS) || upperBody.contains("BKASH")) {
                    parseMobileMoneyTransaction("bKash", address, body, date);
                } else if (matches(address, NAGAD_SENDERS) || upperBody.contains("NAGAD")) {
                    parseMobileMoneyTransaction("Nagad", address, body, date);
                } else if (matches(address, UBER_SENDERS) || upperBody.contains("UBER")) {
                    parseRideTransaction("Uber", address, body, date);
                } else if (matches(address, PATHAO_SENDERS) || upperBody.contains("PATHAO")) {
                    parseRideTransaction("Pathao", address, body, date);
                } else if (matches(address, TELECOM_SENDERS) || isTelecomRecharge(body)) {
                    parseTelecomTransaction(address, body, date);
                }
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    private boolean matches(String address, String[] senders) {
        for (String s : senders) if (address.toUpperCase().contains(s.toUpperCase())) return true;
        return false;
    }

    private boolean isTelecomRecharge(String body) {
        String u = body.toUpperCase();
        return u.contains("RECHARGE") || u.contains("TOP-UP") || u.contains("TOPUP")
            || u.contains("RECHARGED") || u.contains("BUNDLE") || u.contains("PACK")
            || (u.contains("BALANCE") && (u.contains("GP") || u.contains("ROBI")
            || u.contains("BANGLALINK") || u.contains("AIRTEL") || u.contains("TELETALK")));
    }

    private void parseMobileMoneyTransaction(String provider, String sender, String body, String date) {
        db.insertMobileMoney(provider, detectMobileMoneyType(body), extractAmount(body),
            extractBalance(body), extractTxnId(body), extractPhone(body),
            sender, body, formatTimestamp(date));
    }

    private String detectMobileMoneyType(String body) {
        String u = body.toUpperCase();
        if (u.contains("CASH IN")) return "CASH_IN";
        if (u.contains("CASH OUT")) return "CASH_OUT";
        if (u.contains("SEND MONEY") || u.contains("SENT")) return "SEND_MONEY";
        if (u.contains("RECEIVED") || u.contains("RECEIVE")) return "RECEIVE_MONEY";
        if (u.contains("PAYMENT") || u.contains("PAY")) return "PAYMENT";
        if (u.contains("BILL") || u.contains("BILL PAY")) return "BILL_PAY";
        if (u.contains("MERCHANT")) return "MERCHANT_PAYMENT";
        if (u.contains("RECHARGE")) return "MOBILE_RECHARGE";
        if (u.contains("ADD MONEY")) return "ADD_MONEY";
        if (u.contains("WITHDRAW")) return "WITHDRAW";
        if (u.contains("SALARY")) return "SALARY";
        if (u.contains("REMITTANCE")) return "REMITTANCE";
        return "OTHER";
    }

    private void parseRideTransaction(String provider, String sender, String body, String date) {
        String trip = body.length() > 200 ? body.substring(0, 200) : body;
        db.insertRideHailing(provider, detectRideType(body), extractAmount(body),
            trip, sender, formatTimestamp(date));
    }

    private String detectRideType(String body) {
        String u = body.toUpperCase();
        if (u.contains("COMPLETED") || u.contains("TRIP")) return "TRIP_COMPLETED";
        if (u.contains("CANCEL")) return "CANCELLED";
        if (u.contains("PROMO") || u.contains("DISCOUNT")) return "PROMO";
        if (u.contains("OTP") || u.contains("CODE")) return "VERIFICATION";
        if (u.contains("FOOD") || u.contains("DELIVERY")) return "DELIVERY";
        return "OTHER";
    }

    private void parseTelecomTransaction(String sender, String body, String date) {
        db.insertTelecomUsage(detectOperator(sender, body), detectRechargeType(body),
            extractAmount(body), extractBalance(body), sender, body, formatTimestamp(date));
    }

    private String detectRechargeType(String body) {
        String u = body.toUpperCase();
        if (u.contains("RECHARGE") || u.contains("TOP-UP") || u.contains("TOPUP")) return "RECHARGE";
        if (u.contains("BUNDLE") || u.contains("PACK") || u.contains("INTERNET")) return "BUNDLE_PURCHASE";
        if (u.contains("BONUS")) return "BONUS";
        if (u.contains("EXPIRE")) return "EXPIRY_NOTICE";
        if (u.contains("BALANCE")) return "BALANCE_INFO";
        return "OTHER";
    }

    private String detectOperator(String sender, String body) {
        String c = (sender + " " + body).toUpperCase();
        if (c.contains("GP") || c.contains("GRAMEENPHONE")) return "Grameenphone";
        if (c.contains("ROBI")) return "Robi";
        if (c.contains("BANGLALINK")) return "Banglalink";
        if (c.contains("AIRTEL")) return "Airtel";
        if (c.contains("TELETALK")) return "Teletalk";
        return "Unknown";
    }

    private String extractAmount(String body) {
        Matcher m = AMOUNT_PATTERN.matcher(body);
        return m.find() ? m.group(1).replace(",", "") : "";
    }

    private String extractBalance(String body) {
        Matcher m = BALANCE_PATTERN.matcher(body);
        return m.find() ? m.group(1).replace(",", "") : "";
    }

    private String extractTxnId(String body) {
        Matcher m = TXN_ID_PATTERN.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private String extractPhone(String body) {
        Matcher m = PHONE_PATTERN.matcher(body);
        return m.find() ? m.group(0) : "";
    }

    private String formatTimestamp(String dateMs) {
        try {
            long ms = Long.parseLong(dateMs);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
        } catch (Exception e) { return dateMs; }
    }
}
