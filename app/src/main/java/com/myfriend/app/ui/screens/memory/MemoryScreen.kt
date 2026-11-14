package com.myfriend.app.ui.screens.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.local.MemoryCategory
import com.myfriend.app.data.local.MemoryConsolidationEntity
import com.myfriend.app.data.local.MemoryEntity
import com.myfriend.app.domain.MemoryDrawer
import com.myfriend.app.domain.MemoryTier
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.formatFullDateTime

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    var deletingMemory by remember { mutableStateOf<MemoryEntity?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingMemory = null
                    showEditor = true
                },
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加记忆", modifier = Modifier.size(25.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "长期记忆",
                subtitle = state.persona?.name?.let { "关于你与 $it 之间记住的事" } ?: "人格的长期记忆",
                navigation = { BackNavigation(onBack) },
                actions = {
                    IconButton(onClick = viewModel::extractFromChats, enabled = !state.extracting) {
                        if (state.extracting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.8.dp, color = LocalAccentColor.current)
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "从最近对话整理记忆", tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)
            ) {
                item {
                    state.persona?.let { persona ->
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 48.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(persona.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${state.tierCounts.active} 条还在用 · 共 ${state.memories.size} 条长期记忆",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextPill("注入中", selected = true)
                            }
                        }
                    }
                }
                item(key = "health") {
                    MemoryHealthCard(
                        counts = state.tierCounts,
                        lastConsolidation = state.lastConsolidation,
                        consolidating = state.consolidating,
                        queued = state.consolidationQueued,
                        onConsolidate = viewModel::runConsolidationNow
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = LocalAccentColor.current.copy(alpha = 0.075f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Outlined.Memory, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(18.dp))
                            Text(
                                "记忆只属于这个人物人格，不会发送给其他 API。每轮对话都会把相关记忆随人格一起注入。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (state.memories.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Memory,
                            title = "还没有记忆",
                            description = "在聊天中自然相处，或点击右上角的星芒从最近对话中整理。也可以手动添加一件想让它记住的事。",
                            modifier = Modifier.padding(top = 20.dp),
                            action = {
                                TextButton(onClick = viewModel::extractFromChats) {
                                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("从最近对话整理")
                                }
                            }
                        )
                    }
                } else {
                    item {
                        MemoryFilterRow(
                            filter = state.filter,
                            sort = state.sort,
                            onFilter = viewModel::setFilter,
                            onToggleSort = viewModel::toggleSort
                        )
                    }
                    state.sections.forEach { section ->
                        item(key = "section-${section.drawer.name}") {
                            SectionLabel(
                                section.title,
                                trailing = {
                                    Text(
                                        "${section.memories.size} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            )
                        }
                        if (section.memories.isEmpty()) {
                            item(key = "empty-${section.drawer.name}") {
                                Text(
                                    section.emptyHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                                )
                            }
                        } else {
                            items(section.memories, key = { it.id }) { memory ->
                                MemoryCard(
                                    memory = memory,
                                    onEdit = {
                                        editingMemory = memory
                                        showEditor = true
                                    },
                                    onPin = { viewModel.togglePinned(memory) },
                                    onDelete = { deletingMemory = memory }
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        MemoryEditorDialog(
            memory = editingMemory,
            onDismiss = { showEditor = false },
            onSave = { content, category, importance ->
                val target = editingMemory
                if (target == null) {
                    viewModel.addMemory(content, category, importance)
                } else {
                    viewModel.updateMemory(target, content, category, importance)
                }
                showEditor = false
            }
        )
    }

    deletingMemory?.let { memory ->
        ConfirmDialog(
            title = "删除这条记忆？",
            message = "「${memory.content.take(80)}」将从该人格的长期记忆中移除。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteMemory(memory)
                deletingMemory = null
            },
            onDismiss = { deletingMemory = null }
        )
    }
}

@Composable
private fun MemoryFilterRow(
    filter: MemoryDrawer?,
    sort: MemorySort,
    onFilter: (MemoryDrawer?) -> Unit,
    onToggleSort: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextPill("全部", selected = filter == null, onClick = { onFilter(null) })
            // 枚举顺序是 SELF/USER/EVENT，这里按产品顺序「关于你 → 他 → 经历」排。
            memoryFilterOrder.forEach { drawer ->
                TextPill(
                    memoryDrawerPillLabel(drawer),
                    selected = filter == drawer,
                    onClick = { onFilter(drawer) }
                )
            }
        }
        IconButton(onClick = onToggleSort, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.SwapVert,
                contentDescription = "当前按${sort.label}排序，点击切换",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MemoryHealthCard(
    counts: MemoryTierCounts,
    lastConsolidation: MemoryConsolidationEntity?,
    consolidating: Boolean,
    queued: Boolean,
    onConsolidate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "记忆的健康",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${counts.total} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierCount("还在用", counts.active, LocalAccentColor.current)
                TierCount("沉下去了", counts.dormant, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                TierCount("已归档", counts.archived, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "沉下去的不再优先被想起，还会被关键词捞回来；归档的不参与上下文，只有他认真回忆（recall 3）时才可能翻到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                lastConsolidation?.let {
                    MemoryHealthText.lastRunLine(it.at, it.kept, it.merged, it.archived, it.forgotten)
                } ?: "还没整理过",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onConsolidate,
                    // 排队中仍然可以再点：REPLACE 会把它重新排一次，别让按钮一直装死。
                    enabled = !consolidating,
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    if (queued) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("排队中…", style = MaterialTheme.typography.labelMedium)
                    } else if (consolidating) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp),
                            strokeWidth = 1.6.dp,
                            color = LocalAccentColor.current
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("整理中…", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("现在整理一次", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 三档里的一档：一个数字加一句白话标签。数量都从列表里数，不另外查库。 */
@Composable
private fun RowScope.TierCount(label: String, count: Int, color: Color) {
    Column(Modifier.weight(1f)) {
        Text(
            "$count",
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val tier = MemoryTier.parse(memory.status)
    // 沉下去 / 归档的整张卡降对比度：一眼能和「还在用」的区分开，但正文仍然读得清。
    val bodyAlpha = when (tier) {
        MemoryTier.ACTIVE -> 1f
        MemoryTier.DORMANT -> 0.68f
        MemoryTier.ARCHIVED -> 0.48f
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (tier == MemoryTier.ACTIVE) 0.65f else 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    categoryLabel(memory.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = categoryColor(memory.category).copy(alpha = if (tier == MemoryTier.ACTIVE) 1f else 0.65f)
                )
                Spacer(Modifier.width(8.dp))
                repeat(memory.importance.coerceIn(1, 5)) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = LocalAccentColor.current.copy(alpha = 0.8f * bodyAlpha)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tier != MemoryTier.ACTIVE) {
                    Text(
                        tierTagLabel(tier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (memory.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "一直记着",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "一直记着",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                            menuOpen = false
                            onEdit()
                        })
                        DropdownMenuItem(text = { Text(if (memory.pinned) "取消固定" else "固定为重要") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = {
                            menuOpen = false
                            onPin()
                        })
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Text(
                memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = bodyAlpha),
                modifier = Modifier.padding(end = 9.dp, top = 2.dp),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                memorySourceLabel(memory),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (tier == MemoryTier.ACTIVE) 0.7f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 9.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                formatFullDateTime(memory.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (tier == MemoryTier.ACTIVE) 0.55f else 0.35f)
            )
            // 归档不是删除：这句小字说明它去哪了，不弹窗、也不给按钮。
            if (tier == MemoryTier.ARCHIVED) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "只有他认真回忆时才可能翻到",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.padding(end = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun MemoryEditorDialog(
    memory: MemoryEntity?,
    onDismiss: () -> Unit,
    onSave: (String, MemoryCategory, Int) -> Unit
) {
    var content by remember(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var category by remember(memory?.id) { mutableStateOf(memory?.category ?: MemoryCategory.FACT) }
    var importance by remember(memory?.id) { mutableStateOf(memory?.importance ?: 3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (memory == null) "添加记忆" else "编辑记忆") },
        text = {
            Column {
                StyledOutlinedField(
                    value = content,
                    onValueChange = { content = it.take(2000) },
                    label = "它应该记住什么",
                    placeholder = "例如：对方喜欢在夜里散步，讨厌被追问。",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )
                Spacer(Modifier.height(12.dp))
                Text("分类", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryCategory.entries.forEach { item ->
                        TextPill(
                            categoryLabel(item),
                            selected = category == item,
                            onClick = { category = item }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("重要度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    repeat(5) { index ->
                        val star = index + 1
                        IconButton(
                            onClick = { importance = star },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = "$star 星",
                                tint = if (star <= importance) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content, category, importance) },
                enabled = content.isNotBlank()
            ) { Text(if (memory == null) "记住" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun memoryDrawerPillLabel(drawer: MemoryDrawer): String = when (drawer) {
    MemoryDrawer.USER -> "关于你"
    MemoryDrawer.SELF -> "他"
    MemoryDrawer.EVENT -> "经历"
}

private val memoryFilterOrder = listOf(MemoryDrawer.USER, MemoryDrawer.SELF, MemoryDrawer.EVENT)

/** 来源可追溯：优先显示触发这条记忆的原话，没有原话时说明是手动添加还是对话整理。 */
private fun memorySourceLabel(memory: MemoryEntity): String {
    val quote = memory.sourceQuote?.trim().orEmpty()
    return when {
        quote.isNotEmpty() -> {
            val head = quote.take(40)
            if (quote.length > head.length) "来自：$head…" else "来自：$head"
        }

        memory.sourceSessionId == null -> "手动添加"
        else -> "来自对话"
    }
}

private fun categoryLabel(category: MemoryCategory): String = when (category) {
    MemoryCategory.FACT -> "事实"
    MemoryCategory.PREFERENCE -> "偏好"
    MemoryCategory.EVENT -> "经历"
    MemoryCategory.RELATIONSHIP -> "关系"
    MemoryCategory.OTHER -> "其他"
}

/** 行尾的一点点标记：只在不是「还在用」时才出现，不提数字、不加颜色。 */
private fun tierTagLabel(tier: MemoryTier): String = when (tier) {
    MemoryTier.ACTIVE -> ""
    MemoryTier.DORMANT -> "沉睡"
    MemoryTier.ARCHIVED -> "归档"
}

@Composable
private fun categoryColor(category: MemoryCategory): Color = when (category) {
    MemoryCategory.FACT -> MaterialTheme.colorScheme.onSurfaceVariant
    MemoryCategory.PREFERENCE -> LocalAccentColor.current
    MemoryCategory.EVENT -> Color(0xFF6F8B65)
    MemoryCategory.RELATIONSHIP -> Color(0xFF966A88)
    MemoryCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}
