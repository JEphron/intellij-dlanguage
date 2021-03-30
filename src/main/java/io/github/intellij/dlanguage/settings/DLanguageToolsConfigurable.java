package io.github.intellij.dlanguage.settings;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.ide.ui.search.SearchableOptionsRegistrar;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.TextAccessor;
import com.intellij.util.messages.Topic;
import io.github.intellij.dlanguage.messagebus.ToolChangeListener;
import io.github.intellij.dlanguage.messagebus.Topics;
import io.github.intellij.dlanguage.tools.DtoolUtils;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.*;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static io.github.intellij.dlanguage.tools.DtoolUtils.*;

/**
 * The "D Tools" option in: Settings -> Languages & Frameworks -> D Tools.
 */
public class DLanguageToolsConfigurable implements SearchableConfigurable {

    public static final String D_TOOLS_ID = "D Tools";
    private static final Logger LOG = Logger.getInstance(DLanguageToolsConfigurable.class);
    private final PropertiesComponent propertiesComponent;
    private final List<Tool> properties;
    // Swing components.
    private JPanel mainPanel;
    private TextFieldWithBrowseButton dubPath;
    private RawCommandLineEditor dubFlags;
    private JButton dubAutoFind;
    private JTextField dubVersion;
    private TextFieldWithBrowseButton dscannerPath;
    private RawCommandLineEditor dscannerFlags;
    private JButton dscannerAutoFind;
    private JTextField dscannerVersion;
    private TextFieldWithBrowseButton dcdPath;
    private RawCommandLineEditor dcdFlags;
    private JButton dcdAutoFind;
    private JTextField dcdVersion;
    private TextFieldWithBrowseButton dcdClientPath;
    private RawCommandLineEditor dcdClientFlags;
    private JButton dcdClientAutoFind;
    private JTextField dcdClientVersion;
    private TextFieldWithBrowseButton dFormatPath;
    private RawCommandLineEditor dFormatFlags;
    private JButton dFormatAutoFind;
    private JTextField dFormatVersion;
    private TextFieldWithBrowseButton dFixPath;
    private RawCommandLineEditor dFixFlags;
    private JButton dFixAutoFind;
    private JTextField dFixVersion;
    private TextFieldWithBrowseButton GDBPath;
    private JButton GDBAutoFind;
    private RawCommandLineEditor GDBFlags;
    private JTextField GDBVersion;
    private JTabbedPane tabbedPane1;
    private JCheckBox chkNativeCodeCompletion;

    public DLanguageToolsConfigurable(@NotNull final Project project) {
        this.propertiesComponent = PropertiesComponent.getInstance();

        final SearchableOptionsRegistrar sor = SearchableOptionsRegistrar.getInstance();
        sor.addOption("dub", null, "dub", D_TOOLS_ID, D_TOOLS_ID);
        sor.addOption("dscanner", null, "dscanner", D_TOOLS_ID, D_TOOLS_ID);
        sor.addOption("dcd", null, "dcd", D_TOOLS_ID, D_TOOLS_ID);
        sor.addOption("dfmt", null, "dfmt", D_TOOLS_ID, D_TOOLS_ID);
        sor.addOption("dfix", null, "dfix", D_TOOLS_ID, D_TOOLS_ID);
        sor.addOption("gdb", null, "gdb", D_TOOLS_ID, D_TOOLS_ID);

        properties = Arrays.asList(
            new Tool(project, "dub", ToolKey.DUB_KEY, dubPath, dubFlags,
                dubAutoFind, dubVersion, DUB_LATEST, Topics.DUB_TOOL_CHANGE),
            new Tool(project, "dscanner", ToolKey.DSCANNER_KEY, dscannerPath, dscannerFlags,
                dscannerAutoFind, dscannerVersion, DSCANNER_LATEST, Topics.DSCANNER_TOOL_CHANGE),
            new Tool(project, "dcd-server", ToolKey.DCD_SERVER_KEY, dcdPath, dcdFlags,
                dcdAutoFind, dcdVersion, DCD_LATEST, Topics.DCD_SERVER_TOOL_CHANGE),
            new Tool(project, "dcd-client", ToolKey.DCD_CLIENT_KEY, dcdClientPath, dcdClientFlags,
                dcdClientAutoFind, dcdClientVersion, DCD_LATEST, Topics.DCD_CLIENT_TOOL_CHANGE),
            new Tool(project, "dfmt", ToolKey.DFORMAT_KEY, dFormatPath, dFormatFlags,
                dFormatAutoFind, dFormatVersion, DFORMAT_LATEST, Topics.DFMT_TOOL_CHANGE),
            new Tool(project, "dfix", ToolKey.DFIX_KEY, dFixPath, dFixFlags,
                dFixAutoFind, dFixVersion, DFIX_LATEST, Topics.DFIX_TOOL_CHANGE),
            new Tool(project, "gdb", ToolKey.GDB_KEY, GDBPath, GDBFlags,
                GDBAutoFind, GDBVersion, null, Topics.GDB_TOOL_CHANGE)
        );
    }

    /**
     * Heuristically finds the version number. Current implementation is the identity function since
     * cabal plays nice.
     */
    public static String getVersion(final String cmd) {
        if (cmd == null || cmd.isEmpty() || !Files.isExecutable(Paths.get(cmd))) {
            LOG.warn(String.format("unable to get version info for path: '%s'", cmd));
            return "";
        }
        final GeneralCommandLine commandLine = new GeneralCommandLine(cmd, "--version");

        final Future<String> future = ApplicationManager
            .getApplication()
            .executeOnPooledThread(() -> new CapturingProcessHandler(
                commandLine.createProcess(),
                commandLine.getCharset(),
                commandLine.getCommandLineString()
            ).runProcess().getStdout());

        try {
            final @Nullable String versionOutput = future.get(500, TimeUnit.MILLISECONDS);

            if (StringUtil.isNotEmpty(versionOutput)) {
                final String version = versionOutput.split("\n")[0].trim();
                LOG.debug(String.format("%s [%s]", cmd, version));
                return version;
            }
        } catch (final InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
            LOG.error("Could not run: " + commandLine.getCommandLineString(), e);
        }
        return "";
    }

    @NotNull
    @Override
    public String getId() {
        return D_TOOLS_ID;
    }

    @Nullable
    @Override
    public Runnable enableSearch(final String option) {
        return () -> {
            if(StringUtil.isNotEmpty(option)) {
                final int tabCount = tabbedPane1.getTabCount();

                for (int i = 0; i < tabCount-1; i++) {
                    final String title = tabbedPane1.getTitleAt(i);
                    if(title.toLowerCase().contains(option.toLowerCase())) {
                        tabbedPane1.setSelectedIndex(i);
                    }
                }
            }

        };
    }

    @Nls
    @Override
    public String getDisplayName() {
        return D_TOOLS_ID;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return mainPanel;
    }

    /**
     * Enables the apply button if anything changed.
     */
    @Override
    public boolean isModified() {
        for (final Tool t : properties) {
            if (t.isModified()) {
                t.setDirty(true);

                final String exePath = StringUtil.trim(t.pathField.getText());

                if(StringUtil.isNotEmpty(exePath)) {
                    final Path path = Paths.get(exePath);

                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        t.versionField.setText("Path does not exist");
                        t.versionField.setDisabledTextColor(UIManager.getColor("Focus.color"));
                    } else if(!Files.isExecutable(path)) {
                        t.versionField.setText("Path is not executable");
                        t.versionField.setDisabledTextColor(UIManager.getColor("Focus.color"));
                    } else if(!StringUtil.containsIgnoreCase(exePath, t.command)) {
                        t.versionField.setText(String.format("Not a valid %s binary", t.command));
                        t.versionField.setDisabledTextColor(UIManager.getColor("Focus.color"));
                    } else {
                        // then it's a valid path to the tool
                        t.updateVersion();
                        return true;
                    }
                } else {
                    // allow it to be set to a blank value
                    t.updateVersion();
                    return true;
                }
            } else if (t.isDirty()) {
                t.updateVersion();
                t.setDirty(false);
            }
        }
        return propertiesComponent.getBoolean("USE_NATIVE_CODE_COMPLETION") != chkNativeCodeCompletion
            .isSelected();
    }

    /**
     * Triggered when the user pushes the apply button.
     */
    @Override
    public void apply() {
        updateVersionInfoFields();
        saveState();
    }

    /**
     * Triggered when the user pushes the cancel button.
     */
    @Override
    public void reset() {
        restoreState();
    }

    @Override
    public void disposeUIResources() {

    }

    /**
     * Updates the version info fields for all files configured.
     */
    private void updateVersionInfoFields() {
        properties.forEach(Tool::updateVersion);
    }

    /**
     * Persistent save of the current state.
     */
    private void saveState() {
        LOG.info("Saving D Tools Config");
        for (final Property property : properties) {
            property.saveState();
        }
        propertiesComponent
            .setValue("USE_NATIVE_CODE_COMPLETION", chkNativeCodeCompletion.isSelected());
    }

    /**
     * Restore components to the initial state.
     */
    private void restoreState() {
        LOG.info("Restore D Tools Config");
        for (final Property property : properties) {
            property.restoreState();
        }
        if (!propertiesComponent.isValueSet("USE_NATIVE_CODE_COMPLETION")) {
            propertiesComponent.setValue("USE_NATIVE_CODE_COMPLETION", false);
        }
        chkNativeCodeCompletion
            .setSelected(propertiesComponent.getBoolean("USE_NATIVE_CODE_COMPLETION"));
    }

    interface Property {

        boolean isModified();

        void saveState();

        void restoreState();
    }

    interface Versioned {

        void updateVersion();
    }

    /**
     * Manages the state of a PropertyComponent and its respective field.
     */
    class PropertyField implements Property {

        public final TextAccessor field;
        private final String propertyKey;
        private String oldValue;

        PropertyField(@NotNull final String propertyKey, @NotNull final TextAccessor field) {
            this(propertyKey, field, "");
        }

        PropertyField(@NotNull final String propertyKey, @NotNull final TextAccessor field,
            @NotNull final String defaultValue) {
            this.propertyKey = propertyKey;
            this.field = field;
            this.oldValue = propertiesComponent.getValue(propertyKey, defaultValue);
            field.setText(oldValue);
        }

        public boolean isModified() {
            return !field.getText().equals(oldValue);
        }

        public void saveState() {
            propertiesComponent.setValue(propertyKey, oldValue = field.getText());
        }

        public void restoreState() {
            field.setText(oldValue);
        }
    }

    /**
     * Ensures that the UI component for selecting a Dub Tool can only be used to select the correct binary
     */
    private class DubToolBinaryChooserDescriptor extends FileChooserDescriptor {
        DubToolBinaryChooserDescriptor(@NotNull final String binaryName) {
            super(true, false, false, false, false, false);
            withFileFilter(vf -> vf.getNameWithoutExtension().equalsIgnoreCase(binaryName));
        }
    }

    /**
     * Manages the group of fields which reside to a particular tool.
     */
    class Tool implements Property, Versioned {

        public final Project project;
        public final String command;
        public final ToolKey key;
        final TextFieldWithBrowseButton pathField;
        final RawCommandLineEditor flagsField;
        final JTextField versionField;
        final String latestVersion;
        final JButton autoFindButton;
        final List<PropertyField> propertyFields;
        final Topic<ToolChangeListener> topic;
        private final ToolChangeListener publisher;
        private boolean dirty = false;

        Tool(final Project project, final String command, final ToolKey key,
             final TextFieldWithBrowseButton pathField,
             final RawCommandLineEditor flagsField, final JButton autoFindButton,
             final JTextField versionField,
             final String latestVersion,
             final Topic<ToolChangeListener> topic) {
            this.project = project;
            this.command = command;
            this.key = key;
            this.pathField = pathField;
            this.flagsField = flagsField;
            this.versionField = versionField;
            this.latestVersion = latestVersion;
            this.autoFindButton = autoFindButton;
            this.topic = topic;
            this.publisher = topic == null ? null : project.getMessageBus().syncPublisher(topic);

            this.propertyFields = Arrays.asList(
                new PropertyField(key.getPathKey(), pathField),
                new PropertyField(key.getFlagsKey(), flagsField));

            pathField.addBrowseFolderListener(
                String.format("Select %s executable", command),
                "",
                null,
                new DubToolBinaryChooserDescriptor(command)
            );

            autoFindButton.addActionListener(new LocateToolListener(pathField, command));

            updateVersion();
        }

        public boolean isDirty() {
            return dirty;
        }

        void setDirty(final boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public void updateVersion() {
            @Nullable final String pathText = StringUtil.trim(pathField.getText());
            final String version = StringUtil.isEmpty(pathText) ? "" : getVersion(pathText);
            versionField.setText(version);

            if(DtoolUtils.versionPredates(version, this.latestVersion)) {
                versionField.setToolTipText(String.format("A newer version of %s is available", this.command));
                versionField.setDisabledTextColor(UIManager.getColor("Focus.color"));
            } else {
                versionField.setDisabledTextColor(UIManager.getColor("ComboBox.disabledForeground"));
                versionField.setToolTipText(null); // turns the tool tip off
            }
        }

        @Override
        public boolean isModified() {
            return propertyFields.parallelStream().anyMatch(PropertyField::isModified);
        }

        @Override
        public void saveState() {
            if (isModified() && publisher != null) {
                @Nullable final String pathText = StringUtil.trim(pathField.getText());
                publisher.onToolSettingsChanged(new ToolSettings(pathText, flagsField.getText()));
            }
            for (final PropertyField propertyField : propertyFields) {
                propertyField.saveState();
            }
        }

        @Override
        public void restoreState() {
            for (final PropertyField propertyField : propertyFields) {
                propertyField.restoreState();
            }
        }
    }

    private static class LocateToolListener implements ActionListener {
        private final TextFieldWithBrowseButton pathField;
        private final String command;

        public LocateToolListener(@NonNls final TextFieldWithBrowseButton pathField, @NonNls final String command) {
            this.pathField = pathField;
            this.command = command;
        }

        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            @Nullable final String path = this.lookInStandardDirectories().orElseGet(this::locateViaCommandline);

            if (StringUtil.isNotEmpty(path)) {
                pathField.setText(StringUtil.trim(path));
            } else {
                Messages.showErrorDialog(String.format("Could not find '%s'.", command), "DLanguage");
            }
        }

        /**
         * Tries to get the absolute path for a command by trying standard installation directories (for dub).
         * This is mostly useful for dub, especially on Mac/Linux where various installation methods can differ
         */
        private Optional<String> lookInStandardDirectories() {
            for (final Path path : STANDARD_TOOL_PATHS) {
                LOG.info(String.format("Looking for %s in %s", command, path.toString()));
                //noinspection ObjectAllocationInLoop
                final Path toolPath = path.resolve(command);
                if (Files.exists(toolPath) && Files.isExecutable(toolPath)) {
                    return Optional.of(toolPath.toAbsolutePath().toString());
                }
            }
            return Optional.empty();
        }

        private static final Path[] STANDARD_TOOL_PATHS;

        static {
            if (SystemInfo.isWindows) {
                STANDARD_TOOL_PATHS = new Path[] {
                    Paths.get("\\D\\dmd2\\windows\\bin")
                };
            } else if (SystemInfo.isMac) {
                STANDARD_TOOL_PATHS = new Path[] {
                    Paths.get("/usr/local/opt") // homebrew
                };
            } else if (SystemInfo.isUnix) {
                STANDARD_TOOL_PATHS = new Path[] {
                    Paths.get("/usr/local/bin"),
                    Paths.get("/usr/bin"),
                    Paths.get("/snap/bin"), // #575 support snaps
                    Paths.get(System.getProperty("user.home") + "/bin")
                };
            } else {
                LOG.warn(String.format("D language plugin does not support %s", SystemInfo.getOsNameAndVersion()));
                STANDARD_TOOL_PATHS = new Path[]{};
            }
        }

        /**
         * Attempt to find the D Tool by looking on the PATH
         *
         * @return either the found tool path or null
         */
        @Nullable
        private String locateViaCommandline() {
            final GeneralCommandLine cmd = new GeneralCommandLine()
                .withExePath(SystemInfo.isWindows ? "cmd" : "/bin/sh")
                .withParameters(
                    SystemInfo.isWindows ? "/c" : "-c",
                    SystemInfo.isWindows ? "where" : "which",
                    command)
                ;

            try {
                final String path = ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            return new CapturingProcessHandler(
                                cmd.createProcess(),
                                cmd.getCharset(),
                                cmd.getCommandLineString()
                            )
                                .runProcess()
                                .getStdout();
                        } catch (final ExecutionException e) {
                            LOG.warn(String.format("Failed to run '%s'.", command), e);
                        }
                        return null;
                    })
                    .get(500, TimeUnit.MILLISECONDS);

                if (path != null && SystemInfo.isWindows && path.contains("C:\\")) {
                    // not sure if this is actually needed. Was moved over from ExecUtil
                    final String[] split = path.split("(?=C:\\\\)");
                    LOG.info("Multiple paths found for " + command);
                    return split[0]; // if there are multiple results default to first one.
                }
                return path;
            } catch (final InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
                LOG.warn(String.format("Failed to run '%s'.", command), e);
            }
            return null;
        }
    }
}
