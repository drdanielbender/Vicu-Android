package com.rendyhd.vicu.widget

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.rendyhd.vicu.MainActivity
import com.rendyhd.vicu.R
import com.rendyhd.vicu.domain.model.OccurrenceStatus
import com.rendyhd.vicu.domain.repository.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext

class RoutineWidget : GlanceAppWidget() {
    companion object {
        private val COMPACT = DpSize(160.dp, 64.dp)
        private val LARGE = DpSize(250.dp, 180.dp)
    }

    override val stateDefinition = RoutineWidgetStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val state = RoutineWidgetStateDefinition.parseState(prefs)
            GlanceTheme {
                if (androidx.glance.LocalSize.current.height < 120.dp) {
                    CompactRoutineWidget(state)
                } else {
                    LargeRoutineWidget(state)
                }
            }
        }
    }

    suspend fun updateAllWidgets(context: Context) {
        val repository = GlobalContext.get().get<RoutineRepository>()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        val state = RoutineWidgetState.from(repository.observeDay(today).first())
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(RoutineWidget::class.java).forEach { glanceId ->
            updateState(context, glanceId, state)
            update(context, glanceId)
        }
    }

    suspend fun updateState(
        context: Context,
        glanceId: GlanceId,
        state: RoutineWidgetState,
    ) {
        updateAppWidgetState(context, RoutineWidgetStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[RoutineWidgetStateDefinition.KEY_STATE] =
                    RoutineWidgetStateDefinition.encodeState(state)
            }
        }
    }
}

class OpenRoutinesWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to_view_type", "ROUTINES")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }
}

class ToggleRoutineWidgetAction : ActionCallback, KoinComponent {
    companion object {
        val RoutineIdKey = ActionParameters.Key<String>("routine_id")
        val DateKey = ActionParameters.Key<String>("date")
        val SlotIdKey = ActionParameters.Key<String>("slot_id")
        val CompletedKey = ActionParameters.Key<Boolean>("completed")
    }

    private val repository: RoutineRepository by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val routineId = parameters[RoutineIdKey] ?: return
        val date = parameters[DateKey] ?: return
        val slotId = parameters[SlotIdKey] ?: return
        val status = if (parameters[CompletedKey] == true) OccurrenceStatus.PENDING else OccurrenceStatus.COMPLETED

        val widget = RoutineWidget()
        updateAppWidgetState(context, RoutineWidgetStateDefinition, glanceId) { prefs ->
            val current = RoutineWidgetStateDefinition.parseState(prefs)
            val updated = current.withStatus(routineId, date, slotId, status)
            prefs.toMutablePreferences().apply {
                this[RoutineWidgetStateDefinition.KEY_STATE] =
                    RoutineWidgetStateDefinition.encodeState(updated)
            }
        }
        widget.update(context, glanceId)

        repository.setOccurrenceStatus(routineId, date, slotId, status)
        widget.updateAllWidgets(context)
    }
}

private val healthColor = ColorProvider(day = Color(0xFF247D61), night = Color(0xFF69D6AD))

@Composable
private fun CompactRoutineWidget(state: RoutineWidgetState) {
    val next = state.occurrences.firstOrNull { it.status == OccurrenceStatus.PENDING }
        ?: state.occurrences.firstOrNull()
    Row(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(14.dp)
            .clickable(actionRunCallback<OpenRoutinesWidgetAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "Routines  ${state.completedCount}/${state.scheduledCount}",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                maxLines = 1,
            )
            Text(
                text = next?.routineName ?: "All clear today",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
        if (next != null) RoutineWidgetCheckbox(next)
    }
}

@Composable
private fun LargeRoutineWidget(state: RoutineWidgetState) {
    Column(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground).padding(16.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionRunCallback<OpenRoutinesWidgetAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today’s routines",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "${state.completedCount}/${state.scheduledCount}",
                style = TextStyle(color = healthColor, fontWeight = FontWeight.Bold, fontSize = 14.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (state.occurrences.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing scheduled", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(
                    state.occurrences,
                    itemId = { "${it.key}:${it.status}".hashCode().toLong() },
                ) { occurrence ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoutineWidgetCheckbox(occurrence)
                        Spacer(GlanceModifier.width(10.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                occurrence.routineName,
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                                maxLines = 1,
                            )
                            Text(
                                occurrence.slotLabel,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineWidgetCheckbox(occurrence: RoutineWidgetItem) {
    val completed = occurrence.status == OccurrenceStatus.COMPLETED
    Box(
        modifier = GlanceModifier.size(38.dp).cornerRadius(19.dp).clickable(
            actionRunCallback<ToggleRoutineWidgetAction>(
                actionParametersOf(
                    ToggleRoutineWidgetAction.RoutineIdKey to occurrence.routineId,
                    ToggleRoutineWidgetAction.DateKey to occurrence.scheduledDate,
                    ToggleRoutineWidgetAction.SlotIdKey to occurrence.slotId,
                    ToggleRoutineWidgetAction.CompletedKey to completed,
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (completed) R.drawable.ic_widget_circle_checked else R.drawable.ic_widget_circle_unchecked),
            contentDescription = if (completed) "Undo" else "Complete",
            modifier = GlanceModifier.size(24.dp),
            colorFilter = if (completed) null else ColorFilter.tint(healthColor),
        )
    }
}
