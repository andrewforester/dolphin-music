package com.voiceproducer.core.domain.edit

import com.voiceproducer.core.domain.plan.EditResult
import com.voiceproducer.core.domain.plan.PlanEdit
import com.voiceproducer.core.domain.plan.PlanEditor
import com.voiceproducer.core.model.CompositionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class QueuedEdit(
    val edit: PlanEdit,
    val changed: List<Int>,
)

internal class EditQueue {
    private val _busy = MutableStateFlow(false)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    lateinit var editingPlan: CompositionPlan
    val queue = mutableListOf<QueuedEdit>()
    var undos = 0

    fun resyncEditingPlan(committed: CompositionPlan) {
        editingPlan =
            queue.fold(committed) { plan, q ->
                (PlanEditor.apply(plan, q.edit) as? EditResult.Applied)?.plan ?: plan
            }
    }

    fun updateBusy() {
        _busy.value = queue.isNotEmpty() || undos > 0
    }
}
