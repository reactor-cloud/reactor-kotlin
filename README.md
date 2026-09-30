# reactor-client

Kotlin client for Reactor. Auth, a query builder, file storage, and functions for Android and the JVM.

```kotlin
dependencies {
    implementation("sl.atomicollabs.reactor:reactor-client:1.26.9-beta.2")
}
```

[reactor.cloud](https://www.reactor.cloud) · [docs](https://github.com/reactor-cloud/reactor/blob/v1.26.09-beta.2/docs/clients/kotlin.md)

```kotlin
val reactor = ReactorClient("https://<ref>.example.com", anonKey)

val session = reactor.auth.signInWithPassword(email, password)

val inserted = reactor.from("todos")
    .insert(JSONObject().put("title", title).put("user_id", session.user.id))
    .select()
    .execute()

reactor.storage.from("files").upload(path, bytes)
val result = reactor.functions.invoke("ping")
```

| Surface | Call |
| --- | --- |
| Auth | `reactor.auth` — sign up, password, session, sign out |
| Data | `reactor.from(table)` — select, insert, update, delete |
| Storage | `reactor.storage.from(bucket)` — upload and download |
| Functions | `reactor.functions.invoke(name)` |

The query builder covers the calls a first app needs. Use HTTP for the rest of PostgREST. `signInWithOAuth(provider, redirectTo)` returns the authorize URL. `signUp` returns `AuthResult`. Failures throw `ReactorException`.

Git tag `v1.26.09-beta.2`.

## License

You can use Reactor as the backend for as many personal or commercial projects as you want. The license only restricts offering it as a competing hosted service.

Business Source License 1.1. Copyright 2026 AtomicoLabs SL and Claudio del Conde. See [LICENSE](LICENSE).
