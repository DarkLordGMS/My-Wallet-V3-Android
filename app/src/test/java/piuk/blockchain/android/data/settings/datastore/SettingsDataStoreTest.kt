package piuk.blockchain.android.data.settings.datastore

import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import info.blockchain.wallet.api.data.Settings
import io.reactivex.Observable
import org.amshove.kluent.mock
import org.junit.Test
import piuk.blockchain.android.RxTest
import piuk.blockchain.android.data.stores.Optional

class SettingsDataStoreTest : RxTest() {

    lateinit var subject: SettingsDataStore
    lateinit var webSource: Observable<Settings>
    val memoryStore: SettingsMemoryStore = mock()

    @Test
    fun `getSettings using DefaultFetchStrategy from websource`() {
        // Arrange
        val mockSettings: Settings = mock()
        webSource = Observable.just(mockSettings)
        whenever(memoryStore.getSettings()).thenReturn(Observable.just(Optional.None))
        whenever(memoryStore.store(mockSettings)).thenReturn(Observable.just(mockSettings))
        subject = SettingsDataStore(memoryStore, webSource)
        // Act
        val testObserver = subject.getSettings().test()
        // Assert
        verify(memoryStore).getSettings()
        testObserver.assertValue { it == mockSettings }
    }

    @Test
    fun fetchSettings() {
        // Arrange
        val mockSettings: Settings = mock()
        webSource = Observable.just(mockSettings)
        whenever(memoryStore.store(mockSettings)).thenReturn(Observable.just(mockSettings))
        subject = SettingsDataStore(memoryStore, webSource)
        // Act
        val testObserver = subject.fetchSettings().test()
        // Assert
        verify(memoryStore).store(mockSettings)
        testObserver.assertValue { it == mockSettings }
    }

}