/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */
package org.terracotta.angela.agent.kit;

import org.terracotta.angela.common.TerracottaToolInstance;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;

import java.io.File;
import java.util.Map;
import java.util.function.BiFunction;

public class ToolInstall {
  private final TerracottaToolInstance toolInstance;
  private final File kitDir;
  private final File workingDir;
  private final Distribution distribution;

  public ToolInstall(File kitDir, File workingDir, Distribution distribution, BiFunction<Map<String, String>, String[], ToolExecutionResult> operation) {
    this.kitDir = kitDir;
    this.workingDir = workingDir;
    this.distribution = distribution;
    this.toolInstance = new TerracottaToolInstance(operation);
  }

  public TerracottaToolInstance getInstance() {
    return toolInstance;
  }

  public File getWorkingDir() {
    return workingDir;
  }

  public File getKitDir() {
    return kitDir;
  }
  
  public Distribution getDistribution() {
    return distribution;
  }
}
