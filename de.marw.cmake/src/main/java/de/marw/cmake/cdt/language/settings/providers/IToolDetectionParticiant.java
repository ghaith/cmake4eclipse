/*******************************************************************************
 * Copyright (c) 2019 Martin Weber.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *      Martin Weber - Initial implementation
 *******************************************************************************/

package de.marw.cmake.cdt.language.settings.providers;

/**
 * Responsible to match the first argument (the tool command) of a command-line and to provide a parser for the
 * tool-specific command-line arguments.
 *
 * @author Martin Weber
 */
public interface IToolDetectionParticiant {

  /**
   * Gets the for the parser for the tool-specific command-line arguments.
   *
   * @return the parser, never {@code null}
   */
  IToolCommandlineParser getParser();

  /**
   * Gets whether this object can handle NTFS file system paths in the compiler argument in addition to a Linux path
   * (which has forward slashes to separate path name components). If {@code true} the detection logic will also try to
   * match path name with backslashes and will try to expand windows short paths like <code>C:/pr~1/aa~1.exe</code>.
   */
  boolean canHandleNtfsPaths();

  /**
   * Gets, whether the parser for the tool arguments can properly parse the specified command-line string. If so, the
   * remaining arguments of the command-line are returned.
   * @param commandLine
   *          the command line to match
   * @param matchBackslash
   *          whether to match on file system paths with backslashes in the compiler argument or to match an paths
   *          with forward slashes
   * @return {@code null} if the matcher did not match the tool name in the command-line string. Otherwise, if the
   *         tool name matches, a MatchResult holding the de-composed command-line is returned.
   */
  MatchResult basenameMatches(String commandLine, boolean matchBackslash);

  /**
   * Gets, whether the parser for the tool arguments can properly parse the specified command-line string. If so, the
   * remaining arguments of the command-line are returned. This is time-consuming, since it creates a Matcher object
   * on each invocation.
   *
   * @param commandLine
   *          the command-line to match
   * @param matchBackslash
   *          whether to match on file system paths with backslashes in the compiler argument or to match an paths
   *          with forward slashes
   * @param versionRegex
   *          a regular expression that matches the version string in the name of the tool to detect.
   * @return {@code null} if the matcher did not match the tool name in the command-line string. Otherwise, if the
   *         tool name matches, a MatchResult holding the de-composed command-line is returned.
   */
  MatchResult basenameWithVersionMatches(String commandLine, boolean matchBackslash,
      String versionRegex);

  /**
   * Gets, whether the parser for the tool arguments can properly parse the specified command-line string. If so, the
   * remaining arguments of the command-line are returned.
   * @param commandLine
   *          the command-line to match
   * @param matchBackslash
   *          whether to match on file system paths with backslashes in the compiler argument or to match an paths
   *          with forward slashes
   * @return {@code null} if the matcher did not match the tool name in the command-line string. Otherwise, if the
   *         tool name matches, a MatchResult holding the de-composed command-line is returned.
   */
  MatchResult basenameWithExtensionMatches(String commandLine, boolean matchBackslash);

  /**
   * Gets, whether the parser for the tool arguments can properly parse the specified command-line string. If so, the
   * remaining arguments of the command-line are returned. This is time-consuming, since it creates a Matcher object
   * on each invocation.
   *
   * @param commandLine
   *          the command-line to match
   * @param matchBackslash
   *          whether to match on file system paths with backslashes in the compiler argument or to match an paths
   *          with forward slashes
   * @param versionRegex
   *          a regular expression that matches the version string in the name of the tool to detect.
   * @return {@code null} if the matcher did not match the tool name in the command-line string. Otherwise, if the
   *         tool name matches, a MatchResult holding the de-composed command-line is returned.
   */
  MatchResult basenameWithVersionAndExtensionMatches(String commandLine, boolean matchBackslash,
      String versionRegex);

  /** The result of matching a command-line string.
   */
  public static class MatchResult {
    private final String command;
    private final String arguments;

    /**
     * @param command
     *          the command from the command-line, without the argument string. . If the command contains space
     *          characters, the surrounding quotes must have been removed,
     * @param arguments
     *          the remaining arguments from the command-line, without the command
     */
    public MatchResult(String command, String arguments) {
      this.command = command;
      this.arguments = arguments;
    }

    /**
     * Gets the command from the command-line, without the argument string. If the command contains space characters,
     * the surrounding quotes have been removed,
     */
    public String getCommand() {
      return this.command;
    }

    /**
     * Gets the remaining arguments from the command-line, without the command.
     */
    public String getArguments() {
      return this.arguments;
    }
  } // MatchResult
}