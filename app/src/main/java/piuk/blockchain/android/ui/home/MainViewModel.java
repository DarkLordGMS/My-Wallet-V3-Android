package piuk.blockchain.android.ui.home;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatDialogFragment;
import android.util.Log;

import info.blockchain.wallet.api.WalletApi;
import info.blockchain.wallet.exceptions.InvalidCredentialsException;
import info.blockchain.wallet.payload.PayloadManager;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.Collections;

import javax.inject.Inject;

import io.reactivex.Completable;
import io.reactivex.Observable;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.api.EnvironmentSettings;
import piuk.blockchain.android.data.cache.DynamicFeeCache;
import piuk.blockchain.android.data.contacts.ContactsEvent;
import piuk.blockchain.android.data.contacts.ContactsPredicates;
import piuk.blockchain.android.data.datamanagers.BuyDataManager;
import piuk.blockchain.android.data.datamanagers.ContactsDataManager;
import piuk.blockchain.android.data.datamanagers.FeeDataManager;
import piuk.blockchain.android.data.datamanagers.PayloadDataManager;
import piuk.blockchain.android.data.datamanagers.PromptManager;
import piuk.blockchain.android.data.payments.SendDataManager;
import piuk.blockchain.android.data.datamanagers.TransactionListDataManager;
import piuk.blockchain.android.data.exchange.WebViewLoginDetails;
import piuk.blockchain.android.data.notifications.NotificationPayload;
import piuk.blockchain.android.data.notifications.NotificationTokenManager;
import piuk.blockchain.android.data.rxjava.RxBus;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.data.services.EventService;
import piuk.blockchain.android.data.services.ExchangeService;
import piuk.blockchain.android.data.services.WalletService;
import piuk.blockchain.android.data.settings.SettingsDataManager;
import piuk.blockchain.android.data.websocket.WebSocketService;
import piuk.blockchain.android.injection.Injector;
import piuk.blockchain.android.ui.base.BaseViewModel;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.ExchangeRateFactory;
import piuk.blockchain.android.util.OSUtil;
import piuk.blockchain.android.util.PrefsUtil;
import piuk.blockchain.android.util.StringUtils;

@SuppressWarnings("WeakerAccess")
public class MainViewModel extends BaseViewModel {

    private static final String TAG = MainViewModel.class.getSimpleName();

    private DataListener dataListener;
    private OSUtil osUtil;
    private Observable<NotificationPayload> notificationObservable;
    @Inject protected PrefsUtil prefs;
    @Inject protected AppUtil appUtil;
    @Inject protected AccessState accessState;
    @Inject protected PayloadManager payloadManager;
    @Inject protected PayloadDataManager payloadDataManager;
    @Inject protected ContactsDataManager contactsDataManager;
    @Inject protected SendDataManager sendDataManager;
    @Inject protected NotificationTokenManager notificationTokenManager;
    @Inject protected Context applicationContext;
    @Inject protected StringUtils stringUtils;
    @Inject protected SettingsDataManager settingsDataManager;
    @Inject protected BuyDataManager buyDataManager;
    @Inject protected DynamicFeeCache dynamicFeeCache;
    @Inject protected ExchangeRateFactory exchangeRateFactory;
    @Inject protected RxBus rxBus;
    @Inject protected FeeDataManager feeDataManager;
    @Inject protected EnvironmentSettings environmentSettings;
    @Inject protected TransactionListDataManager transactionListDataManager;
    @Inject protected PromptManager promptManager;

    public interface DataListener {

        /**
         * We can't simply call BuildConfig.CONTACTS_ENABLED in this class as it would make it
         * impossible to test, as it's reliant on the build.gradle config. Passing it here
         * allows us to change the response via mocking the DataListener.
         *
         * TODO: This should be removed once/if Contacts ships
         */
        boolean getIfContactsEnabled();

        boolean isBuySellPermitted();

        void onScanInput(String strUri);

        void onStartContactsActivity(@Nullable String data);

        void onStartBalanceFragment(boolean paymentToContactMade);

        void kickToLauncherPage();

        void showProgressDialog(@StringRes int message);

        void hideProgressDialog();

        void clearAllDynamicShortcuts();

        void showMetadataNodeRegistrationFailure();

        void showBroadcastFailedDialog(String mdid, String txHash, String facilitatedTxId, long transactionValue);

        void showBroadcastSuccessDialog();

        void updateCurrentPrice(String price);

        void setBuySellEnabled(boolean enabled);

        void onTradeCompleted(String txHash);

        void setWebViewLoginDetails(WebViewLoginDetails webViewLoginDetails);

        void showDefaultPrompt(AlertDialog alertDialog);

        void showCustomPrompt(AppCompatDialogFragment alertFragment);

        Context getActivityContext();
    }

    public MainViewModel(DataListener dataListener) {
        Injector.getInstance().getPresenterComponent().inject(this);
        this.dataListener = dataListener;
        osUtil = new OSUtil(applicationContext);
    }

    public void initPrompts(Context context) {

        compositeDisposable.add(
                promptManager.getDefaultPrompts(context)
                        .flatMap(Observable::fromIterable)
                        .forEach(dataListener::showDefaultPrompt));

        compositeDisposable.add(
                settingsDataManager.getSettings()
                        .flatMap(settings -> promptManager.getCustomPrompts(context, settings))
                        .flatMap(Observable::fromIterable)
                        .forEach(dataListener::showCustomPrompt));
    }

    @Override
    public void onViewReady() {

        if (!accessState.isLoggedIn()) {
            // This should never happen, but handle the scenario anyway by starting the launcher
            // activity, which handles all login/auth/corruption scenarios itself
            dataListener.kickToLauncherPage();
        } else {

            startWebSocketService();
            logEvents();

            dataListener.showProgressDialog(R.string.please_wait);

            compositeDisposable.add(registerMetadataNodesCompletable()
                    .mergeWith(feesCompletable())
                    .doAfterTerminate(() -> {
                                dataListener.hideProgressDialog();

                                dataListener.onStartBalanceFragment(false);

                                initPrompts(dataListener.getActivityContext());

                                if (!prefs.getValue(PrefsUtil.KEY_SCHEME_URL, "").isEmpty()) {
                                    String strUri = prefs.getValue(PrefsUtil.KEY_SCHEME_URL, "");
                                    prefs.removeValue(PrefsUtil.KEY_SCHEME_URL);
                                    dataListener.onScanInput(strUri);
                                }
                            }
                    )
                    .subscribe(() -> {
                        if (dataListener.isBuySellPermitted()) {
                            initBuyService();
                        }
                        if (dataListener.getIfContactsEnabled()) {
                            initContactsService();
                        }
                    }, throwable -> {
                        //noinspection StatementWithEmptyBody
                        if (throwable instanceof InvalidCredentialsException) {
                            // Double encrypted and not previously set up, ignore error
                        } else {
                            dataListener.showMetadataNodeRegistrationFailure();
                        }
                    }));
        }
    }

    private Completable feesCompletable() {
        return feeDataManager.getFeeOptions()
                .doOnNext(feeOptions -> dynamicFeeCache.setFeeOptions(feeOptions))
                .compose(RxUtil.applySchedulersToObservable())
                .flatMapCompletable(feeOptions -> exchangeRateFactory.updateTicker());
    }

    void broadcastPaymentSuccess(String mdid, String txHash, String facilitatedTxId, long transactionValue) {
        compositeDisposable.add(
                // Get contacts
                contactsDataManager.getContactList()
                        // Find contact by MDID
                        .filter(ContactsPredicates.filterByMdid(mdid))
                        // Get FacilitatedTransaction from HashMap
                        .flatMap(contact -> Observable.just(contact.getFacilitatedTransactions().get(facilitatedTxId)))
                        // Check the payment value was appropriate
                        .flatMapCompletable(transaction -> {
                            // Broadcast payment to shared metadata service
                            return contactsDataManager.sendPaymentBroadcasted(mdid, txHash, facilitatedTxId)
                                    // Show successfully broadcast
                                    .doOnComplete(() -> dataListener.showBroadcastSuccessDialog())
                                    // Show retry dialog if broadcast failed
                                    .doOnError(throwable -> dataListener.showBroadcastFailedDialog(mdid, txHash, facilitatedTxId, transactionValue));
                        })
                        .doAfterTerminate(() -> dataListener.hideProgressDialog())
                        .doOnSubscribe(disposable -> dataListener.showProgressDialog(R.string.contacts_broadcasting_payment))
                        .subscribe(
                                () -> {
                                    // No-op
                                }, throwable -> {
                                    // Not sure if it's worth notifying people at this point? Dialogs are advisory anyway.
                                }));
    }

    void checkForMessages() {
        compositeDisposable.add(contactsDataManager.fetchContacts()
                .andThen(contactsDataManager.getContactList())
                .toList()
                .flatMapObservable(contacts -> {
                    if (!contacts.isEmpty()) {
                        return contactsDataManager.getMessages(true);
                    } else {
                        return Observable.just(Collections.emptyList());
                    }
                })
                .subscribe(messages -> {
                    // No-op
                }, throwable -> Log.e(TAG, "checkForMessages: ", throwable)));
    }

    void unPair() {
        dataListener.clearAllDynamicShortcuts();
        payloadManager.wipe();
        prefs.logOut();
        accessState.unpairWallet();
        appUtil.restartApp();
        accessState.setPIN(null);
        ExchangeService.getInstance().wipe();
    }

    PayloadManager getPayloadManager() {
        return payloadManager;
    }

    private void subscribeToNotifications() {
        notificationObservable = rxBus.register(NotificationPayload.class);

        compositeDisposable.add(
                notificationObservable
                        .compose(RxUtil.applySchedulersToObservable())
                        .subscribe(
                                notificationPayload -> checkForMessages(),
                                Throwable::printStackTrace));
    }

    @Override
    public void destroy() {
        super.destroy();
        rxBus.unregister(NotificationPayload.class, notificationObservable);
        appUtil.deleteQR();
        dismissAnnouncementIfOnboardingCompleted();
    }

    public void updateTicker() {
        compositeDisposable.add(
                exchangeRateFactory.updateTicker()
                        .subscribe(
                                () -> dataListener.updateCurrentPrice(getFormattedPriceString()),
                                Throwable::printStackTrace));
    }

    private String getFormattedPriceString() {
        String fiat = prefs.getValue(PrefsUtil.KEY_SELECTED_FIAT, "");
        double lastPrice = exchangeRateFactory.getLastPrice(fiat);
        String fiatSymbol = exchangeRateFactory.getSymbol(fiat);
        DecimalFormat format = new DecimalFormat();
        format.setMinimumFractionDigits(2);
        return stringUtils.getFormattedString(
                R.string.current_price_btc,
                fiatSymbol + format.format(lastPrice));
    }

    private void startWebSocketService() {
        Intent intent = new Intent(applicationContext, WebSocketService.class);

        if (!osUtil.isServiceRunning(WebSocketService.class)) {
            applicationContext.startService(intent);
        } else {
            // Restarting this here ensures re-subscription after app restart - the service may remain
            // running, but the subscription to the WebSocket won't be restarted unless onCreate called
            applicationContext.stopService(intent);
            applicationContext.startService(intent);
        }
    }

    private void logEvents() {
        EventService handler = new EventService(prefs, new WalletService(new WalletApi()));
        handler.log2ndPwEvent(payloadManager.getPayload().isDoubleEncryption());
        handler.logBackupEvent(payloadManager.getPayload().getHdWallets().get(0).isMnemonicVerified());

        try {
            BigInteger importedAddressesBalance = payloadManager.getImportedAddressesBalance();
            if (importedAddressesBalance != null) {
                handler.logLegacyEvent(importedAddressesBalance.longValue() > 0L);
            }
        } catch (Exception e) {
            Log.e(TAG, "logEvents: ", e);
        }
    }

    String getCurrentServerUrl() {
        return environmentSettings.getExplorerUrl();
    }

    private Completable registerMetadataNodesCompletable() {
        return payloadDataManager.loadNodes()
                .doOnNext(aBoolean -> subscribeToNotifications())
                .flatMap(loaded -> {
                    if (loaded) {
                        return payloadDataManager.getMetadataNodeFactory();
                    } else {
                        if (!payloadManager.getPayload().isDoubleEncryption()) {
                            return payloadDataManager.generateNodes(null)
                                    .andThen(payloadDataManager.getMetadataNodeFactory());
                        } else {
                            throw new InvalidCredentialsException("Payload is double encrypted");
                        }
                    }
                })
                .flatMapCompletable(metadataNodeFactory -> contactsDataManager
                        .initContactsService(metadataNodeFactory.getMetadataNode(), metadataNodeFactory.getSharedMetadataNode()));
    }

    private void initContactsService() {

        String uri = null;
        boolean fromNotification = false;

        if (!prefs.getValue(PrefsUtil.KEY_METADATA_URI, "").isEmpty()) {
            uri = prefs.getValue(PrefsUtil.KEY_METADATA_URI, "");
            prefs.removeValue(PrefsUtil.KEY_METADATA_URI);
        }

        if (prefs.getValue(PrefsUtil.KEY_CONTACTS_NOTIFICATION, false)) {
            prefs.removeValue(PrefsUtil.KEY_CONTACTS_NOTIFICATION);
            fromNotification = true;
        }

        final String finalUri = uri;
        if (finalUri != null || fromNotification) {
            dataListener.showProgressDialog(R.string.please_wait);
        }

        rxBus.emitEvent(ContactsEvent.class, ContactsEvent.INIT);

        if (uri != null) {
            dataListener.onStartContactsActivity(uri);
        } else if (fromNotification) {
            dataListener.onStartContactsActivity(null);
        } else {
            checkForMessages();
        }
    }

    private void initBuyService() {
        compositeDisposable.add(
                buyDataManager.getCanBuy()
                        .subscribe(isEnabled -> {
                                    dataListener.setBuySellEnabled(isEnabled);
                                    if (isEnabled) {
                                        buyDataManager.watchPendingTrades()
                                                .compose(RxUtil.applySchedulersToObservable())
                                                .subscribe(dataListener::onTradeCompleted, Throwable::printStackTrace);

                                        buyDataManager.getWebViewLoginDetails()
                                                .subscribe(dataListener::setWebViewLoginDetails, Throwable::printStackTrace);
                                    }
                                },
                                throwable -> Log.e(TAG, "preLaunchChecks: ", throwable)));
    }

    private void dismissAnnouncementIfOnboardingCompleted() {
        if (prefs.getValue(PrefsUtil.KEY_ONBOARDING_COMPLETE, false)
                && prefs.getValue(PrefsUtil.KEY_LATEST_ANNOUNCEMENT_SEEN, false)) {
            prefs.setValue(PrefsUtil.KEY_LATEST_ANNOUNCEMENT_DISMISSED, true);
        }
    }
}
