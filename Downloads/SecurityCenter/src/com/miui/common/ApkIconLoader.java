
package com.miui.common;

import java.lang.ref.SoftReference;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Message;
import android.widget.ImageView;

/**
 * Asynchronously loads file icons and thumbnail, mostly single-threaded.
 */
public class ApkIconLoader implements Callback {

    private static final String LOADER_THREAD_NAME = "FileIconLoader";

    /**
     * Type of message sent by the UI thread to itself to indicate that some
     * photos need to be loaded.
     */
    private static final int MESSAGE_REQUEST_LOADING = 1;

    /**
     * Type of message sent by the loader thread to indicate that some photos
     * have been loaded.
     */
    private static final int MESSAGE_ICON_LOADED = 2;

    private static abstract class ImageHolder {
        public static final int NEEDED = 0;

        public static final int LOADING = 1;

        public static final int LOADED = 2;

        int state;

        public static ImageHolder create() {
            return new DrawableHolder();
        };

        public abstract boolean setImageView(ImageView v);

        public abstract boolean isNull();

        public abstract void setImage(Object image);
    }

    private static class BitmapHolder extends ImageHolder {
        SoftReference<Bitmap> bitmapRef;

        @Override
        public boolean setImageView(ImageView v) {
            if (bitmapRef.get() == null)
                return false;
            v.setImageBitmap(bitmapRef.get());
            return true;
        }

        @Override
        public boolean isNull() {
            return bitmapRef == null;
        }

        @Override
        public void setImage(Object image) {
            bitmapRef = image == null ? null : new SoftReference<Bitmap>((Bitmap) image);
        }
    }

    private static class DrawableHolder extends ImageHolder {
        SoftReference<Drawable> drawableRef;

        @Override
        public boolean setImageView(ImageView v) {
            if (drawableRef.get() == null)
                return false;

            v.setImageDrawable(drawableRef.get());
            return true;
        }

        @Override
        public boolean isNull() {
            return drawableRef == null;
        }

        @Override
        public void setImage(Object image) {
            drawableRef = image == null ? null : new SoftReference<Drawable>((Drawable) image);
        }
    }

    /**
     * A soft cache for image thumbnails. the key is file pkgName
     */
    private final static ConcurrentHashMap<String, ImageHolder> mImageCache = new ConcurrentHashMap<String, ImageHolder>();

    /**
     * A map from ImageView to the corresponding photo ID. Please note that this
     * photo ID may change before the photo loading request is started.
     */
    private final ConcurrentHashMap<ImageView, FileId> mPendingRequests = new ConcurrentHashMap<ImageView, FileId>();

    /**
     * Handler for messages sent to the UI thread.
     */
    private final Handler mMainThreadHandler = new Handler(this);

    /**
     * Thread responsible for loading photos from the database. Created upon the
     * first request.
     */
    private LoaderThread mLoaderThread;

    /**
     * A gate to make sure we only send one instance of MESSAGE_PHOTOS_NEEDED at
     * a time.
     */
    private boolean mLoadingRequested;

    /**
     * Flag indicating if the image loading is paused.
     */
    private boolean mPaused;

    private final Context mContext;

    /**
     * Constructor.
     * 
     * @param context content context
     */
    public ApkIconLoader(Context context) {
        mContext = context;
    }

    public static class FileId {
        public String mPkgName;

        public FileId(String pkgName) {
            mPkgName = pkgName;
        }
    }

    /**
     * Load photo into the supplied image view. If the photo is already cached,
     * it is displayed immediately. Otherwise a request is sent to load the
     * photo from the database.
     * 
     * @param id, database id
     */
    public boolean loadIcon(ImageView view, String pkgName) {
        boolean loaded = loadCachedIcon(view, pkgName);
        if (loaded) {
            mPendingRequests.remove(view);
        } else {
            FileId p = new FileId(pkgName);
            mPendingRequests.put(view, p);
            if (!mPaused) {
                // Send a request to start loading photos
                requestLoading();
            }
        }
        return loaded;
    }

    public void cancelRequest(ImageView view) {
        mPendingRequests.remove(view);
    }

    /**
     * Checks if the photo is present in cache. If so, sets the photo on the
     * view, otherwise sets the state of the photo to
     * {@link BitmapHolder#NEEDED}
     */
    private boolean loadCachedIcon(ImageView view, String pkgName) {
        ImageHolder holder = mImageCache.get(pkgName);

        if (holder == null) {
            holder = ImageHolder.create();
            if (holder == null)
                return false;

            mImageCache.put(pkgName, holder);
        } else if (holder.state == ImageHolder.LOADED) {
            if (holder.isNull()) {
                return true;
            }

            // failing to set imageview means that the soft reference was
            // released by the GC, we need to reload the photo.
            if (holder.setImageView(view)) {
                return true;
            }
        }

        holder.state = ImageHolder.NEEDED;
        return false;
    }

    /**
     * Stops loading images, kills the image loader thread and clears all
     * caches.
     */
    public void stop() {
        pause();

        if (mLoaderThread != null) {
            mLoaderThread.quit();
            mLoaderThread = null;
        }

        clear();
    }

    public void clear() {
        mPendingRequests.clear();
        mImageCache.clear();
    }

    /**
     * Temporarily stops loading
     */
    public void pause() {
        mPaused = true;
    }

    /**
     * Resumes loading
     */
    public void resume() {
        mPaused = false;
        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * Sends a message to this thread itself to start loading images. If the
     * current view contains multiple image views, all of those image views will
     * get a chance to request their respective photos before any of those
     * requests are executed. This allows us to load images in bulk.
     */
    private void requestLoading() {
        if (!mLoadingRequested) {
            mLoadingRequested = true;
            mMainThreadHandler.sendEmptyMessage(MESSAGE_REQUEST_LOADING);
        }
    }

    /**
     * Processes requests on the main thread.
     */
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_REQUEST_LOADING: {
                mLoadingRequested = false;
                if (!mPaused) {
                    if (mLoaderThread == null) {
                        mLoaderThread = new LoaderThread();
                        mLoaderThread.start();
                    }

                    mLoaderThread.requestLoading();
                }
                return true;
            }

            case MESSAGE_ICON_LOADED: {
                if (!mPaused) {
                    processLoadedIcons();
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Goes over pending loading requests and displays loaded photos. If some of
     * the photos still haven't been loaded, sends another request for image
     * loading.
     */
    private void processLoadedIcons() {
        Iterator<ImageView> iterator = mPendingRequests.keySet().iterator();
        while (iterator.hasNext()) {
            ImageView view = iterator.next();
            FileId fileId = mPendingRequests.get(view);
            boolean loaded = loadCachedIcon(view, fileId.mPkgName);
            if (loaded) {
                iterator.remove();
            }
        }

        if (!mPendingRequests.isEmpty()) {
            requestLoading();
        }
    }

    /**
     * The thread that performs loading of photos from the database.
     */
    private class LoaderThread extends HandlerThread implements Callback {
        private Handler mLoaderThreadHandler;

        public LoaderThread() {
            super(LOADER_THREAD_NAME);
        }

        /**
         * Sends a message to this thread to load requested photos.
         */
        public void requestLoading() {
            if (mLoaderThreadHandler == null) {
                mLoaderThreadHandler = new Handler(getLooper(), this);
            }
            mLoaderThreadHandler.sendEmptyMessage(0);
        }

        /**
         * Receives the above message, loads photos and then sends a message to
         * the main thread to process them.
         */
        public boolean handleMessage(Message msg) {
            Iterator<FileId> iterator = mPendingRequests.values().iterator();
            while (iterator.hasNext()) {
                FileId id = iterator.next();
                ImageHolder holder = mImageCache.get(id.mPkgName);
                if (holder != null && holder.state == ImageHolder.NEEDED) {
                    // Assuming atomic behavior
                    holder.state = ImageHolder.LOADING;

                    Drawable icon = getApkIcon(mContext, id.mPkgName);
                    holder.setImage(icon);

                    holder.state = BitmapHolder.LOADED;
                    mImageCache.put(id.mPkgName, holder);
                }
            }

            mMainThreadHandler.sendEmptyMessage(MESSAGE_ICON_LOADED);
            return true;
        }

        private Drawable getApkIcon(Context context, String pkgName) {
            PackageManager pm = context.getPackageManager();
            try {
                return pm.getApplicationIcon(pkgName);
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            }
            return FileIconHelper.getDefaultApkIcon(context);
        }
    }
}