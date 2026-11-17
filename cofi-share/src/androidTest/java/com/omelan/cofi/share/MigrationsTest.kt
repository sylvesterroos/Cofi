package com.omelan.cofi.share

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omelan.cofi.share.model.ALL_MIGRATIONS
import com.omelan.cofi.share.model.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationsTest {
    private val testDBName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrateAllKeepsExistingRecipes() {
        helper.createDatabase(testDBName, 1).apply {
            execSQL(
                "INSERT INTO recipe (id, name, description, last_finished, icon_name) " +
                    "VALUES (1, 'V60', 'description', 0, 2131230000)",
            )
            execSQL(
                "INSERT INTO step (id, recipe_id, name, time, type, value) " +
                    "VALUES (1, 1, 'Water', 1000, 'WATER', 50)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDBName, 5, true, *ALL_MIGRATIONS)

        migrated.query("SELECT name, icon FROM recipe WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("V60", it.getString(0))
            assertEquals("grinder", it.getString(1))
        }
        migrated.query("SELECT name, order_in_recipe FROM step WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("Water", it.getString(0))
        }
    }
}
