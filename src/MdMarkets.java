package md.markets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import md.markets.IabHelper.OnConsumeFinishedListener;

public class MdMarkets extends CordovaPlugin {

    protected static final String TAG = "MilaDesign";

    public static final int OK = 0;
    public static final int INVALID_ARGUMENTS = -1;
    public static final int UNABLE_TO_INITIALIZE = -2;
    public static final int BILLING_NOT_INITIALIZED = -3;
    public static final int UNKNOWN_ERROR = -4;
    public static final int USER_CANCELLED = -5;
    public static final int BAD_RESPONSE_FROM_SERVER = -6;
    public static final int VERIFICATION_FAILED = -7;
    public static final int ITEM_UNAVAILABLE = -8;
    public static final int ITEM_ALREADY_OWNED = -9;
    public static final int ITEM_NOT_OWNED = -10;
    public static final int CONSUME_FAILED = -11;

    public static final int PURCHASE_PURCHASED = 0;
    public static final int PURCHASE_CANCELLED = 1;
    public static final int PURCHASE_REFUNDED = 2;

    private IabHelper iabHelper = null;
    boolean billingInitialized = false;
    AtomicInteger orderSerial = new AtomicInteger(0);

    protected boolean initializeBillingHelper(String PublicKey, int Market) {
        if (iabHelper != null) {
            Log.d(TAG, "Billing already initialized");
            return true;
        }
        Context context = this.cordova.getActivity();
        if (PublicKey != null) {
            iabHelper = new IabHelper(context, PublicKey, Market);
            iabHelper.setSkipPurchaseVerification(false);
            billingInitialized = false;
            return true;
        }
        Log.d(TAG, "Unable to initialize billing");
        return false;
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    protected JSONObject makeError(String message) {
        return makeError(message, null, null, null);
    }

    protected JSONObject makeError(String message, Integer resultCode) {
        return makeError(message, resultCode, null, null);
    }

    protected JSONObject makeError(String message, Integer resultCode, IabResult result) {
        return makeError(message, resultCode, result.getMessage(), result.getResponse());
    }

    protected JSONObject makeError(String message, Integer resultCode, String text, Integer response) {
        if (message != null) {
            Log.d(TAG, "Error: " + message);
        }
        JSONObject error = new JSONObject();
        try {
            if (resultCode != null) {
                error.put("code", (int) resultCode);
            }
            if (message != null) {
                error.put("message", message);
            }
            if (text != null) {
                error.put("text", text);
            }
            if (response != null) {
                error.put("response", response);
            }
        } catch (JSONException e) {}
        return error;
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "executing on android");
        if ("Initialize".equals(action)) {
            return Initialize(args, callbackContext);
        } else if ("RequestPayment".equals(action)) {
            return RequestPayment(args, callbackContext);
        } else if ("GetSkuDetails".equals(action)) {
            return GetSkuDetails(args, callbackContext);
        } else if ("GetOwnedProducts".equals(action)) {
            return GetOwnedProducts(args, callbackContext);
        }
        return false;
    }

    protected boolean Initialize(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        initializeBillingHelper(args.getString(0), args.getInt(1));
        if (billingInitialized == true) {
            Log.d(TAG, "Billing already initialized");
            callbackContext.success();
        } else if (iabHelper == null) {
            callbackContext.error(makeError("Billing cannot be initialized", UNABLE_TO_INITIALIZE));
        } else {
            iabHelper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
                public void onIabSetupFinished(IabResult result) {
                    if (!result.isSuccess()) {
                        callbackContext.error(makeError("Unable to initialize billing: " + result.toString(), UNABLE_TO_INITIALIZE, result));
                    } else {
                        Log.d(TAG, "Billing initialized");
                        billingInitialized = true;
                        callbackContext.success();
                    }
                }
            });
        }
        return true;
    }

    protected boolean runPayment(final JSONArray args, final CallbackContext callbackContext) {
        final String sku;
        final boolean subscribe;
        try {
            sku = args.getString(0);
            subscribe = args.getBoolean(1);
        } catch (JSONException e) {
            callbackContext.error(makeError("Invalid SKU", INVALID_ARGUMENTS));
            return false;
        }
        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
            return false;
        }
        final Activity cordovaActivity = this.cordova.getActivity();
        int newOrder = orderSerial.getAndIncrement();
        this.cordova.setActivityResultCallback(this);

        IabHelper.OnIabPurchaseFinishedListener oipfl = new IabHelper.OnIabPurchaseFinishedListener() {
            public void onIabPurchaseFinished(IabResult result, Purchase purchase) {
                if (result.isFailure()) {
                    int response = result.getResponse();
                    if (response == IabHelper.IABHELPER_BAD_RESPONSE || response == IabHelper.IABHELPER_UNKNOWN_ERROR) {
                        callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result));
                    } else if (response == IabHelper.IABHELPER_VERIFICATION_FAILED) {
                        callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result));
                    } else if (response == IabHelper.IABHELPER_USER_CANCELLED) {
                        callbackContext.error(makeError("Purchase Cancelled", USER_CANCELLED, result));
                    } else if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {
                        callbackContext.error(makeError("Item already owned", ITEM_ALREADY_OWNED, result));
                    } else {
                        callbackContext.error(makeError("Error completing purchase: " + response, UNKNOWN_ERROR, result));
                    }
                } else {
                    if (purchase.getItemType().equalsIgnoreCase("inapp")) {
                        consumePurchase(purchase.getItemType(), purchase.getOriginalJson(), purchase.getSignature(), callbackContext);
                    } else {
                        try {
                            JSONObject pluginResponse = new JSONObject();
                            pluginResponse.put("orderId", purchase.getOrderId());
                            pluginResponse.put("packageName", purchase.getPackageName());
                            pluginResponse.put("productId", purchase.getSku());
                            pluginResponse.put("purchaseTime", purchase.getPurchaseTime());
                            pluginResponse.put("purchaseState", purchase.getPurchaseState());
                            pluginResponse.put("purchaseToken", purchase.getToken());
                            pluginResponse.put("signature", purchase.getSignature());
                            pluginResponse.put("type", purchase.getItemType());
                            pluginResponse.put("receipt", purchase.getOriginalJson());
                            callbackContext.success(pluginResponse);
                        } catch (JSONException e) {
                            callbackContext.error("Purchase succeeded but success handler failed");
                        }
                    }
                }
            }
        };
        if (subscribe) {
            iabHelper.launchSubscriptionPurchaseFlow(cordovaActivity, sku, newOrder, oipfl, "");
        } else {
            iabHelper.launchPurchaseFlow(cordovaActivity, sku, newOrder, oipfl, "");
        }
        return true;
    }

    protected boolean RequestPayment(final JSONArray args, final CallbackContext callbackContext) {
        return runPayment(args, callbackContext);
    }

    @SuppressWarnings("unused")
    private void consumePurchase(final String type, final String receipt, final String signature, final CallbackContext callbackContext) {
        final Purchase purchase;
        try {
            purchase = new Purchase(type, receipt, signature);
        } catch (JSONException e) {
            callbackContext.error(makeError("Unable to parse purchase token", INVALID_ARGUMENTS));
            return;
        }
        if (purchase == null) {
            callbackContext.error(makeError("Unrecognized purchase token", INVALID_ARGUMENTS));
            return;
        }
        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
            return;
        }
        iabHelper.consumeAsync(purchase, new OnConsumeFinishedListener() {
            public void onConsumeFinished(Purchase purchase, IabResult result) {
                if (result.isFailure()) {
                    int response = result.getResponse();
                    if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED) {
                        callbackContext.error(makeError("Error consuming purchase", ITEM_NOT_OWNED, result));
                    } else {
                        callbackContext.error(makeError("Error consuming purchase", CONSUME_FAILED, result));
                    }
                } else {
                    try {

                        JSONObject pluginResponse = new JSONObject();
                        pluginResponse.put("orderId", purchase.getOrderId());
                        pluginResponse.put("packageName", purchase.getPackageName());
                        pluginResponse.put("productId", purchase.getSku());
                        pluginResponse.put("purchaseTime", purchase.getPurchaseTime());
                        pluginResponse.put("purchaseState", purchase.getPurchaseState());
                        pluginResponse.put("purchaseToken", purchase.getToken());
                        pluginResponse.put("signature", purchase.getSignature());
                        pluginResponse.put("type", purchase.getItemType());
                        pluginResponse.put("receipt", purchase.getOriginalJson());
                        callbackContext.success(pluginResponse);
                    } catch (JSONException e) {
                        callbackContext.error("Consume succeeded but success handler failed");
                    }
                }
            }
        });
    }

    protected boolean GetSkuDetails(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final List <String> moreSkus = new ArrayList <String> ();
        JSONArray jsonSkuList = new JSONArray(args.getString(0));
        for (int i = 0; i < jsonSkuList.length(); i++) {
            moreSkus.add(jsonSkuList.get(i).toString());
            Log.d(TAG, "get sku:" + jsonSkuList.get(i).toString());
        }
        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
            return false;
        }
        cordova.getActivity().runOnUiThread(new Runnable() {
			@Override
			public void run() {
				iabHelper.queryInventoryAsync(true, moreSkus, new IabHelper.QueryInventoryFinishedListener() {
		            public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
		                if (result.isFailure()) {
		                    callbackContext.error("Error retrieving SKU details");
		                    return;
		                }
		                JSONArray response = new JSONArray();
		                try {
		                    for (String sku: moreSkus) {
		                        SkuDetails skuDetails = inventory.getSkuDetails(sku);
		                        if (skuDetails != null) {
		                            JSONObject detailsJson = new JSONObject();
		                            detailsJson.put("productId", skuDetails.getSku());
		                            detailsJson.put("title", skuDetails.getTitle());
		                            detailsJson.put("description", skuDetails.getDescription());
		                            detailsJson.put("price", skuDetails.getPrice());
		                            detailsJson.put("type", skuDetails.getType());
		                            response.put(detailsJson);
		                        }
		                    }
		                } catch (JSONException e) {
		                    callbackContext.error(e.getMessage());
		                }
		                callbackContext.success(response);
		            }
		        });
			}
		});
        return true;
    }

    protected boolean GetOwnedProducts(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        iabHelper.queryInventoryAsync(new IabHelper.QueryInventoryFinishedListener() {
            public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
                if (result.isFailure()) {
                    callbackContext.error("Error retrieving Owned products");
                    return;
                }
                JSONArray response = new JSONArray();
                List<Purchase> OwnedSkus = inventory.getAllPurchases();
                try {
                    if (OwnedSkus != null) {
                        for (Purchase sku: OwnedSkus) {
                            JSONObject detailsJson = new JSONObject();
                            detailsJson.put("ProductId", sku.getSku());
                            detailsJson.put("PurchaseTime", sku.getPurchaseTime());
                            detailsJson.put("Token", sku.getToken());
                            detailsJson.put("OrderId", sku.getOrderId());
                            response.put(detailsJson);
                            if (sku.getItemType().equalsIgnoreCase("inapp")) {
                                consumePurchase(sku.getItemType(), sku.getOriginalJson(), sku.getSignature(), null);
                            }
                        }
                    }
                } catch (JSONException e) {
                    callbackContext.error(e.getMessage());
                }
                callbackContext.success(response);
            }
        });
        return true;
    }
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (!iabHelper.handleActivityResult(requestCode, resultCode, intent)) {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    @Override
    public void onDestroy() {
        if (iabHelper != null) iabHelper.dispose();
        iabHelper = null;
        billingInitialized = false;
    }
}