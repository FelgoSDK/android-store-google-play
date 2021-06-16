/* Copyright (c) 2012 Google Inc.
 * Revised and edited by SOOMLA for stability and supporting new features.
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

package com.soomla.store.billing.google;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.soomla.SoomlaApp;
import com.soomla.SoomlaConfig;
import com.soomla.SoomlaUtils;
import com.soomla.store.billing.IabException;
import com.soomla.store.billing.IabHelper;
import com.soomla.store.billing.IabInventory;
import com.soomla.store.billing.IabPurchase;
import com.soomla.store.billing.IabResult;
import com.soomla.store.billing.IabSkuDetails;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * This is an implementation of SOOMLA's IabHelper to create a plugin of Google Play to SOOMLA.
 *
 * More docs in parent.
 */
public class GoogleIabHelper extends IabHelper implements PurchasesUpdatedListener {

    // uncomment to verify big chunk google bug (over 20)
//    public static final int SKU_QUERY_MAX_CHUNK_SIZE = 50;


    public static final int SKU_QUERY_MAX_CHUNK_SIZE = 19;

    // Keys for the responses from InAppBillingService
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE = "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST = "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN = "INAPP_CONTINUATION_TOKEN";

    // some fields on the getSkuDetails response bundle
    public static final String GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST";
    public static final String GET_SKU_DETAILS_ITEM_TYPE_LIST = "ITEM_TYPE_LIST";

    /**
     * Creates an instance. After creation, it will not yet be ready to use. You must perform
     * setup by calling {@link #startSetup} and wait for setup to complete. This constructor does not
     * block and is safe to call from a UI thread.
     */
    public GoogleIabHelper() {
        SoomlaUtils.LogDebug(TAG, "GoogleIabHelper helper created.");
    }

    /**
     * See parent
     */
    protected void startSetupInner() {
        mService = BillingClient.newBuilder(SoomlaApp.getAppContext())
            .setListener(this)
            .enablePendingPurchases()
            .build();

        mService.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                SoomlaUtils.LogDebug(TAG, "Billing service connected.");
                String packageName = SoomlaApp.getAppContext().getPackageName();
                SoomlaUtils.LogDebug(TAG, "Checking for in-app billing 3 support.");

                // check for in-app billing v3 support

                // In app seems to always be supported (no way to check for it)
                int inAppResponse = BillingClient.BillingResponseCode.OK;// mService.isFeatureSupported(BillingClient.FeatureType.IN_APP).getResponseCode();
                int subsResponse = mService.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode();

                if (inAppResponse != BillingClient.BillingResponseCode.OK
                    || subsResponse != BillingClient.BillingResponseCode.OK) {
                    setupFailed(new IabResult(inAppResponse != 0 ? inAppResponse : subsResponse, "Error checking for billing v3 support."));
                    return;
                }
                SoomlaUtils.LogDebug(TAG, "In-app billing version 3 supported for " + packageName);

                setupSuccess();
            }

            @Override public void onBillingServiceDisconnected() {
                SoomlaUtils.LogDebug(TAG, "Billing service disconnected.");
                mService = null;
            }
        });
    }

    @Override public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
        IabResult result;

        int responseCode = billingResult.getResponseCode();

        checkSetupDoneAndThrow("onPurchasesUpdated");

        // cancellation moved here b/c there can be a cancellation with null data
        if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            SoomlaUtils.LogDebug(TAG, "IabPurchase canceled.");
            try {
                IabPurchase purchase = new IabPurchase(mPurchasingItemType, "{\"productId\":" + mPurchasingItemSku + "}", null);
                result = new IabResult(IabResult.BILLING_RESPONSE_RESULT_USER_CANCELED, "User canceled.");
                purchaseFailed(result, purchase);
                return;
            } catch (JSONException e) {
                SoomlaUtils.LogError(TAG, "Failed to generate canceled purchase.");
                e.printStackTrace();
                result = new IabResult(IabResult.IABHELPER_BAD_RESPONSE, "Failed to generate canceled purchase.");
                purchaseFailed(result, null);
                return;
            }
        }

        if (responseCode == IabResult.BILLING_RESPONSE_RESULT_OK) {
            SoomlaUtils.LogDebug(TAG, "Successful resultcode from purchase activity.");
            SoomlaUtils.LogDebug(TAG, "Expected item type: " + mPurchasingItemType);

            if (list.isEmpty()) {
                SoomlaUtils.LogError(TAG, "BUG: purchase list is empty.");
                result = new IabResult(IabResult.IABHELPER_UNKNOWN_ERROR, "IAB returned empty purchase list.");
                purchaseFailed(result, null);
                return;
            }

            for(Purchase p : list) {
                IabPurchase purchase = null;
                try {
                    purchase = new IabPurchase(mPurchasingItemType, p.getOriginalJson(), p.getSignature());
                    String sku = purchase.getSku();

                    SharedPreferences prefs =
                        SoomlaApp.getAppContext().getSharedPreferences(SoomlaConfig.PREFS_NAME, Context.MODE_PRIVATE);
                    String publicKey = prefs.getString(GooglePlayIabService.PUBLICKEY_KEY, "");

                    // Verify signature
                    if (!Security.verifyPurchase(publicKey, p.getOriginalJson(), p.getSignature())) {
                        SoomlaUtils.LogError(TAG, "IabPurchase signature verification FAILED for sku " + sku);
                        result = new IabResult(IabResult.IABHELPER_VERIFICATION_FAILED, "Signature verification failed for sku " + sku);
                        purchaseFailed(result, purchase);
                        continue;
                    }
                    SoomlaUtils.LogDebug(TAG, "IabPurchase signature successfully verified.");
                }
                catch (JSONException e) {
                    SoomlaUtils.LogError(TAG, "Failed to parse purchase data.");
                    e.printStackTrace();
                    result = new IabResult(IabResult.IABHELPER_BAD_RESPONSE, "Failed to parse purchase data.");
                    purchaseFailed(result, null);
                    continue;
                }

                purchaseSucceeded(purchase);
            }
        }
        else {
            SoomlaUtils.LogError(TAG, "IabPurchase failed. Response code: " + responseCode
                + " - " + IabResult.getResponseDesc(responseCode));
            result = new IabResult(IabResult.IABHELPER_UNKNOWN_PURCHASE_RESPONSE, "Unknown purchase response.");
            purchaseFailed(result, null);
        }
    }


    /**
     * Dispose of object, releasing resources. It's very important to call this
     * method when you are done with this object. It will release any resources
     * used by it such as service connections. Naturally, once the object is
     * disposed of, it can't be used again.
     */
    public void dispose() {
        SoomlaUtils.LogDebug(TAG, "Disposing.");
        super.dispose();

        if(mService != null) {
            SoomlaUtils.LogDebug(TAG, "End BillingService connection.");
            mService.endConnection();
            mService = null;
        }
    }

    /**
        unused with billing library 4.0.0 - callbacks are in onPurchasesUpdated().
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }

    /**
     * Consumes a given in-app product. Consuming can only be done on an item
     * that's owned, and as a result of consumption, the user will no longer own it.
     * This method may block or take long to return. Do not call from the UI thread.
     * For that, see {@link #consumeAsync}.
     *
     * @param itemInfo The PurchaseInfo that represents the item to consume.
     * @throws IabException if there is a problem during consumption.
     */
     public void consume(IabPurchase itemInfo) throws IabException {
         checkSetupDoneAndThrow("consume");

        if (!itemInfo.getItemType().equals(ITEM_TYPE_INAPP)) {
            throw new IabException(IabResult.IABHELPER_INVALID_CONSUMPTION,
                    "Items of type '" + itemInfo.getItemType() + "' can't be consumed.");
        }

         String token = itemInfo.getToken();
         final String sku = itemInfo.getSku();
         if (token == null || token.equals("")) {
             SoomlaUtils.LogError(TAG, "Can't consume " + sku + ". No token.");
             throw new IabException(IabResult.IABHELPER_MISSING_TOKEN, "PurchaseInfo is missing token for sku: "
                 + sku + " " + itemInfo);
         }

         SoomlaUtils.LogDebug(TAG, "Consuming sku: " + sku + ", token: " + token);

         ConsumeParams params = ConsumeParams.newBuilder()
             .setPurchaseToken(token)
             .build();

         mService.consumeAsync(params, new ConsumeResponseListener() {
             @Override public void onConsumeResponse(BillingResult billingResult, String s) {
                 int response = billingResult.getResponseCode();
                 if (response == BillingClient.BillingResponseCode.OK) {
                     SoomlaUtils.LogDebug(TAG, "Successfully consumed sku: " + sku);
                 } else {
                     SoomlaUtils.LogDebug(TAG, "Error consuming sku " + sku + ". " +
                         IabResult.getResponseDesc(response));
                     // TODO how to report error?
                     // throw new IabException(response, "Error consuming sku " + sku);
                 }
             }
         });
    }



    /**
     * Asynchronous wrapper to item consumption. Works like {@link #consume}, but
     * performs the consumption in the background and notifies completion through
     * the provided listener. This method is safe to call from a UI thread.
     *
     * @param purchase The purchase to be consumed.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    public void consumeAsync(IabPurchase purchase, OnConsumeFinishedListener listener) {
        checkSetupDoneAndThrow("consume");
        List<IabPurchase> purchases = new ArrayList<IabPurchase>();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener, null);
    }

    /**
     * Same as {@link #consumeAsync}, but for multiple items at once.
     * @param purchases The list of PurchaseInfo objects representing the purchases to consume.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    public void consumeAsync(List<IabPurchase> purchases, OnConsumeMultiFinishedListener listener) {
        checkSetupDoneAndThrow("consume");
        consumeAsyncInternal(purchases, null, listener);
    }
    /**
     * Callback that notifies when a consumption operation finishes.
     */
    public interface OnConsumeFinishedListener {
        /**
         * Called to notify that a consumption has finished.
         *
         * @param purchase The purchase that was (or was to be) consumed.
         * @param result The result of the consumption operation.
         */
        public void onConsumeFinished(IabPurchase purchase, IabResult result);
    }

    /**
     * Callback that notifies when a multi-item consumption operation finishes.
     */
    public interface OnConsumeMultiFinishedListener {
        /**
         * Called to notify that a consumption of multiple items has finished.
         *
         * @param purchases The purchases that were (or were to be) consumed.
         * @param results The results of each consumption operation, corresponding to each
         *     sku.
         */
        public void onConsumeMultiFinished(List<IabPurchase> purchases, List<IabResult> results);
    }


    /** Protected functions **/

    /**
     * see parent
     */
    @Override
    protected void restorePurchasesAsyncInner() {
        (new Thread(new Runnable() {
            public void run() {
                IabInventory inv = null;
                try {
                    inv = restorePurchases();
                }
                catch (IabException ex) {
                    IabResult result = ex.getResult();
                    restorePurchasesFailed(result);
                    return;
                }

                restorePurchasesSuccess(inv);
            }
        })).start();
    }

    /**
     * see parent
     */
    @Override
    protected void fetchSkusDetailsAsyncInner(final List<String> skus) {
        final IabInventory inv = new IabInventory();

        String[] types = { BillingClient.SkuType.INAPP, BillingClient.SkuType.SUBS };
        final List<String> finishedTypes = new ArrayList<>();

        for(final String type: types) {
            fetchSkusDetailsAsyncForType(inv, skus, type, new Runnable() {
                @Override public void run() {
                    finishedTypes.add(type);

                    if(finishedTypes.size() == 2) {
                        // both subs and inapp type are loaded
                        fetchSkusDetailsSuccess(inv);
                    }
                }
            });
        }
    }

    private void fetchSkusDetailsAsyncForType(final IabInventory inv, List<String> skus,
                                              String type, final Runnable onFinished) {
        SkuDetailsParams params = SkuDetailsParams.newBuilder()
            .setSkusList(skus)
            .setType(type)
            .build();

        mService.querySkuDetailsAsync(params, new SkuDetailsResponseListener() {
            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> list) {

                for(SkuDetails detail: list) {
                    try {
                        inv.addSkuDetails(new IabSkuDetails(detail.getType(), detail.getOriginalJson()));
                    } catch (JSONException e) {
                        fetchSkusDetailsFailed(new IabResult(
                            IabResult.IABHELPER_BAD_RESPONSE,
                            "Error parsing JSON response while refreshing inventory.")
                        );
                    }
                }

                onFinished.run();
            }
        });
    }

    /**
     * See parent
     */
    @Override
    protected void launchPurchaseFlowInner(Activity act, String itemType, final String sku, String extraData) {

        if (!(itemType.equals(ITEM_TYPE_INAPP) || itemType.equals(ITEM_TYPE_SUBS))) {
            throw new IllegalArgumentException("Wrong purchase item type: " + itemType);
        }

        mPurchasingItemSku = sku;
        mPurchasingItemType = itemType;
        SoomlaUtils.LogDebug(TAG, "Launching buy intent for " + sku + ". Request code: " + RC_REQUEST);

        List<String> skuList = new ArrayList<>();
        skuList.add(sku);
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(itemType);
        mService.querySkuDetailsAsync(params.build(),
            new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(BillingResult billingResult,
                                                 List<SkuDetails> skuDetailsList) {
                    for (SkuDetails skuObj : skuDetailsList) {
                        if (skuObj.getSku().equals(sku)) {

                            // Process the result.
                            BillingFlowParams purchaseParams =
                                BillingFlowParams.newBuilder()
                                    .setSkuDetails(skuObj)
                                    .build();

                            mService.launchBillingFlow(SoomlaApp.getActivity(), purchaseParams);

                            return;
                        }
                    }

                    // SKU not found!
                    SoomlaUtils.LogError(TAG, "Could not find SKU: " + sku);

                    IabResult iabResult = new IabResult(IabResult.IABHELPER_BAD_RESPONSE, "Failed to generate failing purchase.");
                    purchaseFailed(iabResult, null);
                }
            }
        );
    }


    /** Private functions **/

    /**
     * The inner functions that consumes purchases.
     *
     * @param purchases the purchases to consume.
     * @param singleListener The listener to invoke when the consumption completes.
     * @param multiListener Multi listener for when we have multiple consumption operations.
     */
    private void consumeAsyncInternal(final List<IabPurchase> purchases,
                                      final OnConsumeFinishedListener singleListener,
                                      final OnConsumeMultiFinishedListener multiListener) {
        final Handler handler = new Handler();
        flagStartAsync("consume");
        (new Thread(new Runnable() {
            public void run() {
                final List<IabResult> results = new ArrayList<IabResult>();
                for (IabPurchase purchase : purchases) {
                    try {
                        consume(purchase);
                        results.add(new IabResult(IabResult.BILLING_RESPONSE_RESULT_OK, "Successful consume of sku " + purchase.getSku()));
                    }
                    catch (IabException ex) {
                        results.add(ex.getResult());
                    }
                }

                flagEndAsync();

                if (singleListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            singleListener.onConsumeFinished(purchases.get(0), results.get(0));
                        }
                    });
                }
                if (multiListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            multiListener.onConsumeMultiFinished(purchases, results);
                        }
                    });
                }
            }
        })).start();
    }

    /**
     * Restores purchases from Google Play.
     *
     * @throws JSONException
     * @throws RemoteException
     */
    private int queryPurchases(IabInventory inv, String itemType) throws JSONException, RemoteException {
        // Query purchases
        SoomlaUtils.LogDebug(TAG, "Querying owned items, item type: " + itemType);
        SoomlaUtils.LogDebug(TAG, "Package name: " + SoomlaApp.getAppContext().getPackageName());
        boolean verificationFailed = false;

        if (mService == null) {
            SoomlaUtils.LogWarning(TAG, "Service was null in queryPurchases().");
            return IabResult.IABHELPER_UNKNOWN_ERROR;
        }
        Purchase.PurchasesResult purchases = mService.queryPurchases(itemType);
        List<Purchase> ownedItems = purchases.getPurchasesList();

        int response = purchases.getResponseCode();
        SoomlaUtils.LogDebug(TAG, "Owned items response: " + String.valueOf(response));
        if (response != IabResult.BILLING_RESPONSE_RESULT_OK) {
            SoomlaUtils.LogDebug(TAG, "getPurchases() failed: " + IabResult.getResponseDesc(response));
            return response;
        }

        SharedPreferences prefs = SoomlaApp.getAppContext()
            .getSharedPreferences(SoomlaConfig.PREFS_NAME, Context.MODE_PRIVATE);
        String publicKey = prefs.getString(GooglePlayIabService.PUBLICKEY_KEY, "");

        if(ownedItems != null) {
            for (Purchase p : ownedItems) {
                String purchaseData = p.getOriginalJson();
                String signature = p.getSignature();
                ArrayList<String> skus = p.getSkus();

                if (Security.verifyPurchase(publicKey, purchaseData, signature)) {
                    SoomlaUtils.LogDebug(TAG, "Sku is owned: " + skus);
                    IabPurchase purchase = new IabPurchase(itemType, purchaseData, signature);

                    if (TextUtils.isEmpty(purchase.getToken())) {
                        SoomlaUtils.LogWarning(TAG, "BUG: empty/null token!");
                        SoomlaUtils.LogDebug(TAG, "IabPurchase data: " + purchaseData);
                    }

                    // Record ownership and token
                    inv.addPurchase(purchase);
                } else {
                    SoomlaUtils.LogWarning(TAG, "IabPurchase signature verification **FAILED**. Not adding item.");
                    SoomlaUtils.LogDebug(TAG, "   IabPurchase data: " + purchaseData);
                    SoomlaUtils.LogDebug(TAG, "   Signature: " + signature);
                    verificationFailed = true;
                }
            }
        }

        return verificationFailed ? IabResult.IABHELPER_VERIFICATION_FAILED : IabResult.BILLING_RESPONSE_RESULT_OK;
    }

    /**
     * Retrieves all items that were purchase but not consumed.
     *
     * @throws IabException
     */
    private IabInventory restorePurchases() throws IabException {
        checkSetupDoneAndThrow("restorePurchases");
        try {
            IabInventory inv = new IabInventory();
            int inAppResult = queryPurchases(inv, ITEM_TYPE_INAPP);
            int subsResult = queryPurchases(inv, ITEM_TYPE_SUBS);
            if (inAppResult != IabResult.BILLING_RESPONSE_RESULT_OK
                    || subsResult != IabResult.BILLING_RESPONSE_RESULT_OK) {
                throw new IabException(inAppResult != 0 ? inAppResult : subsResult, "Error refreshing inventory (querying owned items).");
            }
            return inv;
        }
        catch (RemoteException e) {
            throw new IabException(IabResult.IABHELPER_REMOTE_EXCEPTION, "Remote exception while refreshing inventory.", e);
        }
        catch (JSONException e) {
            throw new IabException(IabResult.IABHELPER_BAD_RESPONSE, "Error parsing JSON response while refreshing inventory.", e);
        }
    }

    /**
     * Workaround to bug where sometimes response codes come as Long instead of Integer
     */
    private int getResponseCodeFromBundle(Bundle b) {
        Object o = b.get(RESPONSE_CODE);
        if (o == null) {
            SoomlaUtils.LogDebug(TAG, "Bundle with null response code, assuming OK (known issue)");
            return IabResult.BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            SoomlaUtils.LogError(TAG, "Unexpected type for bundle response code.");
            SoomlaUtils.LogError(TAG, o.getClass().getName());
            throw new RuntimeException("Unexpected type for bundle response code: " + o.getClass().getName());
        }
    }

    /**
     * Workaround to bug where sometimes response codes come as Long instead of Integer
     */
    private int getResponseCodeFromIntent(Intent i) {
        Object o = i.getExtras().get(RESPONSE_CODE);
        if (o == null) {
            SoomlaUtils.LogError(TAG, "Intent with no response code, assuming OK (known issue)");
            return IabResult.BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            SoomlaUtils.LogError(TAG, "Unexpected type for intent response code.");
            SoomlaUtils.LogError(TAG, o.getClass().getName());
            throw new RuntimeException("Unexpected type for intent response code: " + o.getClass().getName());
        }
    }

    /** Private Members **/

    private static String TAG = "SOOMLA GoogleIabHelper";

    // Connection to the service
    private BillingClient mService;

    // The item type of the current purchase flow
    private String mPurchasingItemType;

    // The SKU of the item in the current purchase flow
    private String mPurchasingItemSku;

    private static final int RC_REQUEST = 10001;

}
