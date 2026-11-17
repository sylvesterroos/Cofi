package com.omelan.cofi.share.model

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite supports a limited operations for ALTER.
        // Create the new table
        db.execSQL(
            "CREATE TABLE Recipe_new (" +
                "id INTEGER NOT NULL," +
                "name TEXT NOT NULL," +
                "description TEXT NOT NULL," +
                "last_finished INTEGER NOT NULL," +
                "icon TEXT NOT NULL," +
                "PRIMARY KEY(id))",
        )
        // Copy the data
        db.execSQL(
            "INSERT INTO Recipe_new (id, name, description, last_finished, icon) " +
                "SELECT id, name, description, last_finished, 'grinder' " +
                "FROM Recipe",
        )
        // Remove the old table
        db.execSQL("DROP TABLE Recipe")
        // Change the table name to the correct one
        db.execSQL("ALTER TABLE Recipe_new RENAME TO Recipe")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE step ADD COLUMN order_in_recipe INTEGER")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

@Database(
    version = 5,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
    entities = [Recipe::class, Step::class],
    exportSchema = true,
)
@TypeConverters(StepTypeConverter::class, RecipeIconTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao
    abstract fun recipeDao(): RecipeDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, createWithData: Boolean = true): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return it }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cofi-database.db",
                ).run {
                    if (createWithData) {
                        addCallback(
                            object : Callback() {
                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    super.onCreate(db)
                                    val data = PrepopulateData(context.applicationContext)
                                    data.recipes.forEach {
                                        val values = ContentValues().apply {
                                            put("id", it.id)
                                            put("name", it.name)
                                            put("description", it.description)
                                            put("last_finished", it.lastFinished)
                                            put(
                                                "icon",
                                                RecipeIconTypeConverter().recipeIconToString(
                                                    it.recipeIcon,
                                                ),
                                            )
                                        }
                                        db.insert(
                                            "recipe",
                                            SQLiteDatabase.CONFLICT_REPLACE,
                                            values,
                                        )
                                    }
                                    data.steps.forEach {
                                        val values = ContentValues().apply {
                                            put("id", it.id)
                                            put("recipe_id", it.recipeId)
                                            put("order_in_recipe", it.orderInRecipe)
                                            put("value", it.value)
                                            put("name", it.name)
                                            put("time", it.time)
                                            put("type", it.type.name)
                                        }
                                        db.insert(
                                            "step",
                                            SQLiteDatabase.CONFLICT_REPLACE,
                                            values,
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                        this
                    }
                }.addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance

                return instance
            }
        }
    }
}
