/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.StaleDataException;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@link RingtonePickerActivity} allows the user to choose one from all of the
 * available ringtones. The chosen ringtone's URI will be persisted as a string.
 *
 * @see RingtoneManager#ACTION_RINGTONE_PICKER
 */
public final class RingtonePickerActivity extends AlertActivity implements
        AdapterView.OnItemSelectedListener, Runnable, DialogInterface.OnClickListener,
        AlertController.AlertParams.OnPrepareListViewListener {

    private static final int POS_UNKNOWN = -1;

    private static final String TAG = "RingtonePickerActivity";

    private static final int DELAY_MS_SELECTION_PLAYED = 300;

    private static final String COLUMN_LABEL = MediaStore.Audio.Media.TITLE;

    private static final String SAVE_CLICKED_POS = "clicked_pos";

    private static final String SOUND_NAME_RES_PREFIX = "sound_name_";

    private RingtoneManager mRingtoneManager;
    private int mType;

    private Cursor mCursor;
    private Handler mHandler;

    /** The position in the list of the 'Silent' item. */
    private int mSilentPos = POS_UNKNOWN;

    /** The position in the list of the 'Default' item. */
    private int mDefaultRingtonePos = POS_UNKNOWN;

    /** The position in the list of the last clicked item. */
    private int mClickedPos = POS_UNKNOWN;

    /** The position in the list of the ringtone to sample. */
    private int mSampleRingtonePos = POS_UNKNOWN;

    /** Whether this list has the 'Silent' item. */
    private boolean mHasSilentItem;

    /** The Uri to place a checkmark next to. */
    private Uri mExistingUri;

    /** The number of static items in the list. */
    private int mStaticItemCount;

    /** Whether this list has the 'Default' item. */
    private boolean mHasDefaultItem;

    /** The Uri to play when the 'Default' item is clicked. */
    private Uri mUriForDefaultItem;

    /**
     * A Ringtone for the default ringtone. In most cases, the RingtoneManager
     * will stop the previous ringtone. However, the RingtoneManager doesn't
     * manage the default ringtone for us, so we should stop this one manually.
     */
    private Ringtone mDefaultRingtone;

    /**
     * The ringtone that's currently playing, unless the currently playing one is the default
     * ringtone.
     */
    private Ringtone mCurrentRingtone;

    private int mAttributesFlags;

    private boolean mShowOkCancelButtons;

    /**
     * Keep the currently playing ringtone around when changing orientation, so that it
     * can be stopped later, after the activity is recreated.
     */
    private static Ringtone sPlayingRingtone;

    private DialogInterface.OnClickListener mRingtoneClickListener =
            new DialogInterface.OnClickListener() {

        /*
         * On item clicked
         */
        public void onClick(DialogInterface dialog, int which) {
            // Save the position of most recently clicked item
            mClickedPos = which;

            // In the buttonless (watch-only) version, preemptively set our result since we won't
            // have another chance to do so before the activity closes.
            if (!mShowOkCancelButtons) {
                setResultFromSelection();
            }

            // Play clip
            playRingtone(which, 0);
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();

        Intent intent = getIntent();

        // Give the Activity so it can do managed queries
        mRingtoneManager = new RingtoneManager(this);

        // Get the types of ringtones to show
        mType = intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1);
        if (mType != -1) {
            mRingtoneManager.setType(mType);
        }

        /*
         * Get whether to show the 'Default' item, and the URI to play when the
         * default is clicked
         */
        mHasDefaultItem = intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        mUriForDefaultItem = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI);
        if (mUriForDefaultItem == null) {
            if (mType == RingtoneManager.TYPE_NOTIFICATION) {
                mUriForDefaultItem = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else if (mType == RingtoneManager.TYPE_ALARM) {
                mUriForDefaultItem = Settings.System.DEFAULT_ALARM_ALERT_URI;
            } else if (mType == RingtoneManager.TYPE_RINGTONE) {
                mUriForDefaultItem = Settings.System.DEFAULT_RINGTONE_URI;
            } else {
                // or leave it null for silence.
                mUriForDefaultItem = Settings.System.DEFAULT_RINGTONE_URI;
            }
        }

        if (savedInstanceState != null) {
            mClickedPos = savedInstanceState.getInt(SAVE_CLICKED_POS, POS_UNKNOWN);
        }
        // Get whether to show the 'Silent' item
        mHasSilentItem = intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        // AudioAttributes flags
        mAttributesFlags |= intent.getIntExtra(
                RingtoneManager.EXTRA_RINGTONE_AUDIO_ATTRIBUTES_FLAGS,
                0 /*defaultValue == no flags*/);

        mShowOkCancelButtons = getResources().getBoolean(R.bool.config_showOkCancelButtons);


        mCursor = new LocalizedCursor(mRingtoneManager.getCursor(), getResources(), COLUMN_LABEL);

        // The volume keys will control the stream that we are choosing a ringtone for
        setVolumeControlStream(mRingtoneManager.inferStreamType());

        // Get the URI whose list item should have a checkmark
        mExistingUri = intent
                .getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);

        final AlertController.AlertParams p = mAlertParams;
        p.mCursor = mCursor;
        p.mOnClickListener = mRingtoneClickListener;
        p.mLabelColumn = COLUMN_LABEL;
        p.mIsSingleChoice = true;
        p.mOnItemSelectedListener = this;
        if (mShowOkCancelButtons) {
            p.mPositiveButtonText = getString(com.android.internal.R.string.ok);
            p.mPositiveButtonListener = this;
            p.mNegativeButtonText = getString(com.android.internal.R.string.cancel);
            p.mPositiveButtonListener = this;
        }
        p.mOnPrepareListViewListener = this;

        p.mTitle = intent.getCharSequenceExtra(RingtoneManager.EXTRA_RINGTONE_TITLE);
        if (p.mTitle == null) {
            p.mTitle = getString(com.android.internal.R.string.ringtone_picker_title);
        }

        setupAlert();
    }
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_CLICKED_POS, mClickedPos);
    }

    public void onPrepareListView(ListView listView) {

        if (mHasDefaultItem) {
            mDefaultRingtonePos = addDefaultRingtoneItem(listView);

            if (mClickedPos == POS_UNKNOWN && RingtoneManager.isDefault(mExistingUri)) {
                mClickedPos = mDefaultRingtonePos;
            }
        }

        if (mHasSilentItem) {
            mSilentPos = addSilentItem(listView);

            // The 'Silent' item should use a null Uri
            if (mClickedPos == POS_UNKNOWN && mExistingUri == null) {
                mClickedPos = mSilentPos;
            }
        }

        if (mClickedPos == POS_UNKNOWN) {
            mClickedPos = getListPosition(mRingtoneManager.getRingtonePosition(mExistingUri));
        }

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mShowOkCancelButtons) {
            setResultFromSelection();
        }
        // Put a checkmark next to an item.
        mAlertParams.mCheckedItem = mClickedPos;
    }

    /**
     * Adds a static item to the top of the list. A static item is one that is not from the
     * RingtoneManager.
     *
     * @param listView The ListView to add to.
     * @param textResId The resource ID of the text for the item.
     * @return The position of the inserted item.
     */
    private int addStaticItem(ListView listView, int textResId) {
        TextView textView = (TextView) getLayoutInflater().inflate(
                com.android.internal.R.layout.select_dialog_singlechoice_material, listView, false);
        textView.setText(textResId);
        listView.addHeaderView(textView);
        mStaticItemCount++;
        return listView.getHeaderViewsCount() - 1;
    }

    private int addDefaultRingtoneItem(ListView listView) {
        if (mType == RingtoneManager.TYPE_NOTIFICATION) {
            return addStaticItem(listView, R.string.notification_sound_default);
        } else if (mType == RingtoneManager.TYPE_ALARM) {
            return addStaticItem(listView, R.string.alarm_sound_default);
        }

        return addStaticItem(listView, R.string.ringtone_default);
    }

    private int addSilentItem(ListView listView) {
        return addStaticItem(listView, com.android.internal.R.string.ringtone_silent);
    }

    /*
     * On click of Ok/Cancel buttons
     */
    public void onClick(DialogInterface dialog, int which) {
        boolean positiveResult = which == DialogInterface.BUTTON_POSITIVE;

        // Stop playing the previous ringtone
        mRingtoneManager.stopPreviousRingtone();

        if (positiveResult) {
            setResultFromSelection();
        } else {
            setResult(RESULT_CANCELED);
        }

        finish();
    }

    /*
     * On item selected via keys
     */
    public void onItemSelected(AdapterView parent, View view, int position, long id) {
        mClickedPos = position;
        playRingtone(position, DELAY_MS_SELECTION_PLAYED);

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mShowOkCancelButtons) {
            setResultFromSelection();
        }
    }

    public void onNothingSelected(AdapterView parent) {
    }

    private void playRingtone(int position, int delayMs) {
        mHandler.removeCallbacks(this);
        mSampleRingtonePos = position;
        mHandler.postDelayed(this, delayMs);
    }

    public void run() {
        stopAnyPlayingRingtone();
        if (mSampleRingtonePos == mSilentPos) {
            return;
        }

        Ringtone ringtone;
        if (mSampleRingtonePos == mDefaultRingtonePos) {
            if (mDefaultRingtone == null) {
                mDefaultRingtone = RingtoneManager.getRingtone(this, mUriForDefaultItem);
            }
           /*
            * Stream type of mDefaultRingtone is not set explicitly here.
            * It should be set in accordance with mRingtoneManager of this Activity.
            */
            if (mDefaultRingtone != null) {
                mDefaultRingtone.setStreamType(mRingtoneManager.inferStreamType());
            }
            ringtone = mDefaultRingtone;
            mCurrentRingtone = null;
        } else {
            try {
               ringtone =mRingtoneManager.getRingtone(
                          getRingtoneManagerPosition(mSampleRingtonePos));
            } catch (StaleDataException staleDataException) {
               ringtone = null;
            } catch (IllegalStateException illegalStateException) {
               ringtone = null;
            }
            mCurrentRingtone = ringtone;
        }

        if (ringtone != null) {
            if (mAttributesFlags != 0) {
                ringtone.setAudioAttributes(
                        new AudioAttributes.Builder(ringtone.getAudioAttributes())
                                .setFlags(mAttributesFlags)
                                .build());
            }
            ringtone.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(this);
        mCursor.deactivate();

        if (!isChangingConfigurations()) {
            stopAnyPlayingRingtone();
        } else {
            saveAnyPlayingRingtone();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isChangingConfigurations()) {
            stopAnyPlayingRingtone();
        }
    }

    private void setResultFromSelection() {
        // Obtain the currently selected ringtone
        Uri uri = null;
        if (mClickedPos == mDefaultRingtonePos) {
            // Set it to the default Uri that they originally gave us
            uri = mUriForDefaultItem;
        } else if (mClickedPos == mSilentPos) {
            // A null Uri is for the 'Silent' item
            uri = null;
        } else {
            uri = mRingtoneManager.getRingtoneUri(getRingtoneManagerPosition(mClickedPos));
        }

        // Return new URI if another ringtone was selected, as there's no ok/cancel button
        if (Objects.equals(uri, mExistingUri)) {
            setResult(RESULT_CANCELED);
        } else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri);
            setResult(RESULT_OK, resultIntent);
        }
    }

    private void saveAnyPlayingRingtone() {
        if (mDefaultRingtone != null && mDefaultRingtone.isPlaying()) {
            sPlayingRingtone = mDefaultRingtone;
        } else if (mCurrentRingtone != null && mCurrentRingtone.isPlaying()) {
            sPlayingRingtone = mCurrentRingtone;
        }
    }

    private void stopAnyPlayingRingtone() {
        if (sPlayingRingtone != null && sPlayingRingtone.isPlaying()) {
            sPlayingRingtone.stop();
        }
        sPlayingRingtone = null;

        if (mDefaultRingtone != null && mDefaultRingtone.isPlaying()) {
            mDefaultRingtone.stop();
        }

        if (mRingtoneManager != null) {
            mRingtoneManager.stopPreviousRingtone();
        }
    }

    private int getRingtoneManagerPosition(int listPos) {
        return listPos - mStaticItemCount;
    }

    private int getListPosition(int ringtoneManagerPos) {

        // If the manager position is -1 (for not found), return that
        if (ringtoneManagerPos < 0) return ringtoneManagerPos;

        return ringtoneManagerPos + mStaticItemCount;
    }

    private static class LocalizedCursor extends CursorWrapper {

        final int mTitleIndex;
        final Resources mResources;
        String mNamePrefix;
        final Pattern mSanitizePattern;

        LocalizedCursor(Cursor cursor, Resources resources, String columnLabel) {
            super(cursor);
            mTitleIndex = mCursor.getColumnIndex(columnLabel);
            mResources = resources;
            mSanitizePattern = Pattern.compile("[^a-zA-Z0-9]");
            if (mTitleIndex == -1) {
                Log.e(TAG, "No index for column " + columnLabel);
                mNamePrefix = null;
            } else {
                try {
                    // Build the prefix for the name of the resource to look up
                    // format is: "ResourcePackageName::ResourceTypeName/"
                    // (the type name is expected to be "string" but let's not hardcode it).
                    // Here we use an existing resource "notification_sound_default" which is
                    // always expected to be found.
                    mNamePrefix = String.format("%s:%s/%s",
                            mResources.getResourcePackageName(R.string.notification_sound_default),
                            mResources.getResourceTypeName(R.string.notification_sound_default),
                            SOUND_NAME_RES_PREFIX);
                } catch (NotFoundException e) {
                    mNamePrefix = null;
                }
            }
        }

        /**
         * Process resource name to generate a valid resource name.
         * @param input
         * @return a non-null String
         */
        private String sanitize(String input) {
            if (input == null) {
                return "";
            }
            return mSanitizePattern.matcher(input).replaceAll("_").toLowerCase();
        }

        @Override
        public String getString(int columnIndex) {
            final String defaultName = mCursor.getString(columnIndex);
            if ((columnIndex != mTitleIndex) || (mNamePrefix == null)) {
                return defaultName;
            }
            TypedValue value = new TypedValue();
            try {
                // the name currently in the database is used to derive a name to match
                // against resource names in this package
                mResources.getValue(mNamePrefix + sanitize(defaultName), value, false);
            } catch (NotFoundException e) {
                // no localized string, use the default string
                return defaultName;
            }
            if ((value != null) && (value.type == TypedValue.TYPE_STRING)) {
                Log.d(TAG, String.format("Replacing name %s with %s",
                        defaultName, value.string.toString()));
                return value.string.toString();
            } else {
                Log.e(TAG, "Invalid value when looking up localized name, using " + defaultName);
                return defaultName;
            }
        }
    }
}
