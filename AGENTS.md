# Better Encrypted DataStore

Better Encrypted DataStore is a Kotlin library that wraps AndroidX DataStore and exposes the familiar `DataStore<T>` API. It uses keys managed by Android Keystore to encrypt both the file on disk and the values held in the underlying DataStore's in-memory cache. The wrapper decrypts values on demand and keeps a separate, short-lived cache to serve active collectors of the `data` flow. The library also supports device-protected storage for access before the first device unlock. See the [README](README.md) for details and usage examples.

## Security and compatibility

- Keep data encrypted until a caller needs it. Keep plaintext in library-owned memory only for as long as an active collector or operation needs it. Release the decrypted cache when the last collector leaves, including on cancellation, and avoid retaining plaintext when an update runs without collectors. The calling app is responsible for any copies it keeps after receiving a value.
- Keep plaintext and key material out of logs and diagnostics. Review failure paths as carefully as successful ones. Handle I/O, Keystore, decryption, and serialization failures according to their cause. Use corruption recovery only for failures that qualify as corruption, and avoid silently replacing existing data with defaults.
- Preserve AndroidX DataStore behavior wherever the library's documented encryption and storage features allow it. Reuse AndroidX for storage behavior where possible. Verify that `data` collection, atomic `updateData` operations, migrations, corruption handling, and cancellation behave as callers expect. Check that failed or cancelled writes leave disk and cache state consistent and that existing encrypted files remain readable.

## Code style

- Follow KISS and DRY. Choose the simplest implementation that preserves security and DataStore behavior, and reuse existing logic when it serves the same purpose. Add abstractions only when they clarify the code or remove meaningful duplication.
- Write detailed comments in plain English where the reasoning is hard to infer. Explain why the code works the way it does, which assumptions it relies on, and what could break if the order or lifetime changes. Use straightforward language and avoid restating individual lines of code or adding unnecessary jargon.
- Organize code by responsibility across files, classes, and packages. Follow the library's existing separation between DataStore integration, encryption, serialization, migrations, and corruption handling. Keep each file and class focused on a clear job.
