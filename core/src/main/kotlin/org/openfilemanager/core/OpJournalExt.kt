package org.openfilemanager.core

internal fun OpJournal.markRunning(ctx: RunContext, item: OpItemRecord) {
    updateItem(ctx.record.id, item.index, ItemState.RUNNING)
}

internal fun OpJournal.skipItem(ctx: RunContext, item: OpItemRecord, reason: String) {
    updateItem(ctx.record.id, item.index, ItemState.SKIPPED, error = reason)
    ctx.c.skipped++
}

internal fun OpJournal.failItem(ctx: RunContext, item: OpItemRecord, error: String) {
    updateItem(ctx.record.id, item.index, ItemState.FAILED, error = error)
    ctx.c.failed++
}
