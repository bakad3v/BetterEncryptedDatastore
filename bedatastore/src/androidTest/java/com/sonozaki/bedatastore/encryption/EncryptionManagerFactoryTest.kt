package com.sonozaki.bedatastore.encryption

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@RunWith(AndroidJUnit4::class)
class EncryptionManagerFactoryTest {
    // Independent stores can request the manager concurrently. Release callers together
    // and verify that they all receive the same instance. Running this class by itself
    // also exercises the first access in a fresh instrumentation process.
    @Test
    fun concurrentCallersShareTheSameKeystoreManager() {
        val callers = 8
        val executor = Executors.newFixedThreadPool(callers)
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        try {
            val results = List(callers) {
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(10, SECONDS)) { "Factory callers were never released" }
                    EncryptionManagerFactory.get()
                })
            }
            assertTrue("Some factory callers did not start", ready.await(5, SECONDS))
            start.countDown()

            val manager = results.first().get(5, SECONDS)
            for (result in results.drop(1)) assertSame(manager, result.get(5, SECONDS))
            assertSame(manager, EncryptionManagerFactory.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
