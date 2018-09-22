/*******************************************************************************
 * Copyright (c) 2016-2018 Martin Weber.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Martin Weber - Initial implementation
 *******************************************************************************/
package de.marw.cmake.cdt.language.settings.providers;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.cdt.build.core.scannerconfig.ScannerConfigNature;
import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.language.settings.providers.ICBuildOutputParser;
import org.eclipse.cdt.core.language.settings.providers.ICListenerAgent;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsEditableProvider;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvider;
import org.eclipse.cdt.core.language.settings.providers.ILanguageSettingsProvidersKeeper;
import org.eclipse.cdt.core.language.settings.providers.IWorkingDirectoryTracker;
import org.eclipse.cdt.core.language.settings.providers.LanguageSettingsSerializableProvider;
import org.eclipse.cdt.core.language.settings.providers.LanguageSettingsStorage;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICLanguageSettingEntry;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.ICSettingEntry;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jetty.util.ajax.JSON;
import org.w3c.dom.Element;

import de.marw.cmake.CMakePlugin;
import de.marw.cmake.cdt.language.settings.providers.ParserDetection.MarchResult;
import de.marw.cmake.cdt.language.settings.providers.builtins.CompilerBuiltinsDetector;

/**
 * A ILanguageSettingsProvider that parses the file 'compile_commands.json' produced by cmake when option
 * {@code -DCMAKE_EXPORT_COMPILE_COMMANDS=ON} is given.<br>
 * NOTE: This class misuses interface ICBuildOutputParser to detect when a build did finish.<br>
 * NOTE: This class misuses interface ICListenerAgent to populate the {@link #getSettingEntries setting entries} on
 * workbench startup.
 *
 * @author Martin Weber
 */
public class CompileCommandsJsonParser extends LanguageSettingsSerializableProvider
    implements ILanguageSettingsEditableProvider, ICListenerAgent, ICBuildOutputParser, Cloneable {
  /**
   *
   */
  static final String PROVIDER_ID = "de.marw.cmake.cdt.language.settings.providers.CompileCommandsJsonParser";

  private static final ILog log = CMakePlugin.getDefault().getLog();

  /**
   * default regex string used for version pattern matching.
   *
   * @see #isVersionPatternEnabled()
   */
  private static final String DEFALT_VERSION_PATTERN = "-?\\d+(\\.\\d+)*";
  /** storage key for version pattern */
  private static final String ATTR_PATTERN = "vPattern";
  /** storage key for version pattern enabled */
  private static final String ATTR_PATTERN_ENABLED = "vPatternEnabled";

  private static final String WORKBENCH_WILL_NOT_KNOW_ALL_MSG = "Your workbench will not know all include paths and preprocessor defines.";

  private static final String MARKER_ID = CMakePlugin.PLUGIN_ID + ".CompileCommandsJsonParserMarker";

  /**
   * Storage to keep settings entries
   */
  private PerConfigLanguageSettingsStorage storage = new PerConfigLanguageSettingsStorage();

  private ICConfigurationDescription currentCfgDescription;

  /**
   * last known working tool detector and its tool option parsers or {@code null}, if unknown (to speed up parsing)
   */
  private ParserDetection.DetectorWithMethod lastDetector;

  public CompileCommandsJsonParser() {
  }

  /**
   * Gets whether the parser will also try to detect compiler command that have a trailing version-string in their name.
   * If enabled, this parser will also try to match for example {@code gcc-4.6} or {@code gcc-4.6.exe} if none of the
   * other patterns matched.
   *
   * @return {@code true} version pattern matching in command names is enabled, otherwise {@code false}
   */
  public boolean isVersionPatternEnabled() {
    return getPropertyBool(ATTR_PATTERN_ENABLED);
  }

  /**
   * Sets whether version pattern matching is performed.
   *
   * @see #isVersionPatternEnabled()
   */
  public void setVersionPatternEnabled(boolean enabled) {
    setPropertyBool(ATTR_PATTERN_ENABLED, enabled);
  }

  /**
   * Gets the regex pattern string used for version pattern matching.
   *
   * @see #isVersionPatternEnabled()
   */
  public String getVersionPattern() {
    String val = properties.get(ATTR_PATTERN);
    if (val == null || val.isEmpty()) {
      // provide a default pattern
      val = DEFALT_VERSION_PATTERN;
    }
    return val;
  }

  /**
   * Sets the regex pattern string used for version pattern matching.
   *
   * @see #isVersionPatternEnabled()
   */
  public void setVersionPattern(String versionPattern) {
    if (versionPattern == null || versionPattern.isEmpty() || versionPattern.equals(DEFALT_VERSION_PATTERN)) {
      // do not store default pattern
      properties.remove(ATTR_PATTERN);
    } else {
      setProperty(ATTR_PATTERN, versionPattern);
    }
  }

  @Override
  public List<ICLanguageSettingEntry> getSettingEntries(ICConfigurationDescription cfgDescription, IResource rc,
      String languageId) {
    if (cfgDescription == null || rc == null) {
      // speed up, we do not provide global (workspace) lang settings..
      return null;
    }
    TimestampedLanguageSettingsStorage store = storage.getSettingsStoreForConfig(cfgDescription);
    String rcPath = null;
    if (rc.getType() == IResource.FILE) {
      rcPath = rc.getProjectRelativePath().toString();
    }
    return store.getSettingEntries(rcPath, languageId);
  }

  /**
   * Parses the content of the 'compile_commands.json' file corresponding to the specified configuration, if timestamps
   * differ.
   *
   * @param enabled
   *          {@code true} if this provider is present in the project's list of settings providers, otherwise false. If
   *          {@code false}, this method will just determine the compiler-built-in processors and not perform any
   *          command line parsing
   * @param initializingWorkbench
   *          {@code true} if the workbench is starting up. If {@code true}, this method will not trigger UI update to
   *          show newly detected include paths nor will it complain if a "compile_commands.json" file does not exist.
   *
   * @return {@code true} if the json file did change since the last invocation of this method (new setting entires were
   *         discoverd), ohterwise {@code false}
   * @throws CoreException
   */
  private boolean tryParseJson(boolean enabled, boolean initializingWorkbench) throws CoreException {

    // If getBuilderCWD() returns a workspace relative path, it is garbled.
    // It returns '${workspace_loc:/my-project-name}'. Additionally, it returns
    // null on a project with makeNature.
    // In contrast, getResolvedOutputDirectories() does it mostly right, it
    // returns '/my-project-name', but also stale data
    // when a user changed the build-root
    final IPath buildRoot = currentCfgDescription.getBuildSetting().getBuilderCWD();
    final IPath jsonPath = buildRoot.append("compile_commands.json");
    final IFile jsonFileRc = ResourcesPlugin.getWorkspace().getRoot().getFile(jsonPath);

    final IPath location = jsonFileRc.getLocation();
    if (location != null) {
      final File jsonFile = location.toFile();
      if (!jsonFile.exists()) {
        // no json file was produced in the build
        final String msg = "File '" + jsonPath + "' was not created in the build. " + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
        createMarker(jsonFileRc, msg);
        return false;
      }
      // file exists on disk...
      final long tsJsonModified = jsonFile.lastModified();

      final IProject project = currentCfgDescription.getProjectDescription().getProject();
      final TimestampedLanguageSettingsStorage store = storage.getSettingsStoreForConfig(currentCfgDescription);

      if (store.lastModified < tsJsonModified) {
        // must parse json file...
        store.clear();
        // store time-stamp
        store.lastModified = tsJsonModified;

        if (!initializingWorkbench) {
          project.deleteMarkers(MARKER_ID, false, IResource.DEPTH_INFINITE);
        }
        Reader in = null;
        try {
          // parse file...
          in = new FileReader(jsonFile);
          Object parsed = new JSON().parse(new JSON.ReaderSource(in), false);
          if (parsed instanceof Object[]) {
            for (Object o : (Object[]) parsed) {
              if (o instanceof Map) {
                processJsonEntry(store, enabled, (Map<?, ?>) o, jsonFileRc);
              } else {
                // expected Map object, skipping entry.toString()
                final String msg = "File format error: unexpected entry '" + o + "'. "
                    + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
                createMarker(jsonFileRc, msg);
              }
            }

            // re-index to reflect new paths and macros in editor views
            // serializeLanguageSettings(currentCfgDescription);
            if (!initializingWorkbench) {
              final ICElement[] tuSelection = { CoreModel.getDefault().create(project) };
              CCorePlugin.getIndexManager().update(tuSelection, IIndexManager.UPDATE_ALL);
            }
            // triggering UI update to show newly detected include paths in
            // Includes folder is USELESS. It looks like ICProject#getIncludeReferences() is only
            // updated when the project is opened or the user clicks 'Apply' in the
            // Preprocessor Include Paths page.
          } else {
            // file format error
            final String msg = "File does not seem to be in JSON format. " + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
            createMarker(jsonFileRc, msg);
          }
        } catch (IOException ex) {
          final String msg = "Failed to read file " + jsonFile + ". " + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
          createMarker(jsonFileRc, msg);
        } finally {
          if (in != null)
            try {
              in.close();
            } catch (IOException ignore) {
            }
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Processes an entry from a {@code compile_commands.json} file and stores a {@link ICLanguageSettingEntry} for the
   * file given the specified map.
   *
   * @param storage
   *          where to store language settings
   * @param enabled
   *          {@code true} if this provider is present in the project's list of settings providers, otherwise false. If
   *          {@code false}, this method will just determine the compiler-built-in processors and not perform any
   *          command line parsing
   * @param sourceFileInfo
   *          a Map of type Map<String,String>
   * @param jsonFile
   *          the JSON file being parsed (for marker creation only)
   * @throws CoreException
   *           if marker creation failed
   */
  private void processJsonEntry(TimestampedLanguageSettingsStorage storage, boolean enabled, Map<?, ?> sourceFileInfo,
      IFile jsonFile) throws CoreException {

    if (sourceFileInfo.containsKey("file") && sourceFileInfo.containsKey("command")
        && sourceFileInfo.containsKey("directory")) {
      final String file = sourceFileInfo.get("file").toString();
      if (file != null && !file.isEmpty()) {
        final String cmdLine = sourceFileInfo.get("command").toString();
        if (cmdLine != null && !cmdLine.isEmpty()) {
          final IFile[] files = ResourcesPlugin.getWorkspace().getRoot()
              .findFilesForLocationURI(new File(file).toURI());
          if (files.length > 0) {
            ParserDetection.ParserDetectionResult pdr = fastDetermineDetector(cmdLine);
            if (pdr != null) {
              // found a matching command-line parser
              final IToolCommandlineParser parser = pdr.getDetectorWithMethod().getDetector().getParser();
              // cwdStr is the absolute working directory of the compiler in
              // CMake-notation (fileSep are forward slashes)
              final String cwdStr = sourceFileInfo.get("directory").toString();
              IPath cwd = cwdStr != null ? Path.fromOSString(cwdStr) : new Path("");
              if (enabled)
                processCommandLine(storage, parser, files[0], cwd, pdr.getReducedCommandLine());

              CompilerBuiltinsDetector detector = new CompilerBuiltinsDetector(currentCfgDescription,
                  parser.getLanguageId(), parser.getBuiltinDetectionType(), pdr.getCommandLine().getCommand());

              storage.addBuiltinsDetector(detector);
            } else {
              // no matching parser found
              String message = "No parser for command '" + cmdLine + "'. " + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
              createMarker(jsonFile, message);
            }
          }
          return;
        }
      }
    }
    // unrecognized entry, skipping
    final String msg = "File format error: " + ": 'file', 'command' or 'directory' missing in JSON object. "
        + WORKBENCH_WILL_NOT_KNOW_ALL_MSG;
    createMarker(jsonFile, msg);
  }

  /**
   * Determines the detectors for compiler built-in include paths and symbols. Parses the json file, if necessary and
   * caches the findings.
   *
   * @param cfgDescription
   *          configuration description
   * @param enabled
   *          {@code true} if this provider is present in the project's list of settings providers, otherwise false. If
   *          {@code false}, this method will just determine the compiler-built-in processors and not perform any
   *          command line parsing
   * @param initializingWorkbench
   *          {@code true} if the workbench is starting up. If {@code true}, this method will not trigger UI update to
   *          show newly detected include paths nor will it complain if a "compile_commands.json" file does not exist.
   * @return the detectors to run or {@code null} if the json file did not change since the last invocation of this
   *         method
   * @throws CoreException
   */
  /* package */ Iterable<CompilerBuiltinsDetector> determineBuiltinDetectors(ICConfigurationDescription cfgDescription,
      boolean enabled, boolean initializingWorkbench) throws CoreException {
    currentCfgDescription = Objects.requireNonNull(cfgDescription, "cfgDescription");
    if (tryParseJson(enabled, initializingWorkbench))
      return storage.getSettingsStoreForConfig(cfgDescription).getBuiltinsDetectors();
    return null;
  }

  /**
   * Unconditionally gets the Determined detectors for compiler built-in include paths and symbols.
   *
   * @param cfgDescription
   *          configuration description
   * @return the detectors to run or {@code null} if {@link #determineBuiltinDetectors} has not been invoked prior
   */
  /* package */ Iterable<CompilerBuiltinsDetector> getBuiltinDetectors(ICConfigurationDescription cfgDescription) {
    return storage.getSettingsStoreForConfig(cfgDescription).getBuiltinsDetectors();
  }

  private static void createMarker(IFile file, String message) throws CoreException {
    IMarker marker;
    try {
      marker = file.createMarker(MARKER_ID);
    } catch (CoreException ex) {
      // resource is not (yet) known by the workbench
      file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
      try {
        marker = file.createMarker(MARKER_ID);
      } catch (CoreException ex2) {
        // resource is not known by the workbench, use project instead of file
        marker = file.getProject().createMarker(MARKER_ID);
      }
    }
    marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
    marker.setAttribute(IMarker.MESSAGE, message);
    marker.setAttribute(IMarker.LOCATION, CompileCommandsJsonParser.class.getName());
  }

  /**
   * Determines the parser detector that can parse the specified command-line.<br>
   * Tries to be fast: That is, it tries the last known working detector first and will perform expensive detection
   * required under windows only if needed.
   *
   * @param line
   *          the command line to process
   *
   * @return {@code null} if none of the detectors matched the tool name in the specified command-line string.
   *         Otherwise, if the tool name matches, a {@code ParserDetectionResult} holding the de-composed command-line
   *         is returned.
   */
  private ParserDetection.ParserDetectionResult fastDetermineDetector(String line) {
    // try last known matching detector first...
    if (lastDetector != null) {
      MarchResult cmdline = null;
      final ParserDetection.ParserDetector detector = lastDetector.getDetector();
      switch (lastDetector.getHow()) {
      case BASENAME:
        cmdline = detector.basenameMatches(line);
        break;
      case WITH_EXTENSION:
        cmdline = ((ParserDetection.ParserDetectorExt) detector).basenameWithExtensionMatches(line);
        break;
      case WITH_VERSION:
        if (isVersionPatternEnabled()) {
          cmdline = detector.basenameWithVersionMatches(line, getVersionPattern());
        }
        break;
      case WITH_VERSION_EXTENSION:
        if (isVersionPatternEnabled()) {
          cmdline = ((ParserDetection.ParserDetectorExt) detector).basenameWithVersionAndExtensionMatches(line,
              getVersionPattern());
        }
        break;
      default:
        break;
      }
      if (cmdline != null) {
        return new ParserDetection.ParserDetectionResult(lastDetector, cmdline);
      } else {
        lastDetector = null; // invalidate last working detector
      }
    }

    // no working detector found, determine a new one...
    String versionPattern = isVersionPatternEnabled() ? getVersionPattern() : null;
    ParserDetection.ParserDetectionResult result = ParserDetection.determineDetector(line, versionPattern,
        File.separatorChar == '\\');
    if (result != null) {
      // cache last working detector
      lastDetector = result.getDetectorWithMethod();
    }
    return result;
  }

  /**
   * Processes the command-line of an entry from a {@code compile_commands.json} file by trying the specified detector
   * and stores a {@link ICLanguageSettingEntry} for the file found in the specified map.
   *
   * @param storage
   *          where to store language settings
   * @param cmdlineParser
   *          the tool detector and its tool option parsers
   * @param sourceFile
   *          the source file resource corresponding to the source file being processed by the tool
   * @param cwd
   *          the current working directory of the compiler at its invocation
   * @param line
   *          the command line to process
   */
  private void processCommandLine(TimestampedLanguageSettingsStorage storage, IToolCommandlineParser cmdlineParser,
      IFile sourceFile, IPath cwd, String line) {
    line = ToolCommandlineParser.trimLeadingWS(line);
    final List<ICLanguageSettingEntry> entries = cmdlineParser.processArgs(cwd, line);
    final String languageId = cmdlineParser.getLanguageId();
    if (entries != null && entries.size() > 0) {
      handleIncludePathEntries(storage, entries, languageId);
      // attach settings to sourceFile resource...
      storage.addSettingEntries(sourceFile, languageId, entries);
    }
  }

  /**
   * Handles {@code ICSettingEntry.INCLUDE_PATH} entries. These are added to the project resource to make them show up
   * in the UI in the includes folder and the CommandLauncherManager is told to respect them, when the build took place
   * in a docker container.
   *
   * @param storage
   * @param entries
   * @param languageId
   */
  private void handleIncludePathEntries(TimestampedLanguageSettingsStorage storage,
      final List<ICLanguageSettingEntry> entries, final String languageId) {
    /*
     * compile_commands.json holds entries per-file only and does not contain per-project or per-folder entries. For
     * include dirs, ALSO add these entries to the project resource to make them show up in the UI in the includes
     * folder...
     */
    Predicate<ICLanguageSettingEntry> isInclDir = e -> e.getKind() == ICSettingEntry.INCLUDE_PATH;
    List<ICLanguageSettingEntry> newEntries = entries.stream().filter(isInclDir).collect(Collectors.toList());

    List<ICLanguageSettingEntry> oldEntries = storage.getSettingEntries(null, languageId);
    // add new items only, maintain list order
    if (oldEntries != null) {
      // filter duplicates by using a Set...
      Set<ICLanguageSettingEntry> oldSet = new HashSet<>(oldEntries);
      newEntries = newEntries.stream().filter(e -> !oldSet.contains(e)).collect(Collectors.toList());
    }
    storage.addSettingEntries(null, languageId, newEntries);
  }

  /*
   * (non-Javadoc)
   *
   * @see
   * org.eclipse.cdt.core.language.settings.providers.LanguageSettingsSerializableProvider#serializeEntries(org.w3c.dom.
   * Element)
   */
  @Override
  public void serializeEntries(Element elementProvider) {
    // no language setting entries to serialize, since entries come from the compile_commands.json file
  }

  /*-
   * interface ICBuildOutputParser
   */
  @Override
  public void startup(ICConfigurationDescription cfgDescription, IWorkingDirectoryTracker cwdTracker)
      throws CoreException {
    currentCfgDescription = cfgDescription;
  }

  /**
   * Invoked for each line in the build output.
   */
  // interface ICBuildOutputParser
  @Override
  public boolean processLine(String line) {
    // nothing to do, we parse on shutdown...
    return false;
  }

  /*-
   * interface ICBuildOutputParser
   */
  @Override
  public void shutdown() {
    try {
      tryParseJson(true, false);
    } catch (CoreException ex) {
      log.log(new Status(IStatus.ERROR, CMakePlugin.PLUGIN_ID, "shutdown()", ex));
    }
    // release resources for garbage collector
    currentCfgDescription = null;
  }

  @Override
  public CompileCommandsJsonParser clone() throws CloneNotSupportedException {
    return (CompileCommandsJsonParser) super.clone();
  }

  @Override
  public CompileCommandsJsonParser cloneShallow() throws CloneNotSupportedException {
    return (CompileCommandsJsonParser) super.cloneShallow();
  }

  @Override
  public LanguageSettingsStorage copyStorage() {
    if (currentCfgDescription == null)
      return null;
    TimestampedLanguageSettingsStorage st = storage.getSettingsStoreForConfig(currentCfgDescription);
    return st.clone();
  }

  /**
   * Overridden to misuse this to populate the {@link #getSettingEntries setting entries} on startup.<br>
   * {@inheritDoc}
   */
  @Override
  public void registerListener(ICConfigurationDescription cfgDescription) {
    if (cfgDescription != null) {
      // per-project or null if the user just added this provider on the provider tab
      currentCfgDescription = cfgDescription;
      try {
        tryParseJson(true, true);
      } catch (CoreException ex) {
        log.log(new Status(IStatus.ERROR, CMakePlugin.PLUGIN_ID, "registerListener()", ex));
      }
    } else {
      // per workspace (to populate on startup)
      IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
      IProject[] projects = workspaceRoot.getProjects();
      CCorePlugin ccp = CCorePlugin.getDefault();
      // parse JSOn file for any opened project that has a ScannerConfigNature...
      for (IProject project : projects) {
        try {
          if (project.isOpen() && project.hasNature(ScannerConfigNature.NATURE_ID)) {
            ICProjectDescription projectDescription = ccp.getProjectDescription(project, false);
            if (projectDescription != null) {
              ICConfigurationDescription activeConfiguration = projectDescription.getActiveConfiguration();
              if (activeConfiguration instanceof ILanguageSettingsProvidersKeeper) {
                final List<ILanguageSettingsProvider> lsps = ((ILanguageSettingsProvidersKeeper) activeConfiguration)
                    .getLanguageSettingProviders();
                for (ILanguageSettingsProvider lsp : lsps) {
                  if (CompileCommandsJsonParser.PROVIDER_ID.equals(lsp.getId())) {
                    currentCfgDescription = activeConfiguration;
                    tryParseJson(true, true);
                    break;
                  }
                }
              }
            }
          }
        } catch (CoreException ex) {
          log.log(new Status(IStatus.ERROR, CMakePlugin.PLUGIN_ID, "registerListener()", ex));
        }
      }
    }
    // release resources for garbage collector
    currentCfgDescription = null;
  }

  /*-
   * @see org.eclipse.cdt.core.language.settings.providers.ICListenerAgent#unregisterListener()
   */
  @Override
  public void unregisterListener() {
  }

  ////////////////////////////////////////////////////////////////////
  // inner classes
  ////////////////////////////////////////////////////////////////////
  private static class TimestampedLanguageSettingsStorage extends LanguageSettingsStorage {
    /** cached file modification time-stamp of last parse */
    long lastModified = 0;

    private Set<CompilerBuiltinsDetector> builtinDetectors;

    /**
     * Adds the specified language settings entries for this storages.
     *
     * @param rc
     *          resource such as file or folder or project. If {@code null} the entries are considered to be being
     *          defined as project-level entries for child resources.
     * @param languageId
     *          language id. Must not be {@code null}
     * @param entries
     *          language settings entries to set.
     */
    private void addSettingEntries(IResource rc, String languageId, List<ICLanguageSettingEntry> entries) {
      if (entries.size() == 0)
        return;
      /*
       * compile_commands.json holds entries per-file only and does not contain per-project or per-folder entries. So we
       * map the latter as project entries (=> null) to make the UI show the include directories we detected.
       */
      String rcPath = null;
      if (rc != null && rc.getType() == IResource.FILE) {
        rcPath = rc.getProjectRelativePath().toString();
      }
      List<ICLanguageSettingEntry> sentries = super.getSettingEntries(rcPath, languageId);
      if (sentries != null) {
        // make list mutable
        List<ICLanguageSettingEntry> tmp = new ArrayList<>(sentries);
        tmp.addAll(entries);
        entries = tmp;
      }
      // also tells the CommandLauncherManager (since CDT 9.4) so it can translate paths from docker container
      super.setSettingEntries(rcPath, languageId, entries);
    }

    private void addBuiltinsDetector(CompilerBuiltinsDetector detector) {
      if (builtinDetectors == null)
        builtinDetectors = new HashSet<>(3, 1.0f);
      builtinDetectors.add(detector);
    }

    private Iterable<CompilerBuiltinsDetector> getBuiltinsDetectors() {
      return builtinDetectors == null ? builtinDetectors = Collections.emptySet()
          : Collections.unmodifiableCollection(builtinDetectors);
    }

    public TimestampedLanguageSettingsStorage clone() {
      TimestampedLanguageSettingsStorage cloned = new TimestampedLanguageSettingsStorage();
      cloned.lastModified = this.lastModified;
      cloned.fStorage.putAll(super.fStorage);
      return cloned;
    }

    @Override
    public void clear() {
      synchronized (fStorage) {
        super.clear();
        lastModified = 0;
      }
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = super.hashCode();
      result = prime * result + (int) (lastModified ^ (lastModified >>> 32));
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (!super.equals(obj))
        return false;
      if (getClass() != obj.getClass())
        return false;
      TimestampedLanguageSettingsStorage other = (TimestampedLanguageSettingsStorage) obj;
      if (lastModified != other.lastModified)
        return false;
      return true;
    }

  } // TimestampedLanguageSettingsStorage

  private static class PerConfigLanguageSettingsStorage implements Cloneable {

    /**
     * Storage to keep settings entries. Key is {@link ICConfigurationDescription#getId()}
     */
    private Map<String, TimestampedLanguageSettingsStorage> storages = new WeakHashMap<>();

    /**
     * Gets the settings storage for the specified configuration. Creates a new settings storage, if none exists.
     *
     * @return the storage, never {@code null}
     */
    private TimestampedLanguageSettingsStorage getSettingsStoreForConfig(ICConfigurationDescription cfgDescription) {
      TimestampedLanguageSettingsStorage store = storages.get(cfgDescription.getId());
      if (store == null) {
        store = new TimestampedLanguageSettingsStorage();
        storages.put(cfgDescription.getId(), store);
      }
      return store;
    }

  } // PerConfigLanguageSettingsStorage

}
