package com.gino.mywallet.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TaskStatus
import com.gino.mywallet.domain.AcademicCalendar
import com.gino.mywallet.domain.PeriodTimes
import com.gino.mywallet.domain.SemesterConfig
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.QuietButton
import com.gino.mywallet.ui.components.SectionTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WEEK_RANGE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

@Composable
fun PlansScreen(vm: AppViewModel) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val semester by vm.semester.collectAsStateWithLifecycle()
    val currentWeek = AcademicCalendar.currentWeek(semester)
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PlanEntity?>(null) }

    val selected = plans.find { it.id == selectedId } ?: plans.firstOrNull()
    val selectedPlanId = selected?.id

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val weekText = currentWeek?.let { "本週第${it}週／共 ${semester.weeks} 週" }
            ?: "今天在學期外（${semester.week1Monday} 起共 ${semester.weeks} 週）"
        Text(weekText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("計畫")
            QuietButton("新增計畫") { showCreate = true }
        }

        if (plans.isEmpty()) {
            Text(
                "尚未建立計畫。可用指令 plan.import 匯入整學期，或在此新增。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                plans.forEach { plan ->
                    FilterChip(
                        selected = plan.id == selectedPlanId,
                        onClick = { selectedId = plan.id },
                        label = { Text(plan.title.ifBlank { plan.id }) }
                    )
                }
            }
        }

        selected?.let { plan ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(plan.title, fontWeight = FontWeight.Bold)
                    Text(plan.id, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan.note.isNotBlank()) {
                        Text(plan.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { confirmDelete = plan }) {
                    Text("刪除", color = MaterialTheme.colorScheme.error)
                }
            }

            val planTasks = tasks.filter {
                it.planId == plan.id && it.status == TaskStatus.OPEN
            }
            val unscheduled = planTasks.filter { it.plannedAt == null }

            (1..semester.weeks).forEach { week ->
                val weekTasks = planTasks.filter { taskInWeek(it, week, semester) }
                WeekPlanCard(
                    week = week,
                    semester = semester,
                    isCurrent = week == currentWeek,
                    tasks = weekTasks
                )
            }

            if (unscheduled.isNotEmpty()) {
                SectionTitle("未排程")
                unscheduled.forEach { t ->
                    Text("· [${t.category}] ${t.title}", fontSize = 13.sp)
                }
            }
        }
    }

    if (showCreate) {
        CreatePlanDialog(
            onDismiss = { showCreate = false },
            onConfirm = { id, title ->
                vm.upsertPlan(id, title)
                selectedId = id
                showCreate = false
            }
        )
    }
    confirmDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除計畫") },
            text = { Text("將刪除「${plan.title}」，以及其中尚未完成的任務。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePlan(plan.id)
                        if (selectedId == plan.id) selectedId = null
                        confirmDelete = null
                    }
                ) { Text("確定刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun WeekPlanCard(
    week: Int,
    semester: SemesterConfig,
    isCurrent: Boolean,
    tasks: List<TaskEntity>
) {
    val start = AcademicCalendar.weekStart(week, semester)
    val end = AcademicCalendar.weekEnd(week, semester)
    val border = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(12.dp))
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "第${week}週" + if (isCurrent) " · 本週" else "",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${start.format(WEEK_RANGE_FMT)}–${end.format(WEEK_RANGE_FMT)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (tasks.isEmpty()) {
                Text("（無排程）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                tasks.sortedWith(compareBy<TaskEntity> { it.plannedAt ?: Long.MAX_VALUE })
                    .forEach { t ->
                        Text(
                            weekTaskLine(t),
                            fontSize = 13.sp
                        )
                    }
            }
        }
    }
}

@Composable
private fun CreatePlanDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, title: String) -> Unit
) {
    var id by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增計畫") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("id（如 114-1）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("標題") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(id, title.ifBlank { id }) },
                enabled = id.isNotBlank()
            ) { Text("建立") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun taskInWeek(task: TaskEntity, week: Int, semester: SemesterConfig): Boolean {
    val planned = task.plannedAt ?: return false
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(planned).atZone(zone).toLocalDate()
    return AcademicCalendar.weekOf(date, semester) == week
}

private fun weekTaskLine(task: TaskEntity): String {
    val zone = ZoneId.systemDefault()
    val plannedTag = task.plannedAt?.let { ms ->
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val wd = PeriodTimes.weekdayLabel(date.dayOfWeek.value)
        "預計週$wd"
    }
    val bits = listOfNotNull(
        plannedTag,
        if (task.isRepeatOnCooldown()) "冷卻中" else null
    )
    val suffix = if (bits.isEmpty()) "" else " · ${bits.joinToString(" · ")}"
    return "· [${task.category}] ${task.title}$suffix"
}
