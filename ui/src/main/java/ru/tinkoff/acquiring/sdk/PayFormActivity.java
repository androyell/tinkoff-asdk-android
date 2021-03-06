/*
 * Copyright © 2016 Tinkoff Bank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.tinkoff.acquiring.sdk;


import android.app.FragmentManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.MenuItem;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ru.tinkoff.acquiring.sdk.responses.AcquiringResponse;


/**
 * Экран оплаты. Позволяет посмотреть данные платежа, ввести данные карты для оплаты, проходить в
 * случае необходимости 3DS, управлять ранее сохраненными картами.
 *
 * @author a.shishkin1
 */
public final class PayFormActivity extends AppCompatActivity implements FragmentsCommunicator.IFragmentManagerExtender {

    public static final int RESULT_ERROR = 500;
    public static final String API_ERROR_NO_CUSTOMER = "7";

    static SdkHandler handler = new SdkHandler();

    static final String EXTRA_ERROR = "error";

    static final String EXTRA_TERMINAL_KEY = "terminal_key";
    static final String EXTRA_PASSWORD = "password";
    static final String EXTRA_PUBLIC_KEY = "public_key";

    static final String EXTRA_ORDER_ID = "order_id";
    static final String EXTRA_AMOUNT = "amount";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_DESCRIPTION = "description";
    static final String EXTRA_CARD_ID = "card_id";
    static final String EXTRA_E_MAIL = "email";
    static final String EXTRA_CUSTOM_KEYBOARD = "keyboard";
    static final String EXTRA_CUSTOMER_KEY = "customer_key";
    static final String EXTRA_RECURENT_PAYMENT = "reccurent_payment";
    static final String EXTRA_PAYMENT_ID = "payment_id";

    static final int RESULT_CODE_CLEAR_CARD = 101;

    private static final String INSTANCE_KEY_CARDS = "cards";
    private static final String INSTANCE_KEY_CARD_INDEX = "card_idx";

    private FragmentsCommunicator mFragmentsCommunicator;
    private Long paymentId;

    public static PayFormStarter init(String terminalKey, String password, String publicKey) {
        return new PayFormStarter(terminalKey, password, publicKey);
    }

    private DialogsManager dialogsManager;
    private AcquiringSdk sdk;
    private CardManager cardManager;
    private Card[] cards;
    private Card sourceCard;
    private boolean useCustomKeyboard;
    private boolean isCardsReady;


    AcquiringSdk getSdk() {
        return sdk;
    }

    CardManager getCardManager() {
        return cardManager;
    }

    Card[] getCards() {
        return cards;
    }

    Card getSourceCard() {
        return sourceCard;
    }

    void setSourceCard(Card sourceCard) {
        this.sourceCard = sourceCard;
    }

    boolean shouldUseCustomKeyboard() {
        return useCustomKeyboard;
    }

    public PayFormActivity() {
        mFragmentsCommunicator = new FragmentsCommunicator();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFragmentsCommunicator.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.acq_activity);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.acqPayFormTitle, tv, true);
        String title = getResources().getString(tv.resourceId);
        setTitle(title);

        dialogsManager = new DialogsManager(this);

        Intent intent = getIntent();

        String terminalKey = intent.getStringExtra(EXTRA_TERMINAL_KEY);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        String publicKey = intent.getStringExtra(EXTRA_PUBLIC_KEY);

        sdk = new AcquiringSdk(terminalKey, password, publicKey);
        cardManager = new CardManager(sdk);
        useCustomKeyboard = intent.getBooleanExtra(EXTRA_CUSTOM_KEYBOARD, false);

        if (savedInstanceState == null) {
            isCardsReady = false;
            startFinishAuthorized();
            if (isCardChooseEnable()) {
                String customerKey = getIntent().getStringExtra(EXTRA_CUSTOMER_KEY);
                requestCards(customerKey, cardManager);
            }
        } else {
            cards = new CardsArrayBundlePacker().unpack(savedInstanceState.getBundle(INSTANCE_KEY_CARDS));
            int idx = savedInstanceState.getInt(INSTANCE_KEY_CARD_INDEX, -1);
            if (idx != -1) {
                sourceCard = cards[idx];
            }
        }


    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mFragmentsCommunicator.onSavedInstanceState(outState);
        outState.putBundle(INSTANCE_KEY_CARDS, new CardsArrayBundlePacker().pack(cards));
        if (sourceCard != null && cards != null) {
            for (int i = 0; i < cards.length; i++) {
                if (sourceCard == cards[i]) {
                    outState.putInt(INSTANCE_KEY_CARD_INDEX, i);
                    break;
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        dialogsManager.dismissDialogs();
        handler.unregister(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            navigateBack();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    void showProgressDialog() {
        dialogsManager.showProgressDialog(getString(R.string.acq_progress_dialog_text));
    }

    void hideProgressDialog() {
        dialogsManager.hideProgressDialog();
    }

    void startFinishAuthorized() {
        Fragment fragment = new EnterCardFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, fragment)
                .commit();
    }

    void startThreeDs(ThreeDsData data) {
        hideProgressDialog();
        Fragment fragment = new ThreeDsFragment();
        Bundle args = new Bundle();
        args.putBundle(ThreeDsFragment.EXTRA_3DS, new ThreeDsBundlePacker().pack(data));
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, fragment)
                .commit();
    }

    void startChooseCard() {
        Fragment fragment = new CardListFragment();
        Bundle bundle = new Bundle();
        bundle.putString(CardListFragment.EXTRA_CUSTOMER_KEY, getIntent().getStringExtra(EXTRA_CUSTOMER_KEY));
        fragment.setArguments(bundle);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.content_frame, fragment)
                .addToBackStack("choose_card")
                .commit();
    }

    void finishChooseCards() {
        getSupportFragmentManager().popBackStack("choose_card", FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    void onCardsReady(Card[] cards) {
        this.cards = cards;
        if (!isCardsReady && sourceCard == null && cards != null && cards.length > 0) {
            String cardId = getIntent().getStringExtra(EXTRA_CARD_ID);
            if (cardId != null) {
                sourceCard = cardManager.getCardById(cardId);
            } else {
                sourceCard = cards[0];
            }
        }
        isCardsReady = true;
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (fragment != null && fragment instanceof ICardInterest) {
            ((ICardInterest) fragment).onCardReady();
        }
    }

    void onDeleteCard(Card card) {
        cardManager.clear(getIntent().getStringExtra(EXTRA_CUSTOMER_KEY));
        if (sourceCard == card) {
            sourceCard = null;
        }
        if (cards.length == 1 && cards[0] == card) {
            cards = null;
        } else {
            List<Card> list = new ArrayList<>(Arrays.asList(cards));
            list.remove(card);
            Card[] array = new Card[list.size()];
            list.toArray(array);
            cards = array;
        }
        onCardsReady(cards);
    }

    void showErrorDialog(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            message = getString(R.string.acq_default_error_message);
        }
        dialogsManager.showErrorDialog(getString(R.string.acq_default_error_title), message);
    }

    static void requestCards(final String customerKey, final CardManager cardManager) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Card[] cards = cardManager.getCards(customerKey);
                    PayFormActivity.handler.obtainMessage(SdkHandler.CARDS_READY, cards).sendToTarget();
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause != null && cause instanceof AcquiringApiException) {
                        // handle NO SUCH CUSTOMER api error
                        AcquiringResponse apiResponse = ((AcquiringApiException) cause).getResponse();
                        if (apiResponse != null && API_ERROR_NO_CUSTOMER.equals(apiResponse.getErrorCode())) {
                            PayFormActivity.handler.obtainMessage(SdkHandler.CARDS_READY, null).sendToTarget();
                        } else {
                            throw e;
                        }
                    } else {
                        // ignore non api exception
                    }
                }
            }
        }).start();
    }

    void onPaymentInitCompleted(final Long paymentId) {
        this.paymentId = paymentId;
    }

    void announceSuccess() {
        hideProgressDialog();
        final Intent data = new Intent();
        data.putExtra(EXTRA_PAYMENT_ID, paymentId);
        setResult(RESULT_OK, data);
        if (isCardChooseEnable()) {
            cardManager.clear(getIntent().getStringExtra(EXTRA_CUSTOMER_KEY));
        }
        finish();
    }

    void announceException(Exception e) {
        hideProgressDialog();

        if (e != null && e.getCause() instanceof NetworkException) {
            Toast.makeText(this, R.string.acq_network_error_message, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent data = new Intent();
        data.putExtra(EXTRA_ERROR, e);
        setResult(RESULT_ERROR, data);
        finish();
    }

    public boolean isCardChooseEnable() {
        return getIntent().getStringExtra(EXTRA_CUSTOMER_KEY) != null;
    }

    @Override
    public void onBackPressed() {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (fragment instanceof OnBackPressedListener) {
            final OnBackPressedListener listener = ((OnBackPressedListener) fragment);
            if (listener.onBackPressed()) {
                return;
            }
        }

        navigateBack();
    }

    private void navigateBack() {
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            setResult(RESULT_CANCELED);
        }
        super.onBackPressed();
    }

    public static void dispatchResult(int resultCode, Intent data, OnPaymentListener listener) {
        if (resultCode == RESULT_OK) {
            listener.onSuccess(data.getLongExtra(EXTRA_PAYMENT_ID, -1L));
        } else if (resultCode == RESULT_CANCELED) {
            listener.onCancelled();
        } else if (resultCode == RESULT_ERROR) {
            listener.onError((Exception) data.getSerializableExtra(EXTRA_ERROR));
        }
    }


    @Override
    public FragmentsCommunicator getFragmentsCommunicator() {
        return mFragmentsCommunicator;
    }
}
