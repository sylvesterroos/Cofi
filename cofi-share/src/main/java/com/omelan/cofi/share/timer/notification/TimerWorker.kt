package com.omelan.cofi.share.timer.notification

import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omelan.cofi.share.CombineWeight
import com.omelan.cofi.share.DataStore
import com.omelan.cofi.share.model.AppDatabase
import com.omelan.cofi.share.model.Recipe
import com.omelan.cofi.share.model.Step
import com.omelan.cofi.share.model.StepType
import com.omelan.cofi.share.utils.roundToDecimals
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private fun List<Step>.findNextId(currentStep: Step) = try {
    this[this.indexOf(currentStep) + 1].id
} catch (e: IndexOutOfBoundsException) {
    null
}

class TimerWorker(private val context: Context, private val workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private fun tickerFlow(duration: Long, step: Duration = 500.milliseconds) = flow {
        val stepMs = step.inWholeMilliseconds
        emit(0)
        for (i in 1L..(duration / stepMs)) {
            delay(step)
            emit(i * stepMs)
        }
        val rest = duration % stepMs
        if (rest > 0) {
            delay(rest)
            emit(duration)
        }
    }

    private suspend fun postDoneNotification(recipe: Recipe): Data {
        val progressData = workDataOf(
            WORKER_PROGRESS_STEP to null,
            WORKER_PROGRESS_PROGRESS to 0f,
            WORKER_PROGRESS_IS_PAUSED to false,
        )
        setProgress(progressData)
        postTimerNotification(
            context,
            createDoneNotification(recipe, context),
            id = COFI_TIMER_NOTIFICATION_ID,
            tag = COFI_TIMER_DONE_NOTIFICATION_TAG,
        )
        return progressData
    }

    private fun cancelStepNotifications(steps: List<Step>, exceptStepId: Int? = null) {
        val notificationManager = NotificationManagerCompat.from(context)
        steps.filter { it.id != exceptStepId }.forEach {
            notificationManager.cancel(
                COFI_TIMER_NOTIFICATION_TAG,
                COFI_TIMER_NOTIFICATION_ID + it.id,
            )
        }
    }

    /**
     *  TODO: DRY [Timer.rememberAlreadyDoneWeight]
     */
    private fun calculateAlreadyDoneWeight(
        indexOfCurrentStep: Int,
        steps: List<Step>,
        weightMultiplier: Float,
        combineWeight: String,
    ): Float {
        val doneSteps = if (indexOfCurrentStep == -1) {
            listOf()
        } else {
            steps.subList(0, indexOfCurrentStep)
        }
        return when (combineWeight) {
            CombineWeight.ALL.name ->
                (doneSteps.sumOf { it.value?.toDouble() ?: 0.0 } * weightMultiplier)
                    .toFloat().roundToDecimals()

            CombineWeight.WATER.name -> (
                doneSteps.sumOf {
                    if (it.type === StepType.WATER && it.value != null) {
                        it.value.toDouble()
                    } else {
                        0.0
                    }
                } * weightMultiplier
                ).toFloat().roundToDecimals()

            else -> 0f
        }
    }

    override suspend fun doWork(): Result = coroutineScope {
        val valueMap = workerParams.inputData.keyValueMap
        val recipeId = valueMap[COFI_TIMER_NOTIFICATION_RECIPE_ID] as Int
        val startingStepId = valueMap[COFI_TIMER_NOTIFICATION_CURRENT_STEP_ID] as Int?
        val startingRealtime = valueMap[COFI_TIMER_NOTIFICATION_START_TIME_DATA] as Long
        val startingProgress = valueMap[COFI_TIMER_NOTIFICATION_PROGRESS] as Float? ?: 0f
        val weightMultiplier = valueMap[COFI_TIMER_NOTIFICATION_WEIGHT_MULTIPLIER] as Float? ?: 1f
        val timeMultiplier = valueMap[COFI_TIMER_NOTIFICATION_TIME_MULTIPLIER] as Float? ?: 1f
        val action = valueMap[COFI_TIMER_NOTIFICATION_ACTION] as String?
        val db = AppDatabase.getInstance(context)
        val recipe = db.recipeDao().get(recipeId).asFlow().first()
            ?: return@coroutineScope Result.failure()
        val steps = db.stepDao().getStepsForRecipe(recipeId).asFlow().first()

        if (action == TimerActions.Actions.ACTION_STOP.name) {
            cancelStepNotifications(steps)
            return@coroutineScope Result.failure()
        }
        cancelStepNotifications(steps, exceptStepId = startingStepId)

        if (startingStepId == null) {
            return@coroutineScope Result.success(postDoneNotification(recipe))
        }
        val initialStepIndex = steps.indexOfFirst { it.id == startingStepId }
        if (initialStepIndex == -1) {
            return@coroutineScope Result.failure()
        }

        val combineWeight = DataStore(context).getWeightSetting().first()

        suspend fun postNotificationWithProgress(
            step: Step,
            progress: Float,
            isPaused: Boolean = false,
        ): Data {
            val progressData = workDataOf(
                WORKER_PROGRESS_STEP to step.id,
                WORKER_PROGRESS_PROGRESS to progress,
                WORKER_PROGRESS_IS_PAUSED to isPaused,
                WORKER_PROGRESS_WEIGHT_MULTIPLIER to weightMultiplier,
                WORKER_PROGRESS_TIME_MULTIPLIER to timeMultiplier,
            )
            setProgress(progressData)
            postTimerNotification(
                context,
                step.toNotificationBuilder(
                    context,
                    recipe = recipe,
                    weightMultiplier = weightMultiplier,
                    timeMultiplier = timeMultiplier,
                    nextStepId = steps.findNextId(step),
                    currentProgress = progress,
                    isPaused = isPaused,
                    alreadyDoneWeight = calculateAlreadyDoneWeight(
                        steps.indexOf(step),
                        steps,
                        weightMultiplier,
                        combineWeight,
                    ),
                ),
                id = COFI_TIMER_NOTIFICATION_ID + step.id,
                tag = COFI_TIMER_NOTIFICATION_TAG,
            )
            return progressData
        }

        if (action == TimerActions.Actions.ACTION_PAUSE.name) {
            return@coroutineScope Result.success(
                postNotificationWithProgress(steps[initialStepIndex], startingProgress, true),
            )
        }

        // The system may stop and later reschedule this worker with its original input,
        // so the time spent away is replayed over the steps instead of being counted once.
        var elapsedMillis = (SystemClock.elapsedRealtime() - startingRealtime).coerceAtLeast(0L)
        var stepProgress = startingProgress
        for (step in steps.drop(initialStepIndex)) {
            if (step.time == null) {
                return@coroutineScope Result.success(
                    postNotificationWithProgress(step, stepProgress, isPaused = true),
                )
            }
            val stepMillis = step.time.toLong() * timeMultiplier
            val remainingMillis = (stepMillis * (1 - stepProgress)).toLong()
            val skippedMillis = elapsedMillis.coerceAtMost(remainingMillis)
            elapsedMillis -= skippedMillis
            val millisToCount = remainingMillis - skippedMillis
            val progressBeforeCount = stepProgress + skippedMillis / stepMillis
            if (millisToCount > 0) {
                tickerFlow(millisToCount)
                    .distinctUntilChanged()
                    .buffer(1)
                    .collect { currentValue ->
                        if (currentValue < millisToCount) {
                            postNotificationWithProgress(
                                step,
                                progress = progressBeforeCount + currentValue / stepMillis,
                            )
                        }
                    }
            }
            NotificationManagerCompat.from(context)
                .cancel(COFI_TIMER_NOTIFICATION_TAG, COFI_TIMER_NOTIFICATION_ID + step.id)
            stepProgress = 0f
        }
        Result.success(postDoneNotification(recipe))
    }
}
