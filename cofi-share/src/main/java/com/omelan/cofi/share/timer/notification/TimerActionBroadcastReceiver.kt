package com.omelan.cofi.share.timer.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.parcelize.Parcelize

@Parcelize
data class TimerData(
    val recipeId: Int,
    val stepId: Int?,
    val alreadyDoneProgress: Float,
    val weightMultiplier: Float,
    val timeMultiplier: Float,
) : Parcelable

private const val ACTION_DATA = "ACTION_DATA"

object TimerActions {
    enum class Actions {
        ACTION_PAUSE,
        ACTION_NEXT,
        ACTION_RESUME,
        ACTION_STOP,
    }

    fun createIntent(context: Context, notificationAction: Actions, timerData: TimerData): Intent {
        val actionIntent = Intent(context, TimerActionBroadcastReceiver::class.java).apply {
            action = notificationAction.name
        }
        actionIntent.putExtra(ACTION_DATA, timerData)
        return actionIntent
    }

    fun createPendingIntent(
        context: Context,
        notificationAction: Actions,
        timerData: TimerData,
    ): PendingIntent? = PendingIntent.getBroadcast(
        context,
        SystemClock.elapsedRealtime().toInt(),
        createIntent(context, notificationAction, timerData),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

class TimerActionBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerData = IntentCompat.getParcelableExtra(intent, ACTION_DATA, TimerData::class.java)
        if (timerData == null) {
            return
        }
        if (timerData.recipeId < 0) {
            Log.e("TimerActionReceiver", "Invalid recipeId ${timerData.recipeId}")
            return
        }
        val workManager = WorkManager.getInstance(context)

        val inputData = workDataOf(
            COFI_TIMER_NOTIFICATION_START_TIME_DATA to SystemClock.elapsedRealtime(),
            COFI_TIMER_NOTIFICATION_ACTION to intent.action,
            COFI_TIMER_NOTIFICATION_RECIPE_ID to timerData.recipeId,
            COFI_TIMER_NOTIFICATION_CURRENT_STEP_ID to timerData.stepId,
            COFI_TIMER_NOTIFICATION_PROGRESS to timerData.alreadyDoneProgress,
            COFI_TIMER_NOTIFICATION_WEIGHT_MULTIPLIER to timerData.weightMultiplier,
            COFI_TIMER_NOTIFICATION_TIME_MULTIPLIER to timerData.timeMultiplier,
        )
        val updatedWorkRequest = OneTimeWorkRequestBuilder<TimerWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "cofi_${timerData.recipeId}",
            ExistingWorkPolicy.REPLACE,
            updatedWorkRequest,
        )
    }
}
