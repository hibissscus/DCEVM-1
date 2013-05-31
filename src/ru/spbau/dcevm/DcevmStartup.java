package ru.spbau.dcevm;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.io.ZipUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

/**
 * User: yarik
 * Date: 4/15/13
 * Time: 3:54 AM
 */
public class DcevmStartup implements StartupActivity {

  private static final Logger LOG = Logger.getInstance("#" + DcevmStartup.class.getName());

  @NotNull private final DcevmUrlProvider             myUrlProvider;
  @NotNull private final DcevmFileManager             myFileManager;
  @NotNull private final DcevmNetworkManager          myNetworkManager;
  @NotNull private final DcevmNotificationManager     myNotificationManager;

  public DcevmStartup(@NotNull DcevmUrlProvider urlProvider,
                      @NotNull DcevmFileManager fileManager,
                      @NotNull DcevmNetworkManager networkManager,
                      @NotNull DcevmNotificationManager notificationManager)
  {
    myUrlProvider = urlProvider;
    myFileManager = fileManager;
    myNetworkManager = networkManager;
    myNotificationManager = notificationManager;
  }

  @Override
  public void runActivity(final Project project) {
    final Application application = ApplicationManager.getApplication();

    final String url = myUrlProvider.getUrl();
    if (StringUtil.isEmpty(url)) {
      PropertiesComponent propertiesComponent = PropertiesComponent.getInstance(project);
      if (propertiesComponent.getBoolean(DcevmConstants.NO_DCEVM_ASSEMBLED_FOR_CURRENT_ENVIRONMENT_MESSAGE_SHOWN_KEY, false)) {
        myNotificationManager.notifyNoDcevmAvailableForLocalEnvironment(project);
        propertiesComponent.setValue(DcevmConstants.NO_DCEVM_ASSEMBLED_FOR_CURRENT_ENVIRONMENT_MESSAGE_SHOWN_KEY, Boolean.TRUE.toString());
      }
      return;
    }
    assert url != null;

    if (isAvailableLocally()) {
      application.executeOnPooledThread(new Runnable() {
        @Override
        public void run() {
          checkIfDcevmUpToDate(project, url);
        }
      });
      return;
    }


    myNotificationManager.askPermissionToDownloadDcevm(project, new Runnable() {
      @Override
      public void run() {
        download(project, url);
      }
    });
  }

  private void checkIfDcevmUpToDate(@NotNull final Project project, @NotNull final String url) {
    int remoteSize = myNetworkManager.getSize(url);
    if (remoteSize <= 0) {
      return;
    }
    int localSize = PropertiesComponent.getInstance(project).getOrInitInt(DcevmConstants.LOCAL_DCEVM_SIZE_KEY, -1);
    if (localSize != remoteSize) {
      myNotificationManager.askPermissionToUpdateDcevm(project, new Runnable() {
        @Override
        public void run() {
          download(project, url);
        }
      });
    }
  }

  private void download(@NotNull final Project project, @NotNull final String url) {
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Downloading " + DcevmConstants.DCEVM_NAME, true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        final File tmpFile = new File(FileUtilRt.getTempDirectory(), DcevmConstants.DCEVM_NAME);
        FileUtilRt.delete(tmpFile);
        myNetworkManager.download(tmpFile, url, indicator, new Runnable() {
          @Override
          public void run() {
            long length = tmpFile.length();
            PropertiesComponent.getInstance(project).setValue(DcevmConstants.LOCAL_DCEVM_SIZE_KEY, String.valueOf(length));
            FileUtilRt.delete(myFileManager.getDcevmDir());
            try {
              ZipUtil.extract(tmpFile, myFileManager.getDcevmDir(), null);
              patchProjectIfNecessary(project);
            }
            catch (IOException e) {
              LOG.warn("Unexpected error on unzipping DCEVM", e);
            }
          }
        });
      }
    });
  }

  private void patchProjectIfNecessary(@NotNull final Project project) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            ServiceManager.getService(project, DcevmRunConfigurationManager.class).patchIfNecessary(project);
          }
        });
      }
    });

  }

  private boolean isAvailableLocally() {
    File dir = myFileManager.getDcevmDir();
    return dir.isDirectory() && dir.list().length > 0;
  }
}