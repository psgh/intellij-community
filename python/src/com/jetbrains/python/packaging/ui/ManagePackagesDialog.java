package com.jetbrains.python.packaging.ui;

import com.google.common.collect.Lists;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.FilterComponent;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.UiNotifyConnector;
import com.jetbrains.python.packaging.*;
import com.jetbrains.python.packaging.ui.ManageRepoDialog;
import com.jetbrains.python.packaging.ui.PyPackagesNotificationPanel;
import com.jetbrains.python.packaging.ui.PyPackagesPanel;
import com.jetbrains.python.sdk.PythonSdkType;
import org.apache.xmlrpc.AsyncCallback;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLFrameHyperlinkEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.List;

/**
 * User: catherine
 * <p/>
 * UI for installing python packages
 */
@SuppressWarnings("UseOfObsoleteCollectionType")
public class ManagePackagesDialog extends DialogWrapper {
  @NonNls private static final String TEXT_PREFIX = "<html><head>" +
                                                    "    <style type=\"text/css\">" +
                                                    "        p {" +
                                                    "            font-family: Arial,serif; font-size: 12pt; margin: 2px 2px" +
                                                    "        }" +
                                                    "    </style>" +
                                                    "</head><body style=\"font-family: Arial,serif; font-size: 12pt; margin: 5px 5px;\">";
  @NonNls private static final String TEXT_SUFFIX = "</body></html>";

  @NonNls private static final String HTML_PREFIX = "<a href=\"";
  @NonNls private static final String HTML_SUFFIX = "</a>";

  private JPanel myFilter;
  private JPanel myMainPanel;
  private JEditorPane myDescriptionTextArea;
  private JBList myPackages;
  private JButton myInstallButton;
  private JCheckBox myOptionsCheckBox;
  private JTextField myOptionsField;
  private JCheckBox myInstallToUser;
  private JComboBox myVersionComboBox;
  private JCheckBox myVersionCheckBox;
  private JButton myManageButton;
  private final PyPackagesNotificationPanel myNotificationArea;
  private JPanel myPackagesPanel;
  private JSplitPane mySplitPane;
  private JPanel myNotificationsAreaPlaceholder;
  private PackagesModel myPackagesModel;
  private final Set<String> myInstalledPackages;
  private final PyPackagesPanel myPackageListPanel;

  public ManagePackagesDialog(@NotNull Project project, @NotNull final Sdk sdk, @NotNull final PyPackagesPanel packageListPanel) {
    super(false);

    myInstallToUser.setEnabled(!PythonSdkType.isVirtualEnv(sdk));
    myInstallToUser.setSelected(false);

    myPackageListPanel = packageListPanel;
    init();
    final JBTable table = myPackageListPanel.getPackagesTable();
    setTitle("Available Packages");
    myPackagesModel = new PackagesModel(project);
    myPackages = new JBList();
    myNotificationArea = new PyPackagesNotificationPanel(project);
    myNotificationsAreaPlaceholder.add(myNotificationArea.getComponent(), BorderLayout.CENTER);

    final AnActionButton reloadButton = new AnActionButton("Reload List of Packages", IconLoader.getIcon("/vcs/refresh.png")) {
      @Override
      public void actionPerformed(AnActionEvent e) {
        myPackages.setPaintBusy(true);
        final Application application = ApplicationManager.getApplication();
        application.executeOnPooledThread(new Runnable() {
          @Override
          public void run() {
            try {
              final PyPackageService service = PyPackageService.getInstance();
              PyPIPackageUtil.INSTANCE.updatePyPICache(service);
              service.LAST_TIME_CHECKED = System.currentTimeMillis();
              myPackages.setPaintBusy(false);
            }
            catch (IOException e) {
              application.invokeLater(new Runnable() {
                @Override
                public void run() {
                  Messages.showErrorDialog("Failed to update package list. Please, check your internet connection.",
                                           "Update Package List Failed");
                  myPackages.setPaintBusy(false);
                }
              }, ModalityState.stateForComponent(myPackages));
            }
          }
        });
      }
    };
    myPackagesPanel = ToolbarDecorator.createDecorator(this.myPackages).disableAddAction().
      disableUpDownActions().disableRemoveAction().addExtraAction(reloadButton).createPanel();
    myPackagesPanel.setPreferredSize(new Dimension(400, -1));
    mySplitPane.setLeftComponent(myPackagesPanel);

    myPackages.setModel(myPackagesModel);
    myPackages.addListSelectionListener(new MyPackageSelectionListener());
    if (myInstallToUser.isEnabled())
      myInstallToUser.setSelected(PyPackageService.getInstance().useUserSite(sdk.getHomePath()));
    myInstallToUser.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        PyPackageService.getInstance().addSdkToUserSite(sdk.getHomePath(), myInstallToUser.isSelected());
      }
    });
    myOptionsCheckBox.setEnabled(false);
    myVersionCheckBox.setEnabled(false);
    myVersionCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        myVersionComboBox.setEnabled(myVersionCheckBox.isSelected());
      }
    });

    UiNotifyConnector.doWhenFirstShown(myPackages, new Runnable() {
      @Override
      public void run() {
        initModel();
      }
    });
    myOptionsCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        myOptionsField.setEnabled(myOptionsCheckBox.isSelected());
      }
    });
    myInstallButton.setEnabled(false);
    myDescriptionTextArea.addHyperlinkListener(new MyHyperlinkListener());
    addInstallAction(sdk, project, table);
    myInstalledPackages = new HashSet<String>();
    updateInstalledNames(table);
    addManageAction();
    myInstallToUser.setText("Install to user's site packages directory (" + PyPackageManager.getUserSite() + ")");
  }

  public void setSelected(String pyPackage) {
    myPackages.setSelectedValue(new ComparablePair(pyPackage, PyPIPackageUtil.PYPI_URL), true);
  }

  void updateInstalledNames(JBTable table) {
    for (Object p : ((DefaultTableModel)table.getModel()).getDataVector()) {
      myInstalledPackages.add(((Vector)p).get(0).toString());
    }
  }

  private void addManageAction() {
    myManageButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        ManageRepoDialog dialog = new ManageRepoDialog();
        dialog.show();
      }
    });
    myPackages.setCellRenderer(new MyTableRenderer());
  }

  private static String composeHref(String vendorUrl) {
    return HTML_PREFIX + vendorUrl + "\">" + vendorUrl + HTML_SUFFIX;
  }

  private void addInstallAction(@NotNull final Sdk sdk,
                                @NotNull final Project project,
                                @NotNull final JBTable table) {
    myInstallButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent event) {
        final Object pyPackage = myPackages.getSelectedValue();
        if (pyPackage instanceof ComparablePair) {
          final ComparablePair pair = (ComparablePair)pyPackage;
          final String packageName = pair.getFirst();
          final String repository = pair.getSecond().equals(PyPIPackageUtil.PYPI_URL) ? null : pair.getSecond();
          final List<String> extraArgs = new ArrayList<String>();
          if (myInstallToUser.isSelected()) {
            extraArgs.add(PyPackageManager.USE_USER_SITE);
          }
          if (myOptionsCheckBox.isEnabled() && myOptionsCheckBox.isSelected()) {
            // TODO: Respect arguments quotation
            Collections.addAll(extraArgs, myOptionsField.getText().split(" +"));
          }
          if (repository != null) {
            extraArgs.add("--extra-index-url");
            extraArgs.add(repository);
          }
          final PyRequirement req;
          if (myVersionCheckBox.isEnabled() && myVersionCheckBox.isSelected()) {
            req = new PyRequirement(packageName, (String)myVersionComboBox.getSelectedItem());
          }
          else {
            req = new PyRequirement(packageName);
          }
          final PyPackageManager.UI ui = new PyPackageManager.UI(project, sdk, new PyPackageManager.UI.Listener() {
            @Override
            public void started() {
              setDownloadStatus(true);
              table.setPaintBusy(true);
            }

            @Override
            public void finished(@Nullable List<PyExternalProcessException> exceptions) {
              table.clearSelection();
              setDownloadStatus(false);
              addNotifications(exceptions, packageName, myNotificationArea, myPackageListPanel.getNotificationsArea());
              myPackageListPanel.updatePackages(sdk, myInstalledPackages);
            }
          });
          ui.install(Collections.singletonList(req), extraArgs);
        }
      }
    });
  }

  private static void addNotifications(final List<PyExternalProcessException> exceptions,
                                       final String packageName,
                                       final PyPackagesNotificationPanel... areas) {
    for (PyPackagesNotificationPanel pane : areas) {
      if (exceptions.isEmpty()) {
        pane.showSuccess("Package successfully installed.");
      }
      else {
        String title = "Install packages failed";
        final String firstLine = title + ": Error occurred when installing package " + packageName + ". ";
        pane.showError(firstLine + "<a href=\"xxx\">Details...</a>",
                       title,
                       PyPackageManager.UI.createDescription(exceptions, firstLine));
      }
    }
  }

  public void initModel() {
    setDownloadStatus(true);
    final Application application = ApplicationManager.getApplication();
    application.executeOnPooledThread(new Runnable() {
      @Override
      public void run() {
        try {
          final Collection<String> packageNames = PyPIPackageUtil.INSTANCE.getPackageNames();
          application.invokeLater(new Runnable() {
            @Override
            public void run() {
              for (String name : packageNames) {
                myPackagesModel.add(PyPIPackageUtil.PYPI_URL, name);
              }

              myPackagesModel.add(Lists.newArrayList(PyPIPackageUtil.INSTANCE.getAdditionalPackageNames()));
              setDownloadStatus(false);
            }
          }, ModalityState.stateForComponent(myPackages));
        }
        catch (final IOException e) {
          application.invokeLater(new Runnable() {
            @Override
            public void run() {
              Messages.showErrorDialog("IO Error occurred. Could not reach url " + e.getMessage() +
                                       ". Please, check your internet connection.", "Packages");
              setDownloadStatus(false);
            }
          }, ModalityState.stateForComponent(myPackages));
        }
      }
    });
  }

  protected void setDownloadStatus(boolean status) {
    myPackages.setPaintBusy(status);
  }

  @Override
  protected JComponent createCenterPanel() {
    return myMainPanel;
  }

  private void createUIComponents() {
    myFilter = new MyPackageFilter();
  }

  public class MyPackageFilter extends FilterComponent {

    public MyPackageFilter() {
      super("PACKAGE_FILTER", 5);
    }

    public void filter() {
      myPackagesModel.filter(getFilter());
    }
  }

  private static class PackagesModel extends CollectionListModel<Pair> {
    private Project myProject;

    public PackagesModel(Project project) {
      myProject = project;
    }

    public void add(String urlResource, String element) {
      super.add(new ComparablePair(element, urlResource));
    }

    protected void filter(String filter) {
      try {
        Collection<String> toProcess = PyPIPackageUtil.INSTANCE.getPackageNames();
        for (ComparablePair name : PyPIPackageUtil.INSTANCE.getAdditionalPackageNames()) {
          toProcess.add(name.first);
        }

        removeAll();
        if (StringUtil.isEmptyOrSpaces(filter)) {
          for (String name : toProcess) {
            add(PyPIPackageUtil.PYPI_URL, name);
          }
          return;
        }
        for (String packageName : toProcess) {
          if (StringUtil.containsIgnoreCase(packageName, filter)) {
            add(PyPIPackageUtil.PYPI_URL, packageName);
          }
        }
      }
      catch (IOException e) {
        Messages.showErrorDialog("IO Error occurred. Could not reach url " + e.getMessage() +
                                 ". Please, check your internet connection.", "Packages");
      }
    }
  }

  @Nullable
  public JComponent getPreferredFocusedComponent() {
    return myFilter;
  }

  public static class MyHyperlinkListener implements HyperlinkListener {
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
        JEditorPane pane = (JEditorPane)e.getSource();
        if (e instanceof HTMLFrameHyperlinkEvent) {
          HTMLFrameHyperlinkEvent evt = (HTMLFrameHyperlinkEvent)e;
          HTMLDocument doc = (HTMLDocument)pane.getDocument();
          doc.processHTMLFrameHyperlinkEvent(evt);
        }
        else {
          URL url = e.getURL();
          if (url != null) {
            BrowserUtil.launchBrowser(url.toString());
          }
        }
      }
    }
  }

  public class MyPackageSelectionListener implements ListSelectionListener {
    @Override
    public void valueChanged(ListSelectionEvent event) {
      myOptionsCheckBox.setEnabled(myPackages.getSelectedIndex() >= 0);
      myVersionCheckBox.setEnabled(myPackages.getSelectedIndex() >= 0);
      myOptionsCheckBox.setSelected(false);
      myVersionCheckBox.setSelected(false);
      myVersionComboBox.setEnabled(false);
      myOptionsField.setEnabled(false);
      myDescriptionTextArea.setText("");

      setDownloadStatus(true);
      final Object pyPackage = myPackages.getSelectedValue();
      if (pyPackage instanceof ComparablePair) {
        final String packageName = ((ComparablePair)pyPackage).getFirst();
        myVersionComboBox.removeAllItems();
        if (myVersionCheckBox.isEnabled()) {
          PyPIPackageUtil.INSTANCE.usePackageReleases(packageName, new AsyncCallback() {
            @Override
            public void handleResult(Object result, URL url, String method) {
              final List<String> releases = (List<String>)result;
              PyPIPackageUtil.INSTANCE.addPackageReleases(packageName, releases);
                ApplicationManager.getApplication().invokeLater(new Runnable() {
                  @Override
                  public void run() {
                    myVersionComboBox.removeAllItems();
                    for (String release : releases) {
                      myVersionComboBox.addItem(release);
                    }
                  }
                }, ModalityState.any());
            }
            @Override
            public void handleError(Exception exception, URL url, String method) {
            }
          });
        }
        myInstallButton.setEnabled(true);

        PyPIPackageUtil.INSTANCE.fillPackageDetails(packageName, new AsyncCallback() {
          @Override
          public void handleResult(Object result, URL url, String method) {
            final Hashtable details = (Hashtable)result;
            PyPIPackageUtil.INSTANCE.addPackageDetails(packageName, details);
            ApplicationManager.getApplication().invokeLater(new Runnable() {
              @Override
              public void run() {
                addDetails(details);
              }
            }, ModalityState.any());
          }
          @Override
          public void handleError(Exception exception, URL url, String method) {
          }
        });
      }
      else {
        myInstallButton.setEnabled(false);
      }
      setDownloadStatus(false);
    }

    private void addDetails(Hashtable details) {
      Object description = details.get("summary");
      StringBuilder stringBuilder = new StringBuilder(TEXT_PREFIX);
      if (description instanceof String) {
        stringBuilder.append(description).append("<br/>");
      }
      Object version = details.get("version");
      if (version instanceof String && !StringUtil.isEmpty((String)version)) {
        stringBuilder.append("<h4>Version</h4>");
        stringBuilder.append(version);
      }
      Object author = details.get("author");
      if (author instanceof String && !StringUtil.isEmpty((String)author)) {
        stringBuilder.append("<h4>Author</h4>");
        stringBuilder.append(author).append("<br/><br/>");
      }
      Object authorEmail = details.get("author_email");
      if (authorEmail instanceof String && !StringUtil.isEmpty((String)authorEmail)) {
        stringBuilder.append("<br/>");
        stringBuilder.append(composeHref("mailto:" + authorEmail));
      }
      Object homePage = details.get("home_page");
      if (homePage instanceof String && !StringUtil.isEmpty((String)homePage)) {
        stringBuilder.append("<br/>");
        stringBuilder.append(composeHref((String)homePage));
      }
      stringBuilder.append(TEXT_SUFFIX);
      myDescriptionTextArea.setText(stringBuilder.toString());
    }
  }

  protected Action[] createActions() {
    return new Action[0];
  }

  private class MyTableRenderer extends DefaultListCellRenderer {
    private JLabel myNameLabel = new JLabel();
    private JLabel myRepositoryLabel = new JLabel();
    private JPanel myPanel = new JPanel(new BorderLayout());

    private MyTableRenderer() {
      myPanel.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 1));
      myRepositoryLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
      myPanel.add(myNameLabel, BorderLayout.WEST);
      myPanel.add(myRepositoryLabel, BorderLayout.EAST);
      myNameLabel.setOpaque(true);
      myPanel.setPreferredSize(new Dimension(300, 20));
    }

    @Override
    public Component getListCellRendererComponent(JList list,
                                                  Object value,
                                                  int index,
                                                  boolean isSelected,
                                                  boolean cellHasFocus) {
      if (value instanceof Pair) {
        String name = (String)((Pair)value).getFirst();
        myNameLabel.setText(name);
        myRepositoryLabel.setText((String)((Pair)value).getSecond());
        myNameLabel.setForeground(myInstalledPackages.contains(name) ? Color.BLUE : Color.BLACK);
      }
      myRepositoryLabel.setForeground(Color.GRAY);

      final Color bg = isSelected ? UIUtil.getListSelectionBackground() : UIUtil.getListBackground();
      myPanel.setBackground(bg);
      myNameLabel.setBackground(bg);
      myRepositoryLabel.setBackground(bg);
      return myPanel;
    }
  }
}