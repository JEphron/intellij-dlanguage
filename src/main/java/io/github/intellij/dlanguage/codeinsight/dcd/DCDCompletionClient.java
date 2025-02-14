package io.github.intellij.dlanguage.codeinsight.dcd;

import com.google.common.collect.Maps;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import io.github.intellij.dlanguage.codeinsight.dcd.completions.TextCompletion;
import io.github.intellij.dlanguage.settings.ToolKey;
import io.github.intellij.dlanguage.utils.DUtil;
import io.github.intellij.dlanguage.codeinsight.dcd.completions.Completion;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DCDCompletionClient {

    private final static Logger LOG = Logger.getInstance(DCDCompletionClient.class);
    private static final Map<String, String> completionTypeMap = getCompletionTypeMap();

    public List<Completion> autoComplete(final int position, final PsiFile file, final String fileContent) throws DCDError {
        final String path = lookupPath();
        if (StringUtil.isEmptyOrSpaces(path)) {
            LOG.debug("Attempted auto completion via DCD but path was blank");
            return Collections.emptyList();
        }

        final File dcdPath = new File(path);
        if(!dcdPath.canExecute()) {
            LOG.warn(String.format("Attempted auto completion via DCD but path '%s' not executable", path));
            return Collections.emptyList();
        }

        final GeneralCommandLine dcdClientCommandLine = this.buildDcdCommand(path, position, file);

        final Process process;
        try {
            process = dcdClientCommandLine.createProcess();
        } catch (ExecutionException e) {
            throw new DCDError(e);
        }

        try (final BufferedWriter output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            output.write(fileContent);
        } catch (IOException e) {
            throw new DCDError(e);
        }

        try {
            final String result = this.readCommandLine(dcdClientCommandLine, file.getText()).get(3L, TimeUnit.SECONDS);
            return processDcdOutput(result);
        } catch (InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
            throw new DCDError(e);
        }
    }

    // is package private for unit testing
    GeneralCommandLine buildDcdCommand(final String dcdClientPath, int position, PsiFile file) {
        final String workingDirectory = file.getProject().getBasePath();

        final GeneralCommandLine commandLine = new GeneralCommandLine()
            .withWorkDirectory(workingDirectory)
            .withExePath(dcdClientPath)
            .withParameters("-c", String.valueOf(position));

        final String flags = ToolKey.DCD_CLIENT_KEY.getFlags();

        if (DUtil.isNotNullOrEmpty(flags)) {
            Arrays.stream(flags.split(","))
                .forEach(i -> commandLine.addParameters("-I", i));
        }
        return commandLine;
    }

    /**
     * Executes commandLine, optionally piping input to stdin, and return stdout.
     * This method was taken from {@link io.github.intellij.dlanguage.utils.ExecUtil} as it's likely going to be deleted
     */
    private Future<String> readCommandLine(@NotNull final GeneralCommandLine commandLine, @Nullable final String input) {
        return ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final Process process = commandLine.createProcess();

            if (input != null) {
                try (final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    writer.write(input);
                    writer.flush();
                }
            }

            return new CapturingProcessHandler(process,
                Charset.defaultCharset(),
                commandLine.getCommandLineString()
            ).runProcess().getStdout();
        });
    }

    List<Completion> processDcdOutput(@NotNull final String output) {
        if (!output.isEmpty()) {
            final String[] lines = output.split("\\n");

            if (lines.length > 1 && lines[0].contains("identifiers")) {
                final List<Completion> completions = new ArrayList<>();
                for (int i = 1; i < lines.length; i++) {
                    final String line = lines[i];

                    final String[] tokens = line.split("\\s");
                    final String completionType = getCompletionType(tokens);
                    final String completionText = getCompletionText(tokens);
                    completions.add(new TextCompletion(completionType, completionText));
                }
                return completions;
            }
//            else if (lines[0].contains("calltips")) {
//                //TODO - this goes in a Parameter Info handler (ctrl+p) instead of here - see: ShowParameterInfoHandler.register
//                System.out.println(tokens);
//            }
        }

        return Collections.emptyList();
    }

    @Nullable
    private static String lookupPath() {
        return ToolKey.DCD_CLIENT_KEY.getPath();
    }

    private String getType(final String[] parts) {
        final String type = parts[parts.length - 1];
        return type.isEmpty() ? "U" : type.trim();
    }

    private String getCompletionType(final String[] parts) {
        final String mapping = completionTypeMap.get(getType(parts));
        return mapping == null ? "Unknown" : mapping;
    }

    private String getCompletionText(final String[] parts) {
        final String text = parts[0];
        final String result = text.isEmpty() ? "" : text.trim();
        final String type = getType(parts);
        if (type.equals("f")) {
            return result + "()";
        }
        return result;
    }

    private static Map<String, String> getCompletionTypeMap() {
        final Map<String, String> map = Maps.newTreeMap();
        map.put("c", "Class");
        map.put("i", "Interface");
        map.put("s", "Struct");
        map.put("u", "Union");
        map.put("v", "Variable");
        map.put("m", "Variable"); // Member Variable
        map.put("k", "Keyword");
        map.put("f", "Function");
        map.put("g", "Enum"); // enum name
        map.put("e", "Enum"); // enum member
        map.put("P", "Package");
        map.put("M", "Module");
        map.put("a", "Array");
        map.put("A", "Map"); // associative array
        map.put("l", "Alias");
        map.put("t", "Template");
        map.put("T", "Mixin");
        map.put("h", "Type Param"); // template type parameter (when no colon constraint)
        map.put("p", "Variadic Param"); // template variadic parameter
        map.put("U", "Unknown");
        return map;
    }

    /**
     * Kills the existing process and closes input and output if they exist.
     */
//    private synchronized void kill() {
//        if (process != null) process.destroy();
//        process = null;
//        try {
//            if (output != null) output.close();
//        } catch (final IOException e) { /* Ignored */ }
//        output = null;
//    }


    public static class DCDError extends Exception {
        DCDError(Throwable throwable) {
            super(throwable);
        }
    }
}
