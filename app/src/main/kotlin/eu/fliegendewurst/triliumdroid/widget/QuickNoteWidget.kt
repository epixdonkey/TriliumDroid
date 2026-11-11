package eu.fliegendewurst.triliumdroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.activity.main.MainActivity
import eu.fliegendewurst.triliumdroid.controller.MainController

/**
 * Standalone 1x1 home screen widget for instantly creating a quick note in today's journal.
 */
class QuickNoteWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray
	) {
		for (appWidgetId in appWidgetIds) {
			updateAppWidget(context, appWidgetManager, appWidgetId)
		}
	}

	companion object {
		fun updateAppWidget(
			context: Context,
			appWidgetManager: AppWidgetManager,
			appWidgetId: Int
		) {
			val views = RemoteViews(context.packageName, R.layout.widget_quick_note)
			views.setImageViewResource(R.id.widget_quick_note_icon, R.drawable.icon_color)
			views.setTextViewText(
				R.id.widget_quick_note_label,
				context.getString(R.string.widget_quick_note_label)
			)

			val intent = Intent(context, MainActivity::class.java).apply {
				action = MainController.ACTION_QUICK_NOTE
				flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
			}
			val pendingIntent = PendingIntent.getActivity(
				context,
				appWidgetId,
				intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			)
			views.setOnClickPendingIntent(R.id.widget_quick_note_root, pendingIntent)
			views.setOnClickPendingIntent(R.id.widget_quick_note_icon, pendingIntent)
			views.setOnClickPendingIntent(R.id.widget_quick_note_label, pendingIntent)

			appWidgetManager.updateAppWidget(appWidgetId, views)
		}
	}
}
