# Better Encrypted Datastore
Better Encrypted Datastore is a library for securely storing encrypted data inside Datastore. It allows you to combine strong encryption with the convenience of reactive programming. In addition, the library extends Datastore's capabilities by adding the ability to store a Datastore file in device encrypted storage, making data from it accessible before the first device unlock. It's based on AndroidX Datastore v 1.1.3.

On screenshots below you can see that unencrypted data remains in RAM long time after user put application in background.

![Decoded Settings value in the heap](./images/encrypted_datastore_value.png)

![Reference from the AndroidX DataStore cache to Settings](./images/encrypted_datastore_path.png)

Better Encrypted Datastore keeps ciphertext in the file and in the underlying AndroidX DataStore cache. It uses a separate cache to share the decrypted value among active collectors of `data`. When the last collector finishes or is cancelled, the library drops the cache's reference to the decrypted value. An `updateData` call without active collectors uses plaintext during the operation and leaves the decrypted cache empty.

On screenshots below you can see that no references to the actual data remains in RAM when user put app in background and after garbage collector run - only default values. 

![Default ExampleData value held by the serializer](./images/bedatastore_value1.png)
![Reference from the serializer to its default value](./images/bedatastore_path1.png)
![Initial ExampleData value held by the ViewModel](./images/bedatastore_value2.png)
![Reference from the ViewModel's StateFlow to its initial value](./images/bedatastore_path2.png)

## Limitations

Plaintext can appear in memory during reads, writes, and migrations, and the decrypted cache can stay populated as long as a collector is active. Moving the app to the background only releases the cache if collection stops. Once all references to a value are gone, the runtime can reclaim it during garbage collection. The timing is up to the runtime, and residual bytes can remain in process memory even after the object is reclaimed.

The app controls how long it retains values received from DataStore. For example, a ViewModel that uses `stateIn` can use `SharingStarted.WhileSubscribed(replayExpirationMillis = 0)` to reset its `StateFlow` to the initial value as soon as sharing stops. `stopTimeoutMillis` controls how long sharing continues after the last subscriber leaves. Any references held elsewhere in the app have their own lifetime.

## Usage

Android 8.0. is the minimal supported version.


You can use Better Encrypted DataStore as usual DataStore. 

Add jitpack repository to settings.gradle.kts

    repositories {
        maven { url = uri("https://jitpack.io") }
    }

And add this dependency to your build.gradle.kts:

`implementation 'com.github.bakad3v:BetterEncryptedDatastore:1.1.3.0'`

And use extension function encryptedDataStore as the usual Datastore.

`private val Context.exampleDatastore by encryptedDataStore(NAME, ExampleSerializer())
val data = context.exampleDatastore.data`

You can also specify additional parameters:

alias - logical encryption alias. It defaults to the DataStore file name. New writes use AES-GCM
with the Keystore alias `<alias>.bedatastore-gcm-v1`. Existing AES-CBC files use their original key
and are readable. A write that changes their value saves it in the new format.

isDBA: Boolean - is Datastore file stored in device protected storage. By default - false.

If you want to store unencrypted data in device protected storage, you can use another extension function dataStoreDBA.
`private val Context.dbaDatastore by dataStoreDBA(DBA_NAME, DBASerializer())`

## Examples
This library is used for storing data in my open-source app [Android Antiforensic Tools](https://github.com/bakad3v/Android-AntiForensic-Tools).

## Tests

- `./gradlew :bedatastore:testDebugUnitTest` runs the adapted AndroidX DataStore 1.1.3 contract and migration scenarios, plus tests for cache lifetime and failure handling. The JVM suite uses real files and a test encryption provider.
- `./gradlew :bedatastore:connectedDebugAndroidTest` runs the Android Keystore and process recovery tests on a connected device or emulator.
- `./gradlew :bedatastore:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sonozaki.bedatastore.recovery.DataStoreProcessRecoveryTest` runs only the process recovery tests. They kill a worker in the test APK during reading, writing, or migration and verify the encrypted file from a fresh store while the device stays on. The JVM tests model storage faults separately with partial reads, failed writes, and truncated ciphertext.

The adapted test files identify their upstream sources and retain the [AndroidX license](bedatastore/src/test/ANDROIDX-LICENSE).

### Performance benchmarks

Run on a physical Android device with `./gradlew :benchmark:connectedReleaseAndroidTest`.
The suite compares `DataStoreBenchmark` (the public encrypted delegate with Android Keystore)
with `AndroidXDataStoreBenchmark` (stock AndroidX DataStore with `OkioStorage`). Both use the same
serializer, payloads, storage directory, and coroutine dispatcher. Each scenario runs with
payloads of 64 B, 4 KiB, 64 KiB, and 1 MiB:

- `coldRead` measures `data.first()` on a fresh DataStore instance with an existing file. The OS file cache is left in place.
- `warmReadWithoutCollectors` measures one-shot reads after priming the AndroidX cache. That cache holds ciphertext for the encrypted delegate and the decoded value for stock AndroidX.
- `hotReadWithCollector` measures one-shot reads with a separate active collector. For the encrypted delegate, the collector keeps the decrypted value cached.
- `updateAndCommit` alternates between two equal-size values and measures `updateData` through the file commit, with no active collectors.

File seeding, payload generation, store setup and shutdown, and result checks run outside the
timed section. The JSON report in `benchmark/build/outputs/connected_android_test_additional_output/`
includes timing samples and device details. Compare results on the same device and build type.
To run only stock AndroidX, add
`-Pandroid.testInstrumentationRunnerArguments.class=com.sonozaki.bedatastore.benchmark.AndroidXDataStoreBenchmark`.

## Donate
You can support me using crypto.

XMR: 88Z5fsVK6FP4oVNjo2BrHydAEa5Y1gTPi5d7BN68sjVDZ9dTn8wPb89WmUxrxf3T37bRGSR5dekkU9aQ7j8ErWcBJ2GZojC

LTC: ltc1qtfhun6yzt0qlpa9d6s8gr2yd70sg0kx755u3se

BTC: bc1qn9a9eyy2vgrz8yvhk0654d63dt56pxhpn6q4xl

USDT (TRC20), TRON: TUCrXnNRXgMccuvGF5cCrNuNJTg4uDvBbZ
