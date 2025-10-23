package com.example.iot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log


class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "IOT_DB", null, 2) { // Tăng version lên 2

    companion object {
        private const val TABLE_NAME = "SensorData"
        private const val COL_ID = "id"
        private const val COL_TEMP = "temperature"
        private const val COL_HUM = "humidity"
        private const val COL_TIME = "time"
        private const val COL_WARNING_LEVEL = "warning_level"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEMP REAL,
                $COL_HUM REAL,
                $COL_TIME TEXT,
                $COL_WARNING_LEVEL TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertData(temp: Double, hum: Double, time: String, warningLevel: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TEMP, temp)
            put(COL_HUM, hum)
            put(COL_TIME, time)
            put(COL_WARNING_LEVEL, warningLevel)
        }
        db.insert(TABLE_NAME, null, values)
        db.close()
    }

    fun getAllData(): List<DataModel> {
        val dataList = mutableListOf<DataModel>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME ORDER BY id DESC", null)
        try {
            if (cursor.moveToFirst()) {
                do {
                    dataList.add(
                        DataModel(
                            id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID)),
                            temperature = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_TEMP)),
                            humidity = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_HUM)),
                            time = cursor.getString(cursor.getColumnIndexOrThrow(COL_TIME)),
                            warningLevel = cursor.getString(cursor.getColumnIndexOrThrow(COL_WARNING_LEVEL))
                        )
                    )
                } while (cursor.moveToNext())
            } else {
                Log.d("DatabaseHelper", "No data found in SensorData table")
            }
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error reading data from database: ${e.message}", e)
        } finally {
            cursor.close()
            db.close()
        }
        return dataList
    }
}