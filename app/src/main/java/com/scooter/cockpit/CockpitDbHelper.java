package com.scooter.cockpit;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class CockpitDbHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "scooter_cockpit.db";
    private static final int DATABASE_VERSION = 2;

    public static final String VariablesTable = "keyValuePair";
    public static final String COL_Id = "id";
    public static final String COL_Key = "keyCol";
    public static final String COL_Value = "value";

    public static final String MyTripsTable = "ScooterTrips";
    public static final String COL_TripHistoryID = "id";
    public static final String COL_LAT_ST = "latitudeST";
    public static final String COL_LNG_ST = "longitudeST";
    public static final String COL_ALT_ST = "altitudeST";
    public static final String COL_LAT_EN = "latitudeEN";
    public static final String COL_LNG_EN = "longitudeEN";
    public static final String COL_ALT_EN = "altitudeEN";

    public static final String COL_Start_TIMESTAMP = "StartTimestamp";
    public static final String COL_End_TIMESTAMP = "EndTimestamp";
    public static final String COL_DISTANCE_KM = "distance_km";
    public static final String COL_MAX_SPEED = "max_speed";
    public static final String COL_DURATION_SEC = "duration_sec";

    private static final String SQL_CREATE_RIDES =
            "CREATE TABLE " + MyTripsTable + " (" +
                    COL_TripHistoryID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_Start_TIMESTAMP + " INTEGER NOT NULL, " +
                    COL_End_TIMESTAMP + " INTEGER, " +
                    COL_DISTANCE_KM + " REAL NOT NULL, " +
                    COL_LAT_ST + " REAL, " +
                    COL_LNG_ST + " REAL, " +
                    COL_ALT_ST + " REAL, " +
                    COL_LAT_EN + " REAL, " +
                    COL_LNG_EN + " REAL, " +
                    COL_ALT_EN + " REAL, " +
                    COL_MAX_SPEED + " INTEGER NOT NULL, " +
                    COL_DURATION_SEC + " INTEGER)";

    private static final String SQL_CREATE_KEY_VALUE_PAIR =
            "CREATE TABLE " + VariablesTable + " (" +
                    COL_Id + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_Key + " TEXT UNIQUE NOT NULL, " +
                    COL_Value + " TEXT NOT NULL)";

    private static CockpitDbHelper instance;

    public static synchronized CockpitDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new CockpitDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private CockpitDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public boolean isTableExists(SQLiteDatabase db, String tableName) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{tableName}
            );
            return cursor.getCount() > 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        if (!isTableExists(db, MyTripsTable)) {
            db.execSQL(SQL_CREATE_RIDES);
        }
        if (!isTableExists(db, VariablesTable)) {
            db.execSQL(SQL_CREATE_KEY_VALUE_PAIR);
            insertInitialKeyValue(db, "ODO", "0");
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + VariablesTable);
        db.execSQL("DROP TABLE IF EXISTS " + MyTripsTable);
        onCreate(db);
    }

    private void insertInitialKeyValue(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put(COL_Key, key);
        values.put(COL_Value, value);
        db.insertWithOnConflict(VariablesTable, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean EditKeyValue(String key, String value) {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            ContentValues values = new ContentValues();
            values.put(COL_Key, key);
            values.put(COL_Value, value);

            long rowId = db.insertWithOnConflict(
                    VariablesTable, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            return rowId != -1;
        } catch (Exception e) {
            return false;
        }
    }

    public String GetValueByKey(String key) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            String sql = "SELECT " + COL_Value + " FROM " + VariablesTable +
                    " WHERE " + COL_Key + " = ? LIMIT 1";
            cursor = db.rawQuery(sql, new String[]{key});
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public boolean startTrip(int maxSpeed, double lat, double lng, double alt) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_MAX_SPEED, Math.max(maxSpeed, 0));
            values.put(COL_Start_TIMESTAMP, System.currentTimeMillis());
            values.put(COL_DISTANCE_KM, 0);
            values.put(COL_ALT_ST, alt);
            values.put(COL_LAT_ST, lat);
            values.put(COL_LNG_ST, lng);
            values.put(COL_DURATION_SEC, 0);

            db.insert(MyTripsTable, null, values);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String[] getTripInfo() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            String sql = "SELECT * FROM " + MyTripsTable +
                    " WHERE " + COL_DURATION_SEC + " = 0" +
                    " ORDER BY " + COL_TripHistoryID + " DESC LIMIT 1";

            cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                String[] ret = new String[2];
                ret[0] = cursor.getString(cursor.getColumnIndexOrThrow(COL_DISTANCE_KM));
                ret[1] = cursor.getString(cursor.getColumnIndexOrThrow(COL_Start_TIMESTAMP));
                return ret;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public int UpdateTrip(int speed, int distance, double lat, double lng, double alt) {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = null;
        try {
            String sql = "SELECT * FROM " + MyTripsTable +
                    " WHERE " + COL_DURATION_SEC + " = 0" +
                    " ORDER BY " + COL_TripHistoryID + " DESC LIMIT 1";

            cursor = db.rawQuery(sql, null);
            if (cursor.getCount() == 0) {
                startTrip(speed, lat, lng, alt);
            } else if (cursor.moveToFirst()) {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TripHistoryID));
                double distanceOld = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE_KM));
                int maxSpeed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MAX_SPEED));

                ContentValues values = new ContentValues();
                values.put(COL_DISTANCE_KM, distanceOld + distance);
                values.put(COL_MAX_SPEED, Math.max(speed, maxSpeed));

                String whereClause = COL_TripHistoryID + " = ?";
                String[] whereArgs = new String[]{String.valueOf(id)};

                return db.update(MyTripsTable, values, whereClause, whereArgs);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    public void endTrip(int speed, int distance, long endTimeMillis, double lat, double lng, double alt) {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = null;
        try {
            String sql = "SELECT * FROM " + MyTripsTable +
                    " WHERE " + COL_DURATION_SEC + " = 0" +
                    " ORDER BY " + COL_TripHistoryID + " DESC LIMIT 1";

            cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TripHistoryID));
                double distanceOld = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_DISTANCE_KM));
                int maxSpeed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_MAX_SPEED));
                long startTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_Start_TIMESTAMP));

                ContentValues values = new ContentValues();
                values.put(COL_DISTANCE_KM, distanceOld + distance);
                values.put(COL_MAX_SPEED, Math.max(speed, maxSpeed));
                values.put(COL_LNG_EN, lng);
                values.put(COL_LAT_EN, lat);
                values.put(COL_ALT_EN, alt);
                values.put(COL_End_TIMESTAMP, endTimeMillis);
                values.put(COL_DURATION_SEC, Math.max(0L, (endTimeMillis - startTime) / 1000L));

                String whereClause = COL_TripHistoryID + " = ?";
                String[] whereArgs = new String[]{String.valueOf(id)};

                db.update(MyTripsTable, values, whereClause, whereArgs);
            }
            startTrip(speed, lat, lng, alt);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}