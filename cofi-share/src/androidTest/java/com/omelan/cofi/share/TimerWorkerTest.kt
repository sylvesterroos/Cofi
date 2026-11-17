package com.omelan.cofi.share

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.omelan.cofi.share.model.AppDatabase
import com.omelan.cofi.share.model.Recipe
import com.omelan.cofi.share.model.Step
import com.omelan.cofi.share.model.StepType
import com.omelan.cofi.share.timer.notification.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimerWorkerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val recipeId = 1
    private val firstStepId = 10
    private val secondStepId = 11
    private val userInputStepId = 12
    private val lastUserInputStepId = 13

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        notificationManager.cancelAll()
        runBlocking {
            val db = AppDatabase.getInstance(context, createWithData = false)
            db.recipeDao().deleteAll()
            db.stepDao().deleteAll()
            db.recipeDao().insertRecipe(Recipe(id = recipeId, name = "Test"))
        }
    }

    private suspend fun insertSteps(vararg steps: Pair<Int, Int?>) {
        AppDatabase.getInstance(context, createWithData = false).stepDao().insertAll(
            steps.mapIndexed { index, (id, time) ->
                Step(
                    id = id,
                    recipeId = recipeId,
                    orderInRecipe = index,
                    name = "Step $id",
                    time = time,
                    type = StepType.WAIT,
                )
            },
        )
    }

    private fun input(
        stepId: Int?,
        elapsedMillis: Long = 0,
        action: TimerActions.Actions? = null,
        progress: Float = 0f,
    ) = workDataOf(
        COFI_TIMER_NOTIFICATION_RECIPE_ID to recipeId,
        COFI_TIMER_NOTIFICATION_CURRENT_STEP_ID to stepId,
        COFI_TIMER_NOTIFICATION_START_TIME_DATA to SystemClock.elapsedRealtime() - elapsedMillis,
        COFI_TIMER_NOTIFICATION_PROGRESS to progress,
        COFI_TIMER_NOTIFICATION_WEIGHT_MULTIPLIER to 1f,
        COFI_TIMER_NOTIFICATION_TIME_MULTIPLIER to 1f,
        COFI_TIMER_NOTIFICATION_ACTION to action?.name,
    )

    private fun run(data: Data): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TimerWorker>(context).setInputData(data).build().doWork()
    }

    private fun outputOf(result: ListenableWorker.Result): Data =
        (result as ListenableWorker.Result.Success).outputData

    private fun activeStepNotificationIds() = notificationManager.activeNotifications
        .filter { it.tag == COFI_TIMER_NOTIFICATION_TAG }
        .map { it.id - COFI_TIMER_NOTIFICATION_ID }
        .sorted()

    private fun isDoneNotificationActive() = notificationManager.activeNotifications
        .any { it.tag == COFI_TIMER_DONE_NOTIFICATION_TAG }

    private fun <T> assertEventually(expected: T, actual: () -> T) {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_SETTLE_MILLIS
        while (actual() != expected && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(expected, actual())
    }

    private fun assertActiveStepNotifications(vararg stepIds: Int) =
        assertEventually(stepIds.sorted()) { activeStepNotificationIds() }

    private fun assertDoneNotification(isActive: Boolean) =
        assertEventually(isActive) { isDoneNotificationActive() }

    @Test
    fun timeAwayIsReplayedUntilTheUserInputStep() {
        runBlocking {
            insertSteps(firstStepId to 1000, secondStepId to 1000, userInputStepId to null)
        }

        val output = outputOf(run(input(firstStepId, elapsedMillis = 5000)))

        assertEquals(userInputStepId, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertTrue(output.getBoolean(WORKER_PROGRESS_IS_PAUSED, false))
        assertActiveStepNotifications(userInputStepId)
    }

    @Test
    fun timeAwayLongerThanTheRecipeFinishesIt() {
        runBlocking { insertSteps(firstStepId to 1000, secondStepId to 1000) }

        val output = outputOf(run(input(firstStepId, elapsedMillis = 60_000)))

        assertEquals(-1, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertDoneNotification(true)
        assertActiveStepNotifications()
    }

    @Test
    fun partOfTheFirstStepIsCountedFromTheElapsedTime() {
        runBlocking { insertSteps(firstStepId to 4000, secondStepId to null) }

        val output = outputOf(run(input(firstStepId, elapsedMillis = 3000)))

        assertEquals(secondStepId, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertDoneNotification(false)
    }

    @Test
    fun liveCountdownRunsThroughAllSteps() {
        runBlocking { insertSteps(firstStepId to 1200, secondStepId to 1200) }

        val output = outputOf(run(input(firstStepId)))

        assertEquals(-1, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertDoneNotification(true)
        assertActiveStepNotifications()
    }

    @Test
    fun pauseKeepsProgressAndPostsTheStepNotification() {
        runBlocking {
            insertSteps(firstStepId to 10_000, secondStepId to 1000)
        }

        val output =
            outputOf(
                run(
                    input(firstStepId, action = TimerActions.Actions.ACTION_PAUSE, progress = 0.4f),
                ),
            )

        assertEquals(firstStepId, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertEquals(0.4f, output.getFloat(WORKER_PROGRESS_PROGRESS, -1f), 0.0001f)
        assertActiveStepNotifications(firstStepId)
    }

    @Test
    fun stopRemovesEveryStepNotification() {
        runBlocking { insertSteps(firstStepId to 10_000, userInputStepId to null) }
        run(input(firstStepId, action = TimerActions.Actions.ACTION_PAUSE))
        assertActiveStepNotifications(firstStepId)

        val result = run(input(-1, action = TimerActions.Actions.ACTION_STOP))

        assertTrue(result is ListenableWorker.Result.Failure)
        assertActiveStepNotifications()
    }

    @Test
    fun continuingFromAUserInputStepRemovesItsNotification() {
        runBlocking {
            insertSteps(
                firstStepId to 1000,
                userInputStepId to null,
                lastUserInputStepId to null,
            )
        }
        run(input(firstStepId, elapsedMillis = 5000))
        assertActiveStepNotifications(userInputStepId)

        run(input(lastUserInputStepId, action = TimerActions.Actions.ACTION_NEXT))

        assertActiveStepNotifications(lastUserInputStepId)
    }

    @Test
    fun continuingFromTheLastUserInputStepPostsTheDoneNotification() {
        runBlocking { insertSteps(firstStepId to 1000, userInputStepId to null) }
        run(input(firstStepId, elapsedMillis = 5000))

        val output = outputOf(run(input(null, action = TimerActions.Actions.ACTION_NEXT)))

        assertEquals(-1, output.getInt(WORKER_PROGRESS_STEP, -1))
        assertDoneNotification(true)
        assertActiveStepNotifications()
    }

    private companion object {
        const val NOTIFICATION_SETTLE_MILLIS = 3000L
        const val POLL_MILLIS = 50L
    }
}
