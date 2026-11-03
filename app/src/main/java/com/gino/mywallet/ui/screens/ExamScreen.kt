package com.gino.mywallet.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamItemStatus
import com.gino.mywallet.data.entity.ExamSubjectEntity
import com.gino.mywallet.domain.ExamRules
import com.gino.mywallet.domain.SubjectRemaining
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.PrimaryButton
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(vm: AppViewModel) {
    val items by vm.examItems.collectAsStateWithLifecycle()
    val subjects by vm.examSubjects.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var sheetSubject by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var expandedBucket by remember { mutableStateOf<String?>(null) }

    val notesBySubject = remember(subjects) {
        subjects.associate { it.subject to it.note }
    }

    LaunchedEffect(Unit) {
        vm.ensureActiveExam()
        vm.refreshExamReviews()
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
            vm.refreshExamReviews()
        }
    }

    val progress = ExamRules.progress(items)
    val queue = ExamRules.queueSorted(items)
    // 清單含已複習完成；初次讀過者以星星標示
    val scopeItems = ExamRules.visibleInScopeList(items) +
        items.filter { it.status == ExamItemStatus.DONE }
    val grouped = scopeItems
        .distinctBy { it.id }
        .groupBy { ExamRules.subjectKey(it) }
        .map { (subject, list) ->
            subject to list.sortedWith(
                compareBy<ExamItemEntity> { it.firstCompletedAt == null }
                    .thenBy { it.status == ExamItemStatus.DONE }
                    .thenBy { it.id }
            )
        }
        .sortedBy { it.first }

    fun openSheet(prefill: String? = null) {
        sheetSubject = prefill
        showAddSheet = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "考試",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProgressChip(
                    title = "初次",
                    value = "${progress.firstDone}/${progress.total}",
                    selected = expandedBucket == "first",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        expandedBucket = if (expandedBucket == "first") null else "first"
                    }
                )
                ProgressChip(
                    title = "複習",
                    value = "${progress.reviewDone}/${progress.total}",
                    selected = expandedBucket == "review",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        expandedBucket = if (expandedBucket == "review") null else "review"
                    }
                )
            }

            when (expandedBucket) {
                "first" -> SubjectRemainingDetail(ExamRules.firstRemainingBySubject(items))
                "review" -> SubjectRemainingDetail(ExamRules.reviewRemainingBySubject(items))
            }

            queue.forEachIndexed { index, item ->
                if (index == 0) {
                    NowReadingCard(
                        item = item,
                        onComplete = { vm.completeExamItem(item.id) },
                        onDequeue = { vm.dequeueExamItem(item.id) },
                        onMoveDown = { vm.moveExamQueue(item.id, 1) },
                        canMoveDown = queue.size > 1
                    )
                } else {
                    NextQueueRow(
                        item = item,
                        onDequeue = { vm.dequeueExamItem(item.id) },
                        onMoveUp = { vm.moveExamQueue(item.id, -1) },
                        onMoveDown = { vm.moveExamQueue(item.id, 1) },
                        canMoveUp = true,
                        canMoveDown = index < queue.lastIndex
                    )
                }
            }

            grouped.forEach { (subject, subjectItems) ->
                val courseNote = notesBySubject[subject].orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { openSheet(subject) }
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(subject, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (courseNote.isNotBlank()) {
                        Text(
                            courseNote,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                subjectItems.forEach { item ->
                    ScopeRow(
                        item = item,
                        now = now,
                        onEnqueue = { vm.enqueueExamItem(item.id) },
                        onDelete = { vm.deleteExamItem(item.id) }
                    )
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(40.dp)
                .clickable { openSheet(null) }
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, contentDescription = "新增任務")
            }
        }
    }

    if (showAddSheet) {
        AddExamCourseSheet(
            existingItems = items,
            existingSubjects = subjects,
            initialSubject = sheetSubject.orEmpty(),
            onDismiss = { showAddSheet = false },
            onSave = { subject, note, units ->
                vm.saveExamCourse(subject, note, units)
                showAddSheet = false
            }
        )
    }
}

@Composable
private fun ProgressChip(
    title: String,
    value: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SubjectRemainingDetail(rows: List<SubjectRemaining>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (rows.isEmpty()) {
            Text("—", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(row.subject, fontSize = 13.sp)
                    Text(
                        "${row.remaining}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun NowReadingCard(
    item: ExamItemEntity,
    onComplete: () -> Unit,
    onDequeue: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveDown: Boolean
) {
    val canComplete = item.status == ExamItemStatus.OPEN || item.status == ExamItemStatus.REVIEWABLE
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "現在讀",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(ExamRules.displayLabel(item), fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
            if (canMoveDown) {
                IconButton(onClick = onMoveDown) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移")
                }
            }
            if (canComplete) {
                IconButton(onClick = onComplete) {
                    Icon(Icons.Outlined.Check, contentDescription = "完成")
                }
            }
            IconButton(onClick = onDequeue) {
                Icon(Icons.Outlined.Close, contentDescription = "移出")
            }
        }
    }
}

@Composable
private fun NextQueueRow(
    item: ExamItemEntity,
    onDequeue: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            ExamRules.displayLabel(item),
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1
        )
        if (canMoveUp) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "上移",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (canMoveDown) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "下移",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onDequeue, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移出",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ScopeRow(
    item: ExamItemEntity,
    now: Long,
    onEnqueue: () -> Unit,
    onDelete: () -> Unit
) {
    val firstRead = item.firstCompletedAt != null || item.status != ExamItemStatus.OPEN
    val reviewed = item.status == ExamItemStatus.DONE
    val cooldown = item.status == ExamItemStatus.COOLDOWN
    val canEnqueue = ExamRules.canEnqueue(item) && item.queueOrder == null
    val rem = ExamRules.cooldownRemainingMs(item, now)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (firstRead) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "已初次讀過",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
            )
        }
        Text(
            item.title,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            maxLines = 1,
            color = if (reviewed) muted else MaterialTheme.colorScheme.onSurface
        )
        when {
            reviewed -> {
                Text(
                    "已複習",
                    fontSize = 11.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            cooldown && rem != null && rem > 0L -> {
                Text(
                    ExamRules.formatCountdownShort(rem),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = muted,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            canEnqueue -> {
                IconButton(onClick = onEnqueue, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistAdd,
                        contentDescription = "加入",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            else -> {
                Spacer(Modifier.size(36.dp))
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "刪除",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExamCourseSheet(
    existingItems: List<ExamItemEntity>,
    existingSubjects: List<ExamSubjectEntity>,
    initialSubject: String,
    onDismiss: () -> Unit,
    onSave: (subject: String, note: String, units: List<String>) -> Unit
) {
    var subject by remember(initialSubject) { mutableStateOf(initialSubject) }
    var note by remember(initialSubject) {
        mutableStateOf(existingSubjects.find { it.subject == initialSubject }?.note.orEmpty())
    }
    var units by remember { mutableStateOf(listOf("")) }
    val sheetMaxHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.88f

    val subjectNames = remember(existingItems, existingSubjects) {
        (existingItems.map { it.subject.trim() } + existingSubjects.map { it.subject.trim() })
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }
    val existingUnitsForSubject = remember(existingItems, subject) {
        val key = subject.trim()
        existingItems
            .filter { key.isEmpty() || it.subject.trim() == key }
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    fun applySubject(name: String) {
        subject = name
        note = existingSubjects.find { it.subject == name }?.note.orEmpty()
    }

    fun pickUnit(title: String) {
        val blankIndex = units.indexOfFirst { it.isBlank() }
        units = if (blankIndex >= 0) {
            units.toMutableList().also { it[blankIndex] = title }
        } else {
            units + title
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("儲存任務", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("課程") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (subjectNames.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    subjectNames.forEach { name ->
                        FilterChip(
                            selected = subject == name,
                            onClick = { applySubject(name) },
                            label = { Text(name) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("課程備註") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            if (existingUnitsForSubject.isNotEmpty()) {
                Text(
                    "已有單元",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    existingUnitsForSubject.forEach { unitTitle ->
                        FilterChip(
                            selected = units.any { it == unitTitle },
                            onClick = { pickUnit(unitTitle) },
                            label = { Text(unitTitle) }
                        )
                    }
                }
            }
            units.forEachIndexed { index, unit ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { v ->
                            units = units.toMutableList().also { it[index] = v }
                        },
                        label = { Text("單元") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            units = if (units.size <= 1) {
                                listOf("")
                            } else {
                                units.toMutableList().also { it.removeAt(index) }
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "刪除單元")
                    }
                }
            }
            TextButton(onClick = { units = units + "" }) {
                Text("新增單元")
            }
            PrimaryButton(
                "儲存",
                enabled = subject.isNotBlank()
            ) {
                onSave(subject, note, units)
            }
        }
    }
}
