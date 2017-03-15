/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.interpreter.remote;

/**
 * Class to hold the various configuration needs of RemoteInterpreter
 */
public class RemoteInterpreterConfig
{

  public static final String CLOSE_TIMEOUT_KEY = "zeppelin.interpreter.closeTimeoutMillis";
  public static final String WAIT_BETWEEN_KILLS_KEY = "zeppelin.interpreter.waitBetweenKillsMillis";
  public static final String PARAGRAPH_MAX_OUTPUT_KEY = "zeppelin.paragraph.maxOutput";
  public static final String PARAGRAPH_OUTPUT_DIR_KEY = "zeppelin.paragraph.outputDir";

  private String noteId;
  private String className;
  private String interpreterRunner;
  private String interpreterPath;
  private String localRepoPath;
  private String paragraphOutputDir;
  private int connectTimeout;
  private int maxPoolSize;
  private int closeTimeoutMillis;
  private int waitBetweenKillsMillis;
  private int maxParagraphOutput;

  public RemoteInterpreterConfig()
  {

  }

  public String getNoteId()
  {
    return noteId;
  }

  public void setNoteId(final String noteId)
  {
    this.noteId = noteId;
  }

  public String getClassName()
  {
    return className;
  }

  public void setClassName(final String className)
  {
    this.className = className;
  }

  public String getInterpreterRunner()
  {
    return interpreterRunner;
  }

  public void setInterpreterRunner(final String interpreterRunner)
  {
    this.interpreterRunner = interpreterRunner;
  }

  public String getInterpreterPath()
  {
    return interpreterPath;
  }

  public void setInterpreterPath(final String interpreterPath)
  {
    this.interpreterPath = interpreterPath;
  }

  public String getLocalRepoPath()
  {
    return localRepoPath;
  }

  public void setLocalRepoPath(final String localRepoPath)
  {
    this.localRepoPath = localRepoPath;
  }

  public int getConnectTimeout()
  {
    return connectTimeout;
  }

  public void setConnectTimeout(final int connectTimeout)
  {
    this.connectTimeout = connectTimeout;
  }

  public int getMaxPoolSize()
  {
    return maxPoolSize;
  }

  public void setMaxPoolSize(final int maxPoolSize)
  {
    this.maxPoolSize = maxPoolSize;
  }

  public int getCloseTimeoutMillis()
  {
    return closeTimeoutMillis;
  }

  public void setCloseTimeoutMillis(final int closeTimeoutMillis)
  {
    this.closeTimeoutMillis = closeTimeoutMillis;
  }

  public int getWaitBetweenKillsMillis()
  {
    return waitBetweenKillsMillis;
  }

  public void setWaitBetweenKillsMillis(final int waitBetweenKillsMillis)
  {
    this.waitBetweenKillsMillis = waitBetweenKillsMillis;
  }

  public String getParagraphOutputDir()
  {
    return paragraphOutputDir;
  }

  public void setParagraphOutputDir(final String paragraphOutputDir)
  {
    this.paragraphOutputDir = paragraphOutputDir;
  }

  public int getMaxParagraphOutput()
  {
    return maxParagraphOutput;
  }

  public void setMaxParagraphOutput(final int maxParagraphOutput)
  {
    this.maxParagraphOutput = maxParagraphOutput;
  }
}
