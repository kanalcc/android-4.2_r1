/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.dreams.phototable;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;

/**
 * Loads images from Picasa.
 */
public class PicasaSource extends PhotoSource {
    private static final String TAG = "PhotoTable.PicasaSource";

    private static final String PICASA_AUTHORITY =
            "com.google.android.gallery3d.GooglePhotoProvider";

    private static final String PICASA_PHOTO_PATH = "photos";
    private static final String PICASA_ALBUM_PATH = "albums";
    private static final String PICASA_USER_PATH = "users";

    private static final String PICASA_ID = "_id";
    private static final String PICASA_URL = "content_url";
    private static final String PICASA_ROTATION = "rotation";
    private static final String PICASA_ALBUM_ID = "album_id";
    private static final String PICASA_TITLE = "title";
    private static final String PICASA_THUMB = "thumbnail_url";
    private static final String PICASA_ALBUM_TYPE = "album_type";
    private static final String PICASA_ALBUM_USER = "user_id";
    private static final String PICASA_ALBUM_UPDATED = "date_updated";
    private static final String PICASA_ACCOUNT = "account";

    private static final String PICASA_URL_KEY = "content_url";
    private static final String PICASA_TYPE_KEY = "type";
    private static final String PICASA_TYPE_FULL_VALUE = "full";
    private static final String PICASA_TYPE_SCREEN_VALUE = "screennail";
    private static final String PICASA_TYPE_THUMB_VALUE = "thumbnail";
    private static final String PICASA_TYPE_IMAGE_VALUE = "image";
    private static final String PICASA_POSTS_TYPE = "Buzz";
    private static final String PICASA_UPLOAD_TYPE = "InstantUpload";

    private final int mMaxPostAblums;
    private final String mPostsAlbumName;
    private final String mUploadsAlbumName;
    private final String mUnknownAlbumName;
    private final LinkedList<ImageData> mRecycleBin;
    private final ConnectivityManager mConnectivityManager;
    private final int mMaxRecycleSize;

    private Set<String> mFoundAlbumIds;
    private int mNextPosition;
    private int mDisplayLongSide;

    public PicasaSource(Context context, SharedPreferences settings) {
        super(context, settings);
        mSourceName = TAG;
        mNextPosition = -1;
        mMaxPostAblums = mResources.getInteger(R.integer.max_post_albums);
        mPostsAlbumName = mResources.getString(R.string.posts_album_name, "Posts");
        mUploadsAlbumName = mResources.getString(R.string.uploads_album_name, "Instant Uploads");
        mUnknownAlbumName = mResources.getString(R.string.unknown_album_name, "Unknown");
        mMaxRecycleSize = mResources.getInteger(R.integer.recycle_image_pool_size);
        mConnectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mRecycleBin = new LinkedList<ImageData>();
        fillQueue();
        mDisplayLongSide = getDisplayLongSide();
    }

    private int getDisplayLongSide() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        return Math.max(metrics.heightPixels, metrics.widthPixels);
    }

    @Override
    protected Collection<ImageData> findImages(int howMany) {
        log(TAG, "finding images");
        LinkedList<ImageData> foundImages = new LinkedList<ImageData>();
        if (mConnectivityManager.isActiveNetworkMetered()) {
            howMany = Math.min(howMany, mMaxRecycleSize);
            log(TAG, "METERED: " + howMany);
            if (!mRecycleBin.isEmpty()) {
                foundImages.addAll(mRecycleBin);
                log(TAG, "recycled " + foundImages.size() + " items.");
                return foundImages;
            }
        }

        String[] projection = {PICASA_ID, PICASA_URL, PICASA_ROTATION, PICASA_ALBUM_ID};
        boolean usePosts = false;
        LinkedList<String> albumIds = new LinkedList<String>();
        for (String id : getFoundAlbums()) {
            if (mSettings.isAlbumEnabled(id)) {
                String[] parts = id.split(":");
                if (parts.length > 2) {
                    albumIds.addAll(resolveAlbumIds(id));
                } else {
                    albumIds.add(parts[1]);
                }
            }
        }

        if (albumIds.size() > mMaxPostAblums) {
            Collections.shuffle(albumIds);
        }

        StringBuilder selection = new StringBuilder();
        int albumIdx = 0;
        for (String albumId : albumIds) {
            if (albumIdx < mMaxPostAblums) {
                if (selection.length() > 0) {
                    selection.append(" OR ");
                }
                log(TAG, "adding: " + albumId);
                selection.append(PICASA_ALBUM_ID + " = '" + albumId + "'");
            } else {
                log(TAG, "too many albums, dropping: " + albumId);
            }
            albumIdx++;
        }

        if (selection.length() == 0) {
            return foundImages;
        }

        log(TAG, "selection is (" + selection.length() + "): " + selection.toString());

        Uri.Builder picasaUriBuilder = new Uri.Builder()
                .scheme("content")
                .authority(PICASA_AUTHORITY)
                .appendPath(PICASA_PHOTO_PATH);
        Cursor cursor = mResolver.query(picasaUriBuilder.build(),
                projection, selection.toString(), null, null);
        if (cursor != null) {
            if (cursor.getCount() > howMany && mNextPosition == -1) {
                mNextPosition =
                        (int) Math.abs(mRNG.nextInt() % (cursor.getCount() - howMany));
            }
            if (mNextPosition == -1) {
                mNextPosition = 0;
            }
            log(TAG, "moving to position: " + mNextPosition);
            cursor.moveToPosition(mNextPosition);

            int idIndex = cursor.getColumnIndex(PICASA_ID);
            int urlIndex = cursor.getColumnIndex(PICASA_URL);
            int orientationIndex = cursor.getColumnIndex(PICASA_ROTATION);
            int bucketIndex = cursor.getColumnIndex(PICASA_ALBUM_ID);

            if (idIndex < 0) {
                log(TAG, "can't find the ID column!");
            } else {
                while (foundImages.size() < howMany && !cursor.isAfterLast()) {
                    if (idIndex >= 0) {
                        ImageData data = new ImageData();
                        data.id = cursor.getString(idIndex);

                        if (urlIndex >= 0) {
                            data.url = cursor.getString(urlIndex);
                        }

                        foundImages.offer(data);
                    }
                    if (cursor.moveToNext()) {
                        mNextPosition++;
                    }
                }
                if (cursor.isAfterLast()) {
                    mNextPosition = 0;
                }
            }

            cursor.close();
        } else {
            Log.w(TAG, "received a null cursor in findImages()");
        }
        log(TAG, "found " + foundImages.size() + " items.");
        return foundImages;
    }

    private String resolveAccount(String id) {
        String displayName = "unknown";
        String[] projection = {PICASA_ACCOUNT};
        Uri.Builder picasaUriBuilder = new Uri.Builder()
                .scheme("content")
                .authority(PICASA_AUTHORITY)
                .appendPath(PICASA_USER_PATH)
                .appendPath(id);
        Cursor cursor = mResolver.query(picasaUriBuilder.build(),
                projection, null, null, null);
        if (cursor != null) {
            cursor.moveToFirst();
            int accountIndex = cursor.getColumnIndex(PICASA_ACCOUNT);
            if (accountIndex >= 0) {
                displayName = cursor.getString(accountIndex);
            }
            cursor.close();
        } else {
            Log.w(TAG, "received a null cursor in resolveAccount()");
        }
        return displayName;
    }

    private Collection<String> resolveAlbumIds(String id) {
        LinkedList<String> albumIds = new LinkedList<String>();
        log(TAG, "resolving " + id);

        String[] parts = id.split(":");
        if (parts.length < 3) {
            return albumIds;
        }

        String[] projection = {PICASA_ID, PICASA_ALBUM_TYPE, PICASA_ALBUM_UPDATED,
                               PICASA_ALBUM_USER};
        String order = PICASA_ALBUM_UPDATED + " DESC";
        String selection = (PICASA_ALBUM_USER + " = '" + parts[2] + "' AND " +
                            PICASA_ALBUM_TYPE + " = '" + parts[1] + "'");
        Uri.Builder picasaUriBuilder = new Uri.Builder()
                .scheme("content")
                .authority(PICASA_AUTHORITY)
                .appendPath(PICASA_ALBUM_PATH)
                .appendQueryParameter(PICASA_TYPE_KEY, PICASA_TYPE_IMAGE_VALUE);
        Cursor cursor = mResolver.query(picasaUriBuilder.build(),
                projection, selection, null, order);
        if (cursor != null) {
            log(TAG, " " + id + " resolved to " + cursor.getCount() + " albums");
            cursor.moveToFirst();

            int idIndex = cursor.getColumnIndex(PICASA_ID);
            int typeIndex = cursor.getColumnIndex(PICASA_ALBUM_TYPE);

            if (idIndex < 0) {
                log(TAG, "can't find the ID column!");
            } else {
                while (!cursor.isAfterLast()) {
                    albumIds.add(cursor.getString(idIndex));
                    cursor.moveToNext();
                }
            }
            cursor.close();
        } else {
            Log.w(TAG, "received a null cursor in resolveAlbumIds()");
        }
        return albumIds;
    }

    private Set<String> getFoundAlbums() {
        if (mFoundAlbumIds == null) {
            findAlbums();
        }
        return mFoundAlbumIds;
    }

    @Override
    public Collection<AlbumData> findAlbums() {
        log(TAG, "finding albums");
        HashMap<String, AlbumData> foundAlbums = new HashMap<String, AlbumData>();
        HashMap<String, String> accounts = new HashMap<String, String>();
        String[] projection = {PICASA_ID, PICASA_TITLE, PICASA_THUMB, PICASA_ALBUM_TYPE,
                               PICASA_ALBUM_USER, PICASA_ALBUM_UPDATED};
        Uri.Builder picasaUriBuilder = new Uri.Builder()
                .scheme("content")
                .authority(PICASA_AUTHORITY)
                .appendPath(PICASA_ALBUM_PATH)
                .appendQueryParameter(PICASA_TYPE_KEY, PICASA_TYPE_IMAGE_VALUE);
        Cursor cursor = mResolver.query(picasaUriBuilder.build(),
                projection, null, null, null);
        if (cursor != null) {
            cursor.moveToFirst();

            int idIndex = cursor.getColumnIndex(PICASA_ID);
            int thumbIndex = cursor.getColumnIndex(PICASA_THUMB);
            int titleIndex = cursor.getColumnIndex(PICASA_TITLE);
            int typeIndex = cursor.getColumnIndex(PICASA_ALBUM_TYPE);
            int updatedIndex = cursor.getColumnIndex(PICASA_ALBUM_UPDATED);
            int userIndex = cursor.getColumnIndex(PICASA_ALBUM_USER);

            if (idIndex < 0) {
                log(TAG, "can't find the ID column!");
            } else {
                while (!cursor.isAfterLast()) {
                    String id = TAG + ":" + cursor.getString(idIndex);
                    String user = (userIndex >= 0 ? cursor.getString(userIndex) : "-1");
                    String type = (typeIndex >= 0 ? cursor.getString(typeIndex) : "none");
                    boolean isPosts = (typeIndex >= 0 && PICASA_POSTS_TYPE.equals(type));
                    boolean isUpload = (typeIndex >= 0 && PICASA_UPLOAD_TYPE.equals(type));

                    String account = accounts.get(user);
                    if (account == null) {
                        account = resolveAccount(user);
                        accounts.put(user, account);
                    }

                    if (isPosts) {
                        log(TAG, "replacing " + id + " with " + PICASA_POSTS_TYPE);
                        id = TAG + ":" + PICASA_POSTS_TYPE + ":" + user;
                    }

                    if (isUpload) {
                        log(TAG, "replacing " + id + " with " + PICASA_UPLOAD_TYPE);
                        id = TAG + ":" + PICASA_UPLOAD_TYPE + ":" + user;
                    }

                    String thumbnailUrl = null;
                    long updated = 0;
                    AlbumData data = foundAlbums.get(id);
                    if (data == null) {
                        data = new AlbumData();
                        data.id = id;
                        data.account = account;

                        if (isPosts) {
                            data.title = mPostsAlbumName;
                        } else if (isUpload) {
                            data.title = mUploadsAlbumName;
                        } else if (titleIndex >= 0) {
                            data.title = cursor.getString(titleIndex);
                        } else {
                            data.title = mUnknownAlbumName;
                        }

                        log(TAG, "found " + data.title + "(" + data.id + ")" +
                                " of type " + type + " owned by " + user);
                        foundAlbums.put(id, data);
                    }

                    if (updatedIndex >= 0) {
                        updated = cursor.getLong(updatedIndex);
                    }

                    if (thumbIndex >= 0) {
                        thumbnailUrl = cursor.getString(thumbIndex);
                    }

                    data.updated = (long) Math.max(data.updated, updated);

                    if (data.thumbnailUrl == null || data.updated == updated) {
                        data.thumbnailUrl = thumbnailUrl;
                    }

                    cursor.moveToNext();
                }
            }
            cursor.close();

        } else {
            Log.w(TAG, "received a null cursor in findAlbums()");
        }
        log(TAG, "found " + foundAlbums.size() + " items.");
        mFoundAlbumIds = foundAlbums.keySet();
        return foundAlbums.values();
    }

    @Override
    protected InputStream getStream(ImageData data, int longSide) {
        InputStream is = null;
        try {
            Uri.Builder photoUriBuilder = new Uri.Builder()
                    .scheme("content")
                    .authority(PICASA_AUTHORITY)
                    .appendPath(PICASA_PHOTO_PATH)
                    .appendPath(data.id);
            if (mConnectivityManager.isActiveNetworkMetered() ||
                    ((2 * longSide) <= mDisplayLongSide)) {
                photoUriBuilder.appendQueryParameter(PICASA_TYPE_KEY, PICASA_TYPE_SCREEN_VALUE);
            } else {
                photoUriBuilder.appendQueryParameter(PICASA_TYPE_KEY, PICASA_TYPE_FULL_VALUE);
            }
            if (data.url != null) {
                photoUriBuilder.appendQueryParameter(PICASA_URL_KEY, data.url);
            }
            is = mResolver.openInputStream(photoUriBuilder.build());
        } catch (FileNotFoundException fnf) {
            log(TAG, "file not found: " + fnf);
            is = null;
        } catch (IOException ioe) {
            log(TAG, "i/o exception: " + ioe);
            is = null;
        }

        if (is != null) {
            mRecycleBin.offer(data);
            log(TAG, "RECYCLED");
            while (mRecycleBin.size() > mMaxRecycleSize) {
                mRecycleBin.poll();
            }
        }
        return is;
    }
}
