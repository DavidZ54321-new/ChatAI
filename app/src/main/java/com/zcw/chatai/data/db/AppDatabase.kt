package com.zcw.chatai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "chatai.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
