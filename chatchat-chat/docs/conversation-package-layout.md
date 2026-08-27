# Conversation package layout

`com.chatchat.chat.conversation` owns conversation lifecycle, history models, relational indexes, and large message payload storage.

| Package | Responsibility |
| --- | --- |
| `conversation.model` | Conversation, summary, and message-detail domain models |
| `conversation.service` | Conversation lifecycle, history assembly, summaries, and operation guards |
| `conversation.persistence` | JPA entities and repositories for sessions, summaries, and message indexes |
| `conversation.store` | Message detail/text store contracts, implementations, key construction, and configuration |

## Placement rules

1. Keep the `conversation` root free of concrete types.
2. Domain models must not depend on service, persistence, or store implementations.
3. Keep relational metadata in `persistence`; large message bodies belong behind contracts in `store`.
4. Application workflow belongs in `service`, not in repositories or storage adapters.
5. Avoid generic `util` and `impl` packages; place helpers beside the functionality they support.
