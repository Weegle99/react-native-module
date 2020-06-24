/* Copyright Urban Airship and Contributors */

package com.urbanairship.reactnative;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.urbanairship.UAirship;
import com.urbanairship.actions.ActionArguments;
import com.urbanairship.actions.ActionCompletionCallback;
import com.urbanairship.actions.ActionResult;
import com.urbanairship.actions.ActionRunRequest;
import com.urbanairship.analytics.AssociatedIdentifiers;
import com.urbanairship.channel.AttributeEditor;
import com.urbanairship.channel.TagGroupsEditor;
import com.urbanairship.messagecenter.Inbox;
import com.urbanairship.messagecenter.Message;
import com.urbanairship.messagecenter.MessageCenter;
import com.urbanairship.push.PushMessage;
import com.urbanairship.reactnative.events.NotificationOptInEvent;
import com.urbanairship.reactnative.events.PushReceivedEvent;
import com.urbanairship.util.UAStringUtil;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;

import static com.urbanairship.actions.ActionResult.STATUS_ACTION_NOT_FOUND;
import static com.urbanairship.actions.ActionResult.STATUS_COMPLETED;
import static com.urbanairship.actions.ActionResult.STATUS_EXECUTION_ERROR;
import static com.urbanairship.actions.ActionResult.STATUS_REJECTED_ARGUMENTS;
import static com.urbanairship.reactnative.Utils.convertDynamic;
import static com.urbanairship.reactnative.Utils.convertJsonValue;

/**
 * React module for Urban Airship.
 */
public class UrbanAirshipReactModule extends ReactContextBaseJavaModule {

    private static final String TAG_OPERATION_GROUP_NAME = "group";
    private static final String TAG_OPERATION_TYPE = "operationType";
    private static final String TAG_OPERATION_TAGS = "tags";
    private static final String TAG_OPERATION_ADD = "add";
    private static final String TAG_OPERATION_REMOVE = "remove";
    private static final String TAG_OPERATION_SET = "set";

    private static final String ATTRIBUTE_OPERATION_KEY = "key";
    private static final String ATTRIBUTE_OPERATION_VALUE = "value";
    private static final String ATTRIBUTE_OPERATION_TYPE = "action";
    private static final String ATTRIBUTE_OPERATION_SET = "set";
    private static final String ATTRIBUTE_OPERATION_REMOVE = "remove";
    private static final String ATTRIBUTE_OPERATION_VALUETYPE = "type";

    private static final String QUIET_TIME_START_HOUR = "startHour";
    private static final String QUIET_TIME_START_MINUTE = "startMinute";
    private static final String QUIET_TIME_END_HOUR = "endHour";
    private static final String QUIET_TIME_END_MINUTE = "endMinute";

    private static final String NOTIFICATION_ICON_KEY = "icon";
    private static final String NOTIFICATION_LARGE_ICON_KEY = "largeIcon";
    private static final String ACCENT_COLOR_KEY = "accentColor";
    private static final String DEFAULT_CHANNEL_ID_KEY = "defaultChannelId";

    static final String AUTO_LAUNCH_MESSAGE_CENTER = "com.urbanairship.auto_launch_message_center";
    static final String CLOSE_MESSAGE_CENTER = "CLOSE";

    /**
     * Default constructor.
     *
     * @param reactContext The react context.
     */
    public UrbanAirshipReactModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public void initialize() {
        super.initialize();

        getReactApplicationContext().addLifecycleEventListener(new LifecycleEventListener() {
            @Override
            public void onHostResume() {
                // If the opt-in status changes send an event
                checkOptIn(getReactApplicationContext());
            }

            @Override
            public void onHostPause() {

            }

            @Override
            public void onHostDestroy() {

            }

        });

        EventEmitter.shared().attachReactContext(getReactApplicationContext());
    }

    @NonNull
    @Override
    public String getName() {
        return "UrbanAirshipReactModule";
    }

    /**
     * Called when a new listener is added for a specified event name.
     *
     * @param eventName The event name.
     */
    @ReactMethod
    public void addAndroidListener(String eventName) {
        PluginLogger.info("UrbanAirshipReactModule - Event listener added: " + eventName);
        EventEmitter.shared().addAndroidListener(eventName);
    }

    /**
     * Called when listeners are removed.
     *
     * @param count The count of listeners.
     */
    @ReactMethod
    public void removeAndroidListeners(int count) {
        PluginLogger.info("UrbanAirshipReactModule - Event listeners removed: " + count);
        EventEmitter.shared().removeAndroidListeners(count);
    }

    /**
     * Called when all listeners are removed.
     */
    @ReactMethod
    public void removeAllAndroidListeners() {
        PluginLogger.info("UrbanAirshipReactModule - Remove all listeners");
        EventEmitter.shared().removeAllAndroidListeners();
    }

    @ReactMethod
    public void setAndroidNotificationConfig(ReadableMap map) {
        Context context = getReactApplicationContext();
        ReactAirshipPreferences prefs = ReactAirshipPreferences.shared();

        prefs.setNotificationIcon(context,
                map.hasKey(NOTIFICATION_ICON_KEY) ? map.getString(NOTIFICATION_ICON_KEY) : null);

        prefs.setNotificationLargeIcon(context,
                map.hasKey(NOTIFICATION_LARGE_ICON_KEY) ? map.getString(NOTIFICATION_LARGE_ICON_KEY) : null);

        prefs.setNotificationAccentColor(context,
                map.hasKey(ACCENT_COLOR_KEY) ? map.getString(ACCENT_COLOR_KEY) : null);

        prefs.setDefaultNotificationChannelId(context,
                map.hasKey(DEFAULT_CHANNEL_ID_KEY) ? map.getString(DEFAULT_CHANNEL_ID_KEY) : null);
    }

    /**
     * Enables/Disables user notifications.
     *
     * @param enabled {@code true} to enable notifications, {@code false} to disable.
     */
    @ReactMethod
    public void setUserNotificationsEnabled(boolean enabled) {
        UAirship.shared().getPushManager().setUserNotificationsEnabled(enabled);
    }

    /**
     * If `channelCreationDelayEnabled` is enabled in the config, apps must call
     * this method to enable channel creation.
     */
    @ReactMethod
    public void enableChannelCreation() {
        UAirship.shared().getChannel().enableChannelCreation();
    }

    /**
     * Enables user notifications.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void enableUserPushNotifications(Promise promise) {
        UAirship.shared().getPushManager().setUserNotificationsEnabled(true);
        promise.resolve(true);
    }

    /**
     * Checks if user notifications are enabled.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void isUserNotificationsEnabled(Promise promise) {
        promise.resolve(UAirship.shared().getPushManager().getUserNotificationsEnabled());
    }

    /**
     * Enables/disables data collection.
     *
     * @param enabled {@code true} to allow data collection., {@code false} to disallow.
     */
    @ReactMethod
    public void setDataCollectionEnabled(boolean enabled) {
        UAirship.shared().setDataCollectionEnabled(enabled);
    }

    /**
     * Checks if data collection is enabled.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void isDataCollectionEnabled(Promise promise) {
        promise.resolve(UAirship.shared().isDataCollectionEnabled());
    }

    /**
     * Enables/disables push token registration.
     *
     * @param enabled {@code true} to allow push token registration., {@code false} to disallow.
     */
    @ReactMethod
    public void setPushTokenRegistrationEnabled(boolean enabled) {
        UAirship.shared().getPushManager().setPushTokenRegistrationEnabled(enabled);
    }

    /**
     * Checks if push token registration is enabled.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void isPushTokenRegistrationEnabled(Promise promise) {
        promise.resolve(UAirship.shared().getPushManager().isPushTokenRegistrationEnabled());
    }

    /**
     * Checks if the app's notifications are enabled.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void isUserNotificationsOptedIn(Promise promise) {
        promise.resolve(UAirship.shared().getPushManager().isOptIn());
    }

    /**
     * Returns the channel ID.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getChannelId(Promise promise) {
        promise.resolve(UAirship.shared().getChannel().getId());
    }

    /**
     * Returns the registration token.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getRegistrationToken(Promise promise) {
        promise.resolve(UAirship.shared().getPushManager().getPushToken());
    }


    /**
     * Sets the named user.
     *
     * @param namedUser The named user ID.
     */
    @ReactMethod
    public void setNamedUser(String namedUser) {
        if (namedUser != null) {
            namedUser = namedUser.trim();
        }

        if (UAStringUtil.isEmpty(namedUser)) {
            namedUser = null;
        }

        UAirship.shared().getNamedUser().setId(namedUser);
    }

    /**
     * Gets the named user.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getNamedUser(Promise promise) {
        promise.resolve(UAirship.shared().getNamedUser().getId());
    }

    /**
     * Adds a channel tag.
     *
     * @param tag The tag to add.
     */
    @ReactMethod
    public void addTag(String tag) {
        if (tag != null) {
            UAirship.shared().getChannel().editTags().addTag(tag).apply();
        }
    }

    /**
     * Removes a channel tag.
     *
     * @param tag The tag to remove.
     */
    @ReactMethod
    public void removeTag(String tag) {
        if (tag != null) {
            UAirship.shared().getChannel().editTags().removeTag(tag).apply();
        }
    }

    /**
     * Gets the current channel tags.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getTags(Promise promise) {
        WritableArray array = Arguments.createArray();
        for (String tag : UAirship.shared().getChannel().getTags()) {
            array.pushString(tag);
        }

        promise.resolve(array);
    }

    /**
     * Edits the channel tag groups.
     * Operations should each be a map with the following:
     * - operationType: Either add or remove
     * - group: The group to modify
     * - tags: The tags to add or remove.
     *
     * @param operations An array of operations.
     */
    @ReactMethod
    public void editChannelTagGroups(ReadableArray operations) {
        applyTagGroupOperations(UAirship.shared().getChannel().editTagGroups(), operations);
    }

    /**
     * Edits the named user tag groups.
     * Operations should each be a map with the following:
     * - operationType: Either add or remove
     * - group: The group to modify
     * - tags: The tags to add or remove.
     *
     * @param operations An array of operations.
     */
    @ReactMethod
    public void editNamedUserTagGroups(ReadableArray operations) {
        applyTagGroupOperations(UAirship.shared().getNamedUser().editTagGroups(), operations);
    }

    /**
     * Edits the channel attributes.
     * Operations should each be a map with the following:
     * - action: Either set or remove
     * - value: The group to modify
     * - key: The tags to add or remove.
     *
     * @param operations An array of operations.
     */
    @ReactMethod
    public void editChannelAttributes(ReadableArray operations) {
        applyAttributeOperations(UAirship.shared().getChannel().editAttributes(), operations);
    }

    /**
     * Edits the named user attributes.
     * Operations should each be a map with the following:
     * - action: Either set or remove
     * - value: The group to modify
     * - key: The tags to add or remove.
     *
     * @param operations An array of operations.
     */
    @ReactMethod
    public void editNamedUserAttributes(ReadableArray operations) {
        applyAttributeOperations(UAirship.shared().getNamedUser().editAttributes(), operations);
    }

    /**
     * Associated an identifier to the channel.
     *
     * @param key The identifier's key.
     * @param value The identifier's value. If the value is null it will be removed from the current
     * set of associated identifiers.
     */
    @ReactMethod
    public void associateIdentifier(String key, String value) {
        AssociatedIdentifiers.Editor editor = UAirship.shared().getAnalytics().editAssociatedIdentifiers();

        if (value == null) {
            editor.removeIdentifier(key);
        } else {
            editor.addIdentifier(key, value);
        }

        editor.apply();
    }

    /**
     * Enables/Disables analytics.
     *
     * @param enabled {@code true} to enable analytics, {@code false} to disable.
     */
    @ReactMethod
    public void setAnalyticsEnabled(boolean enabled) {
        UAirship.shared().getAnalytics().setEnabled(enabled);
    }

    /**
     * Checks if analytics are enabled.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void isAnalyticsEnabled(Promise promise) {
        promise.resolve(UAirship.shared().getAnalytics().isEnabled());
    }

    /**
     * Initiates screen tracking for a specific app screen, must be called once per tracked screen.
     *
     * @param {String} screen The screen's string identifier.
     */
    @ReactMethod
    public void trackScreen(String screen) {
        UAirship.shared().getAnalytics().trackScreen(screen);
    }

    /**
     * Runs an action.
     *
     * @param name The action's name.
     * @param value The action's value.
     * @param promise A JS promise to deliver the action result.
     */
    @ReactMethod
    public void runAction(final String name, Dynamic value, final Promise promise) {
        ActionRunRequest.createRequest(name)
                .setValue(convertDynamic(value))
                .run(new ActionCompletionCallback() {
                    @Override
                    public void onFinish(@NonNull ActionArguments actionArguments, @NonNull ActionResult actionResult) {
                        switch (actionResult.getStatus()) {
                            case STATUS_COMPLETED:
                                promise.resolve(convertJsonValue(actionResult.getValue().toJsonValue()));
                                return;

                            case STATUS_REJECTED_ARGUMENTS:
                                promise.reject("STATUS_REJECTED_ARGUMENTS", "Action rejected arguments.");
                                return;

                            case STATUS_ACTION_NOT_FOUND:
                                promise.reject("STATUS_ACTION_NOT_FOUND", "Action " + name + "not found.");
                                return;

                            case STATUS_EXECUTION_ERROR:
                            default:
                                promise.reject("STATUS_EXECUTION_ERROR", actionResult.getException());
                                return;
                        }
                    }
                });
    }

    /**
     * Badging is not supported on Android. Returns 0 if a badge count is requested on Android.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getBadgeNumber(Promise promise) {
        promise.resolve(0);
    }

    /**
     * Displays the default message center.
     */
    @ReactMethod
    public void displayMessageCenter() {
        MessageCenter.shared().showMessageCenter();
    }

    /**
     * Dismisses the default message center.
     */
    @ReactMethod
    public void dismissMessageCenter() {
        Activity activity = getCurrentActivity();
        if (activity != null) {
            Intent intent = new Intent(activity, CustomMessageCenterActivity.class)
                    .setAction(CLOSE_MESSAGE_CENTER);
            activity.startActivity(intent);
        }
    }

    /**
     * Display an inbox message in the default message center.
     *
     * @param messageId The id of the message to be displayed.
     * @param promise The JS promise.
     */
    @ReactMethod
    public void displayMessage(String messageId, Promise promise) {
        MessageCenter.shared().showMessageCenter(messageId);
        promise.resolve(true);
    }

    /**
     * Dismisses the currently displayed inbox message.
     */
    @ReactMethod
    public void dismissMessage() {
        Activity activity = getCurrentActivity();
        if (activity != null) {
            Intent intent = new Intent(activity, CustomMessageActivity.class)
                    .setAction(CLOSE_MESSAGE_CENTER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        }
    }

    /**
     * Retrieves the current inbox messages.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getInboxMessages(Promise promise) {
        WritableArray messagesArray = Arguments.createArray();

        for (Message message : MessageCenter.shared().getInbox().getMessages()) {
            WritableMap messageMap = new WritableNativeMap();
            messageMap.putString("title", message.getTitle());
            messageMap.putString("id", message.getMessageId());
            messageMap.putDouble("sentDate", message.getSentDate().getTime());
            messageMap.putString("listIconUrl", message.getListIconUrl());
            messageMap.putBoolean("isRead", message.isRead());
            messageMap.putBoolean("isDeleted", message.isDeleted());

            WritableMap extrasMap = new WritableNativeMap();
            Bundle extras = message.getExtras();
            for (String key : extras.keySet()) {
                String value = String.valueOf(extras.get(key));
                extrasMap.putString(key, value);
            }

            messageMap.putMap("extras", extrasMap);
            messagesArray.pushMap(messageMap);
        }

        promise.resolve(messagesArray);
    }

    /**
     * Deletes an inbox message.
     *
     * @param messageId The id of the message to be deleted.
     * @param promise The JS promise.
     */
    @ReactMethod
    public void deleteInboxMessage(String messageId, Promise promise) {
        Message message = MessageCenter.shared().getInbox().getMessage(messageId);

        if (message == null) {
            promise.reject("STATUS_MESSAGE_NOT_FOUND", "Message not found");
        } else {
            message.delete();
            promise.resolve(true);
        }
    }

    /**
     * Marks an inbox message as read.
     *
     * @param messageId The id of the message to be marked as read.
     * @param promise The JS promise.
     */
    @ReactMethod
    public void markInboxMessageRead(String messageId, Promise promise) {
        Message message = MessageCenter.shared().getInbox().getMessage(messageId);

        if (message == null) {
            promise.reject("STATUS_MESSAGE_NOT_FOUND", "Message not found.");
        } else {
            message.markRead();
            promise.resolve(true);
        }
    }

    @ReactMethod
    public void clearNotifications() {
        NotificationManagerCompat.from(UAirship.getApplicationContext()).cancelAll();
    }

    @ReactMethod
    public void clearNotification(String identifier) {
        if (UAStringUtil.isEmpty(identifier)) {
            return;
        }

        String[] parts = identifier.split(":", 2);
        if (parts.length == 0) {
            Log.e(getName(), "Invalid identifier: " + identifier);
            return;
        }

        int id;
        String tag = null;
        try {
            id = Integer.valueOf(parts[0]);
        } catch (NumberFormatException e) {
            Log.e(getName(), "Invalid identifier: " + identifier);
            return;
        }

        if (parts.length == 2) {
            tag = parts[1];
        }


        NotificationManagerCompat.from(UAirship.getApplicationContext()).cancel(tag, id);
    }


    /**
     * Retrieves the current inbox messages.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void getActiveNotifications(Promise promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WritableArray notifications = Arguments.createArray();

            NotificationManager notificationManager = (NotificationManager) UAirship.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            StatusBarNotification[] statusBarNotifications = notificationManager.getActiveNotifications();

            for (StatusBarNotification statusBarNotification : statusBarNotifications) {
                int id = statusBarNotification.getId();
                String tag = statusBarNotification.getTag();

                PushMessage pushMessage;
                Bundle extras = statusBarNotification.getNotification().extras;
                Bundle bundle = extras == null ? null : extras.getBundle("push_message");

                if (bundle != null) {
                    pushMessage = new PushMessage(bundle);
                } else {
                    pushMessage = new PushMessage(new Bundle());
                }

                notifications.pushMap(new PushReceivedEvent(pushMessage, id, tag).getBody());
            }

            promise.resolve(notifications);
        } else {
            promise.reject("UNSUPPORTED", "Getting active notifications is only supported on Marshmallow and newer devices.");
        }
    }


    /**
     * Forces the inbox to refresh. This is normally not needed as the inbox will automatically refresh on foreground or when a push arrives thats associated with a message.
     *
     * @param promise The JS promise.
     */
    @ReactMethod
    public void refreshInbox(final Promise promise) {
        MessageCenter.shared().getInbox().fetchMessages(new Inbox.FetchMessagesCallback() {
            @Override
            public void onFinished(boolean success) {
                if (success) {
                    promise.resolve(true);
                } else {
                    promise.reject("STATUS_DID_NOT_REFRESH", "Inbox failed to refresh");
                }
            }
        });
    }

    /**
     * Sets the default behavior when the message center is launched from a push notification. If set to false the message center must be manually launched.
     *
     * @param enabled {@code true} to automatically launch the default message center, {@code false} to disable.
     */
    @ReactMethod
    public void setAutoLaunchDefaultMessageCenter(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(UAirship.getApplicationContext())
                .edit()
                .putBoolean(AUTO_LAUNCH_MESSAGE_CENTER, enabled)
                .apply();
    }

    /**
     * Helper method to apply tag group changes.
     *
     * @param editor The tag group editor.
     * @param operations A list of tag group operations.
     */
    private static void applyTagGroupOperations(@NonNull TagGroupsEditor editor, @NonNull ReadableArray operations) {
        for (int i = 0; i < operations.size(); i++) {
            ReadableMap operation = operations.getMap(i);
            if (operation == null) {
                continue;
            }

            String group = operation.getString(TAG_OPERATION_GROUP_NAME);
            ReadableArray tags = operation.getArray(TAG_OPERATION_TAGS);
            String operationType = operation.getString(TAG_OPERATION_TYPE);

            if (group == null || tags == null || operationType == null) {
                continue;
            }

            HashSet<String> tagSet = new HashSet<>();
            for (int j = 0; j < tags.size(); j++) {
                String tag = tags.getString(j);
                if (tag != null) {
                    tagSet.add(tags.getString(j));
                }
            }

            if (TAG_OPERATION_ADD.equals(operationType)) {
                editor.addTags(group, tagSet);
            } else if (TAG_OPERATION_REMOVE.equals(operationType)) {
                editor.removeTags(group, tagSet);
            } else if (TAG_OPERATION_SET.equals(operationType)) {
                editor.setTags(group, tagSet);
            }
        }

        editor.apply();
    }

    /**
     * Helper method to apply attribute changes.
     *
     * @param editor The attribute editor.
     * @param operations A list of attribute operations.
     */
    private static void applyAttributeOperations(@NonNull AttributeEditor editor, @NonNull ReadableArray operations) {
        for (int i = 0; i < operations.size(); i++) {
            ReadableMap operation = operations.getMap(i);
            if (operation == null) {
                continue;
            }

            String action = operation.getString(ATTRIBUTE_OPERATION_TYPE);
            String key = operation.getString(ATTRIBUTE_OPERATION_KEY);

            if (action == null || key == null) {
                continue;
            }

            if (ATTRIBUTE_OPERATION_SET.equals(action)) {
                String valueType = (String) operation.getString(ATTRIBUTE_OPERATION_VALUETYPE);
                if ("string".equals(valueType)) {
                    String value = operation.getString(ATTRIBUTE_OPERATION_VALUE);
                    if (value == null) {
                        continue;
                    }
                    editor.setAttribute(key, value);
                } else if ("number".equals(valueType)) {
                    double value = operation.getDouble(ATTRIBUTE_OPERATION_VALUE);
                    editor.setAttribute(key, value);
                } else if ("date".equals(valueType)) {
                    double value = operation.getDouble(ATTRIBUTE_OPERATION_VALUE);
                    // JavaScript's date type doesn't pass through the JS to native bridge. Dates are instead serialized as milliseconds since epoch.
                    editor.setAttribute(key, new Date((long)value));
                }
            } else if (ATTRIBUTE_OPERATION_REMOVE.equals(action)) {
                editor.removeAttribute(key);
            }
        }

        editor.apply();
    }

    /**
     * Helper to determine user notifications authorization status
     *
     * @param context The application context.
     */
    static void checkOptIn(Context context) {
        boolean optIn = UAirship.shared().getPushManager().isOptIn();

        if (ReactAirshipPreferences.shared().getOptInStatus(context) != optIn) {
            ReactAirshipPreferences.shared().setOptInStatus(optIn, context);

            Event optInEvent = new NotificationOptInEvent(optIn);
            EventEmitter.shared().sendEvent(optInEvent);
        }
    }

}
