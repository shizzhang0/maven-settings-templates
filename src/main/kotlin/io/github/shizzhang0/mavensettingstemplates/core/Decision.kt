package io.github.shizzhang0.mavensettingstemplates.core

enum class Trigger { OPEN, SETTINGS_APPLIED, CHANGED_AT_RUNTIME }

enum class Decision {
    /** Nothing to do. */
    NONE,

    /** The IDE already has the target values; only remember them as lastApplied. */
    RECORD,

    /** First time this project is managed: write, sync, offer Undo. */
    FIRST_APPLY,

    /** Nobody touched what we wrote, or `.idea` was recreated: write and sync silently. */
    FOLLOW,

    /** Someone changed the values by hand: ask the user. */
    NOTIFY_DRIFT,
}

/**
 * Design §5.2. [target] null means "leave the project alone". [baseline] (what a brand-new project gets)
 * is only consulted on [Trigger.OPEN]: matching it then means `.idea` was recreated, not edited by hand.
 */
fun decide(
    target: MavenValues?,
    current: MavenValues,
    lastApplied: MavenValues?,
    baseline: MavenValues?,
    ignored: MavenValues?,
    trigger: Trigger,
): Decision = when {
    target == null -> Decision.NONE
    current.sameAs(target) -> if (target.sameAs(lastApplied)) Decision.NONE else Decision.RECORD
    lastApplied == null -> Decision.FIRST_APPLY
    current.sameAs(lastApplied) -> Decision.FOLLOW
    trigger == Trigger.OPEN && current.sameAs(baseline) -> Decision.FOLLOW
    current.sameAs(ignored) -> Decision.NONE
    else -> Decision.NOTIFY_DRIFT
}
