# Control ownership

Normal completion uses `task.progress.finish` or `task.completion.show` with the
current task ID. It releases ownership while leaving the connection open,
clears the task's comments/plan/cursor/glow, and briefly presents `Completed` or
the supplied message with `Dismiss`. The message disappears after about three
seconds. Dismiss changes presentation only; Stop cancels authorization for
further dispatches. Disconnect releases ownership without claiming successful
completion. Operation failure leaves the task active until finish or stop.

The helper manages unique ephemeral task IDs automatically. Raw clients must
attach a fresh `taskId` to each new task's mutations/progress and use it for
completion. A stale completion cannot release newer work, and a new task
invalidates old presentation callbacks. Completion also invalidates work queued
under the previous control ticket; a transport timeout does not authorize replay.

A connected client is not necessarily the control owner. The first accepted
mutation or task presentation claims control for that client identity. Other
clients receive `control_owned` until ownership is released. Local clients
share the local compatibility identity; paired clients use their token identity.
The activity, notification, and health response distinguish `available`, `active`,
and `stopped`; the health response exposes this as `controlState` without client identifiers.

The on-device **Stop control** button is available in the activity, a compact
overlay, and the notification. It clears task presentations and prevents new or
queued mutations. Stop is independent of the serialized execution queue. The
stopped state survives service restart and reconnect. Only an explicit on-device
**Resume control** action enables new work.

Requests retain their ingress generation. Stopping and resuming invalidates old
requests, even if they only acquire the execution queue after resume. Each batch
step and each mutation dispatch rechecks control. `control_stopped` means no new
dispatch was permitted at that check; previously dispatched Android gestures or
external commands may still finish. Henyo does not kill unrelated processes.
Clients must not interpret a transport timeout as proof of cancellation.

Revoking a paired client also closes its established sockets and releases its
ownership. Queued operations and unsolicited snapshots check current control
permission. Remote token-management requests require the existing
`token-management` scope; lacking it returns `token_management_permission_required`.

Disconnecting the last session for an owner releases its ownership without
revoking pairing. A different connected client does not acquire ownership just
by staying connected. Unsolicited tree snapshots pause while control is stopped.

If Android notifications are disabled, the activity and overlay still expose
the controls. Notification stop uses an explicit, non-exported receiver.

## Device verification

Before merging, verify stop while a request is waiting, stop between scroll or
batch steps, reconnect while stopped, service restart while stopped, and resume
with old queued requests. Check two paired clients, notification-disabled mode,
overlay interaction on supported displays, screenshots without Henyo overlays,
and already-dispatched gesture/command behavior.
