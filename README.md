# Better Encrypted Datastore
Better Encrypted Datastore is a library for securely storing encrypted data inside Datastore. It allows you to combine strong encryption with the convenience of reactive programming. In addition, the library extends Datastore's capabilities by adding the ability to store a Datastore file in device encrypted storage, making data from it accessible before the first device unlock.

## Comparison with existing analogues
Existing libraries for encrypting data inside a Datastore, such as [Encrypted DataStore](https://github.com/osipxd/encrypted-datastore), are limited to only encrypting the file where the data is stored. However, DataStore is designed in such a way that the data is stored in a cache in RAM after it is initially loaded from the file, rather than being read from the file each time the data is accessed. In existing libraries, the values in this cache are not encrypted. Furthermore, Datastore is implemented as a singleton, which means that this decrypted data will be in RAM the entire time the application is running.

On screenshots below you can see that unencrypted data remains in RAM long time after user put application in background.

![Image](./images/encrypted_datastore_value.png)
![Image](./images/encrypted_datastore_path.png)

This design protects from some attacks, but opens the door for attackers to perform an attack by extracting data from a RAM dump. Although difficult due to code obfuscation, this attack has already [shown to be effective](https://cellebrite.com/en/decrypting-databases-using-ram-dump-health-data/) against encrypted databases. In addition, on some devices, RAM dumps can be obtained even without root privileges.

In my library, not only the DataStore file is encrypted, but also the cache. It adds a separate cache on top of the existing DataStore cache to store decrypted data. This cache will be filled with values at the first subscription to the data stream and overwritten with an empty value when all subscribers unsubscribe from the data stream. Thus, decrypted data will not be stored in RAM longer than necessary for user.

On screenshots below you can see that no actual data remains in RAM when user put app in background - only default values. 

![Image](./images/bedatastore_value1.png)
![Image](./images/bedatastore_path1.png)
![Image](./images/bedatastore_value2.png)
![Image](./images/bedatastore_path2.png)

My library also provides possibility to store encrypted and unencrypted data in device protected storage. That makes data from datastore accessible before first unlock.

## Limitations

Better Encrypted Datastore prevents upstream data leakage, but cannot prevent downstream leakage, such as in the presentation layer. It is up to you to take care of data security after it leaves the DataStore. For example, if you want to expose data flow as StateFlow in ViewModel, it is recommended to use the SharingStarted.WhileSubscribed() strategy with replayExpirationMillis parameter specified. This parameter configures a delay (in milliseconds) between the stopping of the sharing coroutine and the resetting of the replay cache.

## Usage
You can use Better Encrypted DataStore as usual DataStore. Just add this to your build.gradle:

`implementation 'com.github.bakad3v:BetterEncryptedDatastore:1.0.0-alpha'`

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

BTC lightning: dualwhorl66@walletofsatoshi.com
