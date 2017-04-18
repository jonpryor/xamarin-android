package mono.android;

import mono.android.incrementaldeployment.IncrementalClassLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.*;
import android.util.Log;
import dalvik.system.BaseDexClassLoader;

public class MultiDexInjector extends ContentProvider {
  
  boolean created;
  
	@Override
	public boolean onCreate ()
	{
    Log.w ("jonp", "MyContentProvider.onCreate");
		return created = true;
	}

	@Override
	public void attachInfo (android.content.Context context, android.content.pm.ProviderInfo info) {
    Log.w ("jonp", "MyContentProvider.attachInfo: created=" + created + "; context=" + context.getClass());
    mIncrementalDeploymentDir = getIncrementalDeploymentDir (context);
    Log.w ("jonp", "MyContentProvider.mIncrementalDeploymentDir=" + mIncrementalDeploymentDir);
    externalResourceFile = getExternalResourceFile ();
    Log.w ("jonp", "MyContentProvider.externalResourceFile=" + externalResourceFile);


    File codeCacheDir = context.getCacheDir();
    String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
    String dataDir = context.getApplicationInfo().dataDir;
    String packageName = context.getPackageName();

    Log.w ("jonp", "# jonp: codeCacheDir=" + codeCacheDir.getAbsolutePath());
    Log.w ("jonp", "# jonp: nativeLibDir=" + nativeLibDir);
    Log.w ("jonp", "# jonp: dataDir=" + dataDir);
    Log.w ("jonp", "# jonp: packageName=" + packageName);

  	List<String> dexes = getDexList(packageName);
  	if (dexes != null) {
      Log.w ("jonp", "Have dexes!");
      IncrementalClassLoader.inject(
          MultiDexInjector.class.getClassLoader(),
          packageName,
          codeCacheDir,
          nativeLibDir,
          dexes);
  	}
    monkeyPatchExistingResources();
    super.attachInfo(context, info);
	}


  private String mIncrementalDeploymentDir;
  private String externalResourceFile;

  
  private static String getIncrementalDeploymentDir (Context context)
  {
    // For initial setup by Seppuku, it needs to create the dex deployment directory at app bootstrap.
    // dex is special, important for mono runtime bootstrap.
    String dir = new File (
      android.os.Environment.getExternalStorageDirectory (),
      "Android/data/" + context.getPackageName ()).getAbsolutePath ();
    dir = new File (dir).exists () ?
    	dir + "/files" :
    	"/data/data/" + context.getPackageName () + "/files";
    String dexDir = dir + "/.__override__/dexes";
    if (!new File (dexDir).exists ())
      new File (dexDir).mkdirs ();
    return dir + "/";
  }

  private String getExternalResourceFile() {
    String base = mIncrementalDeploymentDir;
    String resourceFile = base + ".__override__/packaged_resources";
    if (!(new File(resourceFile).isFile())) {
      resourceFile = base + ".__override__/resources";
      if (!(new File(resourceFile).isDirectory())) {
        Log.v("StubApplication", "Cannot find external resources, not patching them in");
        return null;
      }
    }

    Log.v("StubApplication", "Found external resources at " + resourceFile);
    return resourceFile;
  }

  private List<String> getDexList(String packageName) {
    List<String> result = new ArrayList<String>();
    String dexDirectory = mIncrementalDeploymentDir + ".__override__/dexes";
    Log.w ("jonp", "Checking directory for .dex files: " + dexDirectory);
    File[] dexes = new File(dexDirectory).listFiles();
    // It is not illegal state when it was launched to start Seppuku
    if (dexes == null) {
      return null;
    } else {
      for (File dex : dexes) {
        if (dex.getName().endsWith(".dex")) {
          Log.w ("jonp", "# jonp: found file: " + dex.getPath());
          result.add(dex.getPath());
        }
      }
    }

    return result;
  }

  private void monkeyPatchExistingResources() {
    if (externalResourceFile == null) {
      return;
    }

    try {
      // Create a new AssetManager instance and point it to the resources installed under
      // /sdcard
      AssetManager newAssetManager = AssetManager.class.getConstructor().newInstance();
      Method mAddAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
      mAddAssetPath.setAccessible(true);
      if (((int) (Integer) mAddAssetPath.invoke(newAssetManager, externalResourceFile)) == 0) {
        throw new IllegalStateException("Could not create new AssetManager");
      }

      // Kitkat needs this method call, Lollipop doesn't. However, it doesn't seem to cause any harm
      // in L, so we do it unconditionally.
      Method mEnsureStringBlocks = AssetManager.class.getDeclaredMethod("ensureStringBlocks");
      mEnsureStringBlocks.setAccessible(true);
      mEnsureStringBlocks.invoke(newAssetManager);

      // Find the singleton instance of ResourcesManager
      Class<?> clazz = Class.forName("android.app.ResourcesManager");
      Method mGetInstance = clazz.getDeclaredMethod("getInstance");
      mGetInstance.setAccessible(true);
      Object resourcesManager = mGetInstance.invoke(null);

      Field mAssets = null;
      try {
        // Android N and later has mResourcesImpl/*of ResourcesImpl*/.mAssets instead.
        Class<?> resImplClass = Class.forName("android.content.res.ResourcesImpl");
        mAssets = resImplClass.getDeclaredField("mAssets");
      } catch (NoSuchFieldException ex) {
        try {
          mAssets = Resources.class.getDeclaredField("mAssets");
          mAssets.setAccessible(true);
        } catch (NoSuchFieldException ex2) {
          Log.w("StubApplication", "Failed to replace AssetManager. Assets will fail to load; " + ex.getMessage() + " / " + ex2.getMessage());
        }
      }
      // Iterate over all known Resources objects
      try {
        Field fMActiveResources = clazz.getDeclaredField("mActiveResources");
        fMActiveResources.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<?, WeakReference<Resources>> arrayMap =
            (Map<?, WeakReference<Resources>>) fMActiveResources.get(resourcesManager);
        for (WeakReference<Resources> wr : arrayMap.values()) {
          Resources resources = wr.get();
          // Set the AssetManager of the Resources instance to our brand new one
          if (mAssets != null)
            mAssets.set(resources, newAssetManager);
          resources.updateConfiguration(resources.getConfiguration(), resources.getDisplayMetrics());
        }
      } catch (NoSuchFieldException ex) {
         Log.w("StubApplication", "Failed to replace ResourceManager. Some resources may fail to load; " + ex.getMessage());
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(e);
    } catch (InstantiationException e) {
      throw new IllegalStateException(e);
    }
  }
  
  // ---
	@Override
	public android.database.Cursor query (android.net.Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public String getType (android.net.Uri uri)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public android.net.Uri insert (android.net.Uri uri, android.content.ContentValues initialValues)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public int delete (android.net.Uri uri, String where, String[] whereArgs)
	{
		throw new RuntimeException ("This operation is not supported.");
	}

	@Override
	public int update (android.net.Uri uri, android.content.ContentValues values, String where, String[] whereArgs)
	{
		throw new RuntimeException ("This operation is not supported.");
	}
}