package piuk.blockchain.android.ui.contacts.list

import android.app.Application
import android.content.Intent
import com.nhaarman.mockito_kotlin.*
import info.blockchain.wallet.contacts.data.Contact
import info.blockchain.wallet.exceptions.DecryptionException
import info.blockchain.wallet.metadata.MetadataNodeFactory
import info.blockchain.wallet.payload.Payload
import info.blockchain.wallet.payload.PayloadManager
import io.reactivex.Completable
import io.reactivex.Observable
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import piuk.blockchain.android.BlockchainTestApplication
import piuk.blockchain.android.BuildConfig
import piuk.blockchain.android.data.datamanagers.ContactsDataManager
import piuk.blockchain.android.injection.*
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.util.PrefsUtil

@Config(sdk = intArrayOf(23), constants = BuildConfig::class, application = BlockchainTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class ContactsListViewModelTest {

    private lateinit var subject: ContactsListViewModel
    private var mockActivity: ContactsListViewModel.DataListener = mock()
    private var mockContactsManager: ContactsDataManager = mock()
    private var mockPayloadManager: PayloadManager = mock()
    private var mockPrefsUtil: PrefsUtil = mock()

    @Before
    @Throws(Exception::class)
    fun setUp() {

        InjectorTestUtils.initApplicationComponent(
                Injector.getInstance(),
                MockApplicationModule(RuntimeEnvironment.application),
                MockApiModule(),
                DataManagerModule())

        subject = ContactsListViewModel(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldHandleLinkSuccessfully() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.error { Throwable() })
        val intent = Intent()
        val uri = "METADATA_URI"
        intent.putExtra(ContactsListActivity.EXTRA_METADATA_URI, uri)
        whenever(mockActivity.pageIntent).thenReturn(intent)
        whenever(mockContactsManager.acceptInvitation(uri)).thenReturn(Observable.just(Contact()))
        val contacts = listOf(Contact(), Contact(), Contact())
        whenever(mockContactsManager.contactList).thenReturn(Observable.fromIterable(contacts))
        whenever(mockContactsManager.contactsWithUnreadPaymentRequests).thenReturn(Observable.empty())
        whenever(mockContactsManager.readInvitationSent(any())).thenReturn(Observable.just(true))
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verify(mockActivity).showProgressDialog()
        verify(mockActivity).dismissProgressDialog()
        verify(mockActivity).showToast(any(), eq(ToastCustom.TYPE_OK))
        verify(mockActivity).setUiState(ContactsListActivity.CONTENT)
        verify(mockActivity).onContactsLoaded(any())
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldShowSecondPasswordDialog() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(false))
        val mockPayload: Payload = mock()
        whenever(mockPayloadManager.payload).thenReturn(mockPayload)
        whenever(mockPayload.isDoubleEncrypted).thenReturn(true)
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).showSecondPasswordDialog()
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldInitContacts() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(false))
        val mockPayload: Payload = mock()
        whenever(mockPayloadManager.payload).thenReturn(mockPayload)
        whenever(mockPayload.isDoubleEncrypted).thenReturn(false)
        whenever(mockContactsManager.generateNodes(isNull())).thenReturn(Completable.complete())
        val mockNodeFactory: MetadataNodeFactory = mock()
        whenever(mockContactsManager.metadataNodeFactory).thenReturn(Observable.just(mockNodeFactory))
        whenever(mockNodeFactory.sharedMetadataNode).thenReturn(mock())
        whenever(mockNodeFactory.metadataNode).thenReturn(mock())
        whenever(mockContactsManager.initContactsService(any(), any())).thenReturn(Completable.complete())
        whenever(mockContactsManager.registerMdid()).thenReturn(Completable.complete())
        whenever(mockContactsManager.publishXpub()).thenReturn(Completable.complete())
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity, times(2)).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        // There will be other interactions with the mocks, but they are not tested here
        verify(mockPayloadManager).payload
        verify(mockPayload).isDoubleEncrypted
        verify(mockContactsManager).generateNodes(isNull())
        verify(mockContactsManager).metadataNodeFactory
        verify(mockContactsManager).initContactsService(any(), any())
        verify(mockContactsManager).registerMdid()
        verify(mockContactsManager).publishXpub()
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldLoadContactsSuccessfully() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(true))
        whenever(mockContactsManager.fetchContacts()).thenReturn(Completable.complete())
        val contacts = listOf(Contact(), Contact(), Contact())
        whenever(mockContactsManager.contactList).thenReturn(Observable.fromIterable(contacts))
        whenever(mockContactsManager.contactsWithUnreadPaymentRequests)
                .thenReturn(Observable.fromIterable(listOf<Contact>()))
        whenever(mockContactsManager.readInvitationSent(any())).thenReturn(Observable.just(true))
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity, times(2)).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.CONTENT)
        verify(mockActivity).onContactsLoaded(any())
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldLoadContactsEmpty() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(true))
        whenever(mockContactsManager.fetchContacts()).thenReturn(Completable.complete())
        whenever(mockContactsManager.contactList).thenReturn(Observable.fromIterable(listOf<Contact>()))
        whenever(mockContactsManager.contactsWithUnreadPaymentRequests)
                .thenReturn(Observable.fromIterable(listOf<Contact>()))
        whenever(mockContactsManager.readInvitationSent(any())).thenReturn(Observable.just(true))
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity, times(2)).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.EMPTY)
        verify(mockActivity).onContactsLoaded(any())
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldFailLoadingRequests() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(true))
        whenever(mockContactsManager.fetchContacts()).thenReturn(Completable.complete())
        whenever(mockContactsManager.contactList).thenReturn(Observable.fromIterable(listOf<Contact>()))
        whenever(mockContactsManager.contactsWithUnreadPaymentRequests)
                .thenReturn(Observable.error { Throwable() })
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity, times(2)).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verify(mockActivity).onContactsLoaded(any())
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun onViewReadyShouldFailLoadingContacts() {
        // Arrange
        whenever(mockContactsManager.loadNodes()).thenReturn(Observable.just(true))
        whenever(mockContactsManager.fetchContacts()).thenReturn(Completable.error { Throwable() })
        // Act
        subject.onViewReady()
        // Assert
        verify(mockActivity).pageIntent
        verify(mockActivity, times(2)).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verifyNoMoreInteractions(mockActivity)
    }

    @Test
    @Throws(Exception::class)
    fun initContactsServiceShouldThrowDecryptionException() {
        // Arrange
        val password = "PASSWORD"
        whenever(mockContactsManager.generateNodes(password)).thenReturn(Completable.error { Throwable(DecryptionException()) })
        val mockNodeFactory: MetadataNodeFactory = mock()
        whenever(mockContactsManager.metadataNodeFactory).thenReturn(Observable.just(mockNodeFactory))
        whenever(mockNodeFactory.sharedMetadataNode).thenReturn(mock())
        whenever(mockNodeFactory.metadataNode).thenReturn(mock())
        whenever(mockContactsManager.initContactsService(any(), any())).thenReturn(Completable.complete())
        whenever(mockContactsManager.registerMdid()).thenReturn(Completable.complete())
        whenever(mockContactsManager.publishXpub()).thenReturn(Completable.complete())
        // Act
        subject.initContactsService(password)
        // Assert
        verify(mockActivity).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verify(mockActivity).showToast(any(), eq(ToastCustom.TYPE_ERROR))
        verifyNoMoreInteractions(mockActivity)
        verify(mockContactsManager).generateNodes(password)
        verify(mockContactsManager).metadataNodeFactory
        verify(mockContactsManager).registerMdid()
        verify(mockContactsManager).publishXpub()
        verifyNoMoreInteractions(mockContactsManager)
    }

    @Test
    @Throws(Exception::class)
    fun initContactsServiceShouldThrowException() {
        // Arrange
        val password = "PASSWORD"
        whenever(mockContactsManager.generateNodes(password)).thenReturn(Completable.error { Throwable() })
        val mockNodeFactory: MetadataNodeFactory = mock()
        whenever(mockContactsManager.metadataNodeFactory).thenReturn(Observable.just(mockNodeFactory))
        whenever(mockNodeFactory.sharedMetadataNode).thenReturn(mock())
        whenever(mockNodeFactory.metadataNode).thenReturn(mock())
        whenever(mockContactsManager.initContactsService(any(), any())).thenReturn(Completable.complete())
        whenever(mockContactsManager.registerMdid()).thenReturn(Completable.complete())
        whenever(mockContactsManager.publishXpub()).thenReturn(Completable.complete())
        // Act
        subject.initContactsService(password)
        // Assert
        verify(mockActivity).setUiState(ContactsListActivity.LOADING)
        verify(mockActivity).setUiState(ContactsListActivity.FAILURE)
        verify(mockActivity).showToast(any(), eq(ToastCustom.TYPE_ERROR))
        verifyNoMoreInteractions(mockActivity)
        verify(mockContactsManager).generateNodes(password)
        verify(mockContactsManager).metadataNodeFactory
        verify(mockContactsManager).registerMdid()
        verify(mockContactsManager).publishXpub()
        verifyNoMoreInteractions(mockContactsManager)
    }

    inner class MockApplicationModule(application: Application?) : ApplicationModule(application) {
        override fun providePrefsUtil(): PrefsUtil {
            return mockPrefsUtil
        }
    }

    inner class MockApiModule : ApiModule() {
        override fun providePayloadManager(): PayloadManager {
            return mockPayloadManager
        }

        override fun provideContactsManager(payloadManager: PayloadManager?): ContactsDataManager {
            return mockContactsManager
        }
    }

}