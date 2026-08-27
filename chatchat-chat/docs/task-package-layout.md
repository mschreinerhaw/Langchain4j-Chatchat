# Task package layout

`com.chatchat.chat.task` owns agent task submission, execution coordination, scheduling, events,
notifications, learning, todo integration, rate limiting, and evidence storage.

| Package | Responsibility |
| --- | --- |
| `task.core` | Task commands, responses, lifecycle service, feedback, cancellation, and confirmation state |
| `task.queue` | Runtime queue coordination, execution configuration, timeout scheduling, and tenant quotas |
| `task.event` | Task event publication, consumption, keys, buses, and event-store implementations |
| `task.schedule` | Scheduled-task definitions, execution windows, run audits, and scheduling service |
| `task.notification` | Notification policy, content formatting, recipients, and delivery history views |
| `task.learning` | Task-effect analytics, learned experience, indexes, and learning configuration |
| `task.todo` | Todo-task persistence and application service |
| `task.ratelimit` | Database-backed tool rate limiting and bucket persistence |
| `task.evidence` | Task evidence persistence adapters |

## Placement rules

1. Keep the `task` root free of concrete types.
2. Place entities and repositories beside the feature whose state they persist.
3. Keep runtime admission and dispatch mechanics in `queue`; task lifecycle APIs belong in `core`.
4. Keep scheduled execution in `schedule` and notification decisions in `notification`.
5. Avoid generic `util`, `model`, and `persistence` buckets when a functional owner exists.
